package moe.chensi.volume

import android.accessibilityservice.AccessibilityService
import android.animation.Animator
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.AbstractComposeView
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import moe.chensi.volume.compose.AppVolumeList
import moe.chensi.volume.compose.SystemVolumePanel
import moe.chensi.volume.compose.VolumeChangeObserver
import moe.chensi.volume.compose.VolumeKeyTargetSelector
import moe.chensi.volume.data.App
import moe.chensi.volume.system.ActivityTaskManagerProxy
import moe.chensi.volume.ui.theme.VolumeManagerTheme
import org.joor.Reflect
import java.util.Objects
import kotlin.math.roundToInt

@SuppressLint("AccessibilityPolicy")
class Service : AccessibilityService() {
    companion object {
        const val ACTION_SHOW_VIEW = "moe.chensi.volume.ACTION_SHOW_VIEW"

        private const val TAG = "VolumeManager.Service"

        private const val NOTIFICATION_CHANNEL_ID = "service"
        private const val NOTIFICATION_ID = 1

        private const val ANIMATION_DURATION = 300L

        private const val IDLE_TIMEOUT = 5000L
        private const val AUTO_REPEAT_DELAY = 100L
        private const val AUTO_REPEAT_INITIAL_DELAY = 500L
    }

    private val windowManager: WindowManager by lazy {
        Objects.requireNonNull(
            getSystemService(
                WindowManager::class.java
            )!!
        )
    }
    private lateinit var manager: Manager
    private lateinit var app: MyApplication
    private var preferenceScope: CoroutineScope? = null

    private val handler = object : Handler(Looper.getMainLooper()) {
        fun hideView() {
            if (viewVisible) {
                Log.i(TAG, "animate out")
                animateAlpha(layoutParams.alpha, 0f, ANIMATION_DURATION) {
                    if (!viewVisible) {
                        Log.i(TAG, "remove view")
                        view?.let { currentView ->
                            currentView.background = null
                            lifecycle?.currentState = Lifecycle.State.DESTROYED
                            runCatching { windowManager.removeView(currentView) }
                                .onFailure { Log.w(TAG, "Failed to remove overlay", it) }
                            view = null
                        }
                    }
                }
                viewVisible = false
            }
        }

        private val hideViewRunnable = Runnable(::hideView)

        fun startIdleTimer() {
            removeCallbacks(hideViewRunnable)
            postDelayed(hideViewRunnable, IDLE_TIMEOUT)
        }

        private var repeatAdjustVolumeDirection = 0
        private var repeatVolumeTarget: VolumeKeyTarget? = null
        private val repeatAdjustVolumeRunnable: Runnable = Runnable {
            adjustVolume()
            postDelayed(repeatAdjustVolumeRunnable, AUTO_REPEAT_DELAY)
        }

        private fun adjustVolume() {
            when (val target = repeatVolumeTarget ?: effectiveVolumeKeyTarget()) {
                is VolumeKeyTarget.App -> {
                    val app = manager.apps[target.packageName]
                    if (app == null) {
                        adjustSystemVolume()
                    } else {
                        val step = (app.volume * 20f).roundToInt()
                        val nextStep = (step + repeatAdjustVolumeDirection).coerceIn(0, 20)
                        if (nextStep != step) {
                            app.volume = nextStep / 20f
                        }
                    }
                }

                VolumeKeyTarget.Media -> adjustSystemVolume()
            }
            startIdleTimer()
        }

        private fun adjustSystemVolume() {
            runCatching {
                manager.audioManager.adjustSuggestedStreamVolume(
                    repeatAdjustVolumeDirection, AudioManager.USE_DEFAULT_STREAM_TYPE, 0
                )
            }.onFailure {
                Log.w(TAG, "Failed to adjust system volume", it)
            }
            VolumeChangeObserver.notifyVolumeChanged()
        }

        fun startRepeatAdjustVolume(direction: Int) {
            repeatAdjustVolumeDirection = direction
            repeatVolumeTarget = effectiveVolumeKeyTarget()
            removeCallbacks(repeatAdjustVolumeRunnable)
            adjustVolume()
            postDelayed(repeatAdjustVolumeRunnable, AUTO_REPEAT_INITIAL_DELAY)
        }

        fun stopRepeatAdjustVolume() {
            removeCallbacks(repeatAdjustVolumeRunnable)
            repeatVolumeTarget = null
            startIdleTimer()
        }
    }

    private var lifecycle: LifecycleRegistry? = null

