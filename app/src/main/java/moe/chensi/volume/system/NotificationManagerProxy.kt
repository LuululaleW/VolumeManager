package moe.chensi.volume.system

import android.app.NotificationManager
import android.content.Context
import android.util.Log
import moe.chensi.volume.EnableBinderProxy
import moe.chensi.volume.ToggleableBinderProxy
import org.joor.Reflect
import java.util.WeakHashMap

class NotificationManagerProxy private constructor(context: Context) {
    companion object {
        private const val TAG = "NotificationManagerProxy"

        private val cache = WeakHashMap<Context, NotificationManagerProxy>()

        operator fun invoke(context: Context): NotificationManagerProxy {
            return cache.getOrPut(context) { NotificationManagerProxy(context) }
        }
    }

    private val notificationManager = context.getSystemService(NotificationManager::class.java)!!

    init {
        runCatching {
            val service = Reflect.onClass(NotificationManager::class.java).call("getService").get<Any>()
            ToggleableBinderProxy.wrap(service)
        }.onFailure { Log.w(TAG, "Failed to wrap NotificationManager service", it) }
    }

    @EnableBinderProxy
    fun getCurrentInterruptionFilter(): Int {
        return notificationManager.currentInterruptionFilter
    }

    @EnableBinderProxy
    fun setInterruptionFilter(filter: Int) {
        notificationManager.setInterruptionFilter(filter)
    }
}