    private fun createView(): View {
        return object : AbstractComposeView(this) {
            init {
                val owner = object : SavedStateRegistryOwner {
                    private val lifecycleRegistry = LifecycleRegistry(this)

                    private val savedStateRegistryController =
                        SavedStateRegistryController.create(this)

                    init {
                        savedStateRegistryController.performRestore(null)
                        lifecycleRegistry.currentState = Lifecycle.State.STARTED
                        this@Service.lifecycle = lifecycleRegistry
                    }

                    override val lifecycle: Lifecycle
                        get() = lifecycleRegistry

                    override val savedStateRegistry: SavedStateRegistry
                        get() = savedStateRegistryController.savedStateRegistry
                }

                setViewTreeLifecycleOwner(owner)
                setViewTreeSavedStateRegistryOwner(owner)
            }

            override fun onAttachedToWindow() {
                super.onAttachedToWindow()

                Log.i(TAG, "onAttachedToWindow manufacturer: ${Build.MANUFACTURER}")

                @Suppress("SpellCheckingInspection")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                    windowManager.isCrossWindowBlurEnabled &&
                    isHardwareAccelerated &&
                    Build.MANUFACTURER != "realme"
                ) {
                    runCatching {
                        background =
                            Reflect.on(rootSurfaceControl).call("createBackgroundBlurDrawable").apply {
                                call("setBlurRadius", 200)
                                call("setCornerRadius", 40f)
                            }.get()
                    }.onFailure { Log.w(TAG, "Failed to create blur background", it) }
                }

                this@Service.handler.startIdleTimer()
            }

            @SuppressLint("ClickableViewAccessibility")
            override fun onTouchEvent(event: MotionEvent): Boolean {
                Log.i(TAG, "onTouchEvent ${event.actionMasked}")

                if (event.actionMasked == MotionEvent.ACTION_OUTSIDE) {
                    this@Service.handler.hideView()
                    return true
                }

                return super.onTouchEvent(event)
            }

            @Composable
            override fun Content() {
                val hooksEnabled by app.systemVolumeButtonHooksEnabled.collectAsState()
                val selectedApp = (volumeKeyTarget as? VolumeKeyTarget.App)
                    ?.packageName
                    ?.let { manager.apps[it] }
                    ?.takeIf(::isVolumeKeyTargetApp)
                val hasActiveApps = manager.apps.values.any(::isVolumeKeyTargetApp)

                return VolumeManagerTheme {
                    Surface(
                        color = Color(1f, 1f, 1f, 0.3f),
                        contentColor = MaterialTheme.colorScheme.onSurface,
                        shape = RoundedCornerShape(40f)
                    ) {
                        Column(
                            modifier = Modifier.padding(20.dp, 16.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            if (hooksEnabled) {
                                VolumeKeyTargetSelector(
                                    selectedApp = selectedApp,
                                    hasActiveApps = hasActiveApps,
                                    onSelectApp = this@Service::selectAppVolumeTarget,
                                    onSelectMedia = this@Service::selectMediaVolumeTarget
                                )
                            }

                            AppVolumeList(
                                apps = manager.apps.values,
                                showAll = false,
                                selectedVolumeKeyPackage = if (hooksEnabled) {
                                    selectedApp?.packageName
                                } else {
                                    null
                                },
                                onSelectVolumeKeyApp = if (hooksEnabled) {
                                    this@Service::selectVolumeKeyApp
                                } else {
                                    null
                                },
                                onChange = this@Service.handler::startIdleTimer,
                                contentAfter = {
                                    item("system_volume_panel") {
                                        SystemVolumePanel(
                                            audioManager = manager.audioManager,
                                            notificationManagerProxy = manager.notificationManagerProxy,
                                            showCallVolumeAlways = false,
                                            applyVisibilityFilter = true,
                                            allowVisibilityConfig = false,
                                            isSliderVisible = manager::isSystemSliderVisible,
                                            onSliderVisibilityChange = manager::setSystemSliderVisible,
                                            onChange = this@Service.handler::startIdleTimer
                                        )
                                    }
                                }
                            )
                        }
                    }
                }
            }
        }
    }

    private val layoutParams by lazy {
        WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, // Width
            WindowManager.LayoutParams.WRAP_CONTENT, // Height
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT // Make the background translucent
        ).apply {
            gravity = Gravity.CENTER // Center the view
        }
    }

    private var view: View? = null
    private var viewVisible = false
    private var floatingButton: FloatingButton? = null
    private var volumeKeyTarget by mutableStateOf<VolumeKeyTarget>(VolumeKeyTarget.Media)
    private var mediaTargetPinned = false

    private fun isVolumeKeyTargetApp(app: App): Boolean {
        return app.isPlayer && !app.hidden && app.hasBeenPlayingRecently
    }

    private fun activeVolumeKeyApps(): List<App> {
        return manager.apps.values.filter(::isVolumeKeyTargetApp)
    }

    private fun pickAppForVolumeKeys(): App? {
        val candidates = activeVolumeKeyApps()
        if (candidates.isEmpty()) {
            return null
        }

        val foregroundPackage = runCatching {
            activityTaskManager.getForegroundTask()?.app
        }.getOrNull()

        return candidates.firstOrNull {
            it.packageName == foregroundPackage && it.isPlaying
        } ?: candidates.filter { it.isPlaying }.maxByOrNull { it.lastPlayedAt }
        ?: candidates.firstOrNull { it.packageName == foregroundPackage }
        ?: candidates.maxByOrNull { it.lastPlayedAt }
    }

    private fun resolveVolumeKeyTarget() {
        if (mediaTargetPinned) {
            volumeKeyTarget = VolumeKeyTarget.Media
            return
        }

        volumeKeyTarget = pickAppForVolumeKeys()?.let {
            VolumeKeyTarget.App(it.packageName)
        } ?: VolumeKeyTarget.Media
    }

    private fun effectiveVolumeKeyTarget(): VolumeKeyTarget {
        val target = volumeKeyTarget
        if (target is VolumeKeyTarget.App) {
            val app = manager.apps[target.packageName]
            if (app != null && isVolumeKeyTargetApp(app)) {
                return target
            }
        }
        return VolumeKeyTarget.Media
    }

    private fun selectAppVolumeTarget() {
        val currentApp = (volumeKeyTarget as? VolumeKeyTarget.App)
            ?.packageName
            ?.let { manager.apps[it] }
            ?.takeIf(::isVolumeKeyTargetApp)
        val app = currentApp ?: pickAppForVolumeKeys() ?: return

        mediaTargetPinned = false
        volumeKeyTarget = VolumeKeyTarget.App(app.packageName)
        handler.startIdleTimer()
    }

    private fun selectVolumeKeyApp(app: App) {
        mediaTargetPinned = false
        volumeKeyTarget = VolumeKeyTarget.App(app.packageName)
        handler.startIdleTimer()
    }

    private fun selectMediaVolumeTarget() {
        mediaTargetPinned = true
        volumeKeyTarget = VolumeKeyTarget.Media
        handler.startIdleTimer()
    }

    private fun showView() {
        if (!viewVisible && app.systemVolumeButtonHooksEnabled.value) {
            resolveVolumeKeyTarget()
        }

        if (view == null) {
            Log.i(TAG, "add view")
            // The view doesn't respond to input events if reused
            val newView = createView()
            layoutParams.alpha = 0f
            val added = runCatching { windowManager.addView(newView, layoutParams) }
                .onFailure { Log.e(TAG, "Failed to add overlay", it) }
                .isSuccess
            if (!added) {
                return
            }
            view = newView
        }

        if (!viewVisible) {
            Log.i(TAG, "animate in")
            animateAlpha(layoutParams.alpha, 1f, ANIMATION_DURATION)
            viewVisible = true
        }

        handler.startIdleTimer()
    }

    private fun setFloatingButtonPreferences(enabled: Boolean, sizeDp: Int) {
        if (enabled) {
            val button = floatingButton ?: FloatingButton(
                this,
                windowManager,
                ::showView,
                sizeDp
            ).also { floatingButton = it }
            button.setSize(sizeDp)
            button.show()
        } else {
            floatingButton?.setSize(sizeDp)
            floatingButton?.hide()
        }
    }

    private var currentAnimator: ValueAnimator? = null

    private fun animateAlpha(from: Float, to: Float, duration: Long, onEnd: (() -> Unit)? = null) {
        currentAnimator?.cancel()

        val animator = ValueAnimator.ofFloat(from, to)
        animator.duration = duration
        animator.interpolator = AccelerateDecelerateInterpolator()

        animator.addUpdateListener { animation ->
            val currentView = view ?: return@addUpdateListener
            layoutParams.alpha = animation.animatedValue as Float
            runCatching { windowManager.updateViewLayout(currentView, layoutParams) }
                .onFailure { Log.w(TAG, "Failed to update overlay", it) }
        }

        animator.addListener(object : Animator.AnimatorListener {
            var canceled = false

            override fun onAnimationStart(animation: Animator) {}

            override fun onAnimationEnd(animation: Animator) {
                if (canceled) {
                    return
                }

                layoutParams.alpha = to
                view?.let { currentView ->
                    runCatching { windowManager.updateViewLayout(currentView, layoutParams) }
                        .onFailure { Log.w(TAG, "Failed to update overlay", it) }
                }

                onEnd?.invoke()
            }

            override fun onAnimationCancel(animation: Animator) {
                canceled = true
            }

            override fun onAnimationRepeat(animation: Animator) {}
        })

        animator.start()
        currentAnimator = animator
    }

    private var receiverRegistered = false

    private val broadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            Log.i(TAG, "onReceive ${intent.action}")
            if (intent.action == ACTION_SHOW_VIEW) {
                showView()
            }
        }
    }

    override fun onServiceConnected() {
        Log.i(TAG, "onServiceConnected")

        runCatching { startForegroundNotification() }
            .onFailure {
                Log.e(TAG, "Failed to start foreground service", it)
                stopSelf()
                return
            }

        app = super.getApplication() as MyApplication
        manager = app.manager

        if (!receiverRegistered) {
            ContextCompat.registerReceiver(
                this,
                broadcastReceiver,
                IntentFilter(ACTION_SHOW_VIEW),
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
            receiverRegistered = true
        }

        preferenceScope?.cancel()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        preferenceScope = scope
        scope.launch {
            combine(app.floatingButtonEnabled, app.floatingButtonSize) { enabled, sizeDp ->
                enabled to sizeDp
            }.collect { (enabled, sizeDp) ->
                setFloatingButtonPreferences(enabled, sizeDp)
            }
        }
        scope.launch {
            app.systemVolumeButtonHooksEnabled.collect { enabled ->
                if (!enabled) {
                    handler.stopRepeatAdjustVolume()
                }
            }
        }

        Log.i(TAG, "onServiceConnected done ${serviceInfo.capabilities.toString(2)}")
    }

    private fun startForegroundNotification() {
        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.createNotificationChannel(
            NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                getString(R.string.foreground_notification_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.foreground_notification_channel_description)
                setShowBadge(false)
            }
        )

        val notification = Notification.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle(getString(R.string.foreground_notification_title))
            .setContentText(getString(R.string.foreground_notification_text))
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(
                PendingIntent.getActivity(
                    this,
                    0,
                    Intent(this, MainActivity::class.java),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            )
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
    }

    override fun onInterrupt() {
        Log.i(TAG, "onInterrupt")
        handler.stopRepeatAdjustVolume()
    }

    override fun onDestroy() {
        Log.i(TAG, "onDestroy")

        handler.stopRepeatAdjustVolume()
        handler.removeCallbacksAndMessages(null)
        preferenceScope?.cancel()
        floatingButton?.hide()
        stopForeground(STOP_FOREGROUND_REMOVE)
        Toast.makeText(this, R.string.accessibility_service_died, Toast.LENGTH_SHORT).show()

        if (receiverRegistered) {
            try {
                unregisterReceiver(broadcastReceiver)
            } catch (e: IllegalArgumentException) {
                Log.w(TAG, "Receiver was already unregistered", e)
            }
            receiverRegistered = false
        }
        super.onDestroy()
    }

    val activityTaskManager by lazy { ActivityTaskManagerProxy(this) }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (!::manager.isInitialized || !::app.isInitialized) {
            return false
        }

        Log.i(
            TAG,
            "onKeyEvent action = ${event.action}, key code = ${event.keyCode}, shizuku permission = ${manager.shizukuStatus}"
        )

        // Only handle `VOLUME_UP` and `VOLUME_DOWN`
        if (event.keyCode != KeyEvent.KEYCODE_VOLUME_UP && event.keyCode != KeyEvent.KEYCODE_VOLUME_DOWN) {
            return false
        }

        if (!app.systemVolumeButtonHooksEnabled.value) {
            handler.stopRepeatAdjustVolume()
            return false
        }

        // Ignore if Shizuku is not ready
        if (manager.shizukuStatus != Manager.ShizukuStatus.Connected) {
            handler.stopRepeatAdjustVolume()
            return false
        }

        // Check foreground task ignorance list
        val task = runCatching { activityTaskManager.getForegroundTask() }
            .onFailure { Log.w(TAG, "Failed to read foreground task", it) }
            .getOrNull()
        Log.i(TAG, "onKeyEvent foreground task: $task")

        if (task != null) {
            val foregroundApp = manager.apps[task.app]
            if (foregroundApp != null && foregroundApp.disableVolumeButtons) {
                handler.stopRepeatAdjustVolume()
                return false
            }
        }

        when (event.action) {
            KeyEvent.ACTION_DOWN -> {
                if (event.repeatCount == 0) {
                    showView()
                    handler.startRepeatAdjustVolume(
                        if (event.keyCode == KeyEvent.KEYCODE_VOLUME_UP) {
                            AudioManager.ADJUST_RAISE
                        } else {
                            AudioManager.ADJUST_LOWER
                        }
                    )
                }
            }

            KeyEvent.ACTION_UP -> handler.stopRepeatAdjustVolume()
        }

        return true
    }
}
