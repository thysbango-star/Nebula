package com.nebula.assistant

import android.Manifest
import android.annotation.SuppressLint
import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import com.nebula.assistant.audio.Sounds
import com.nebula.assistant.data.Store
import com.nebula.assistant.data.TaskStatus
import com.nebula.assistant.ui.MainActivity
import com.nebula.assistant.wake.Notifications
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class NebulaApp : Application() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onCreate() {
        super.onCreate()
        Notifications.createChannels(this)
        Store.init(this)
        Sounds.warmUp(this)
        getSystemService(NotificationManager::class.java)?.createNotificationChannel(
            NotificationChannel(CHANNEL_TASKS, "Task results", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "When a task finishes, fails or needs your answer."
            })
        watchTasks()
    }

    /** Brings results back: notifies when a task changes state while you're not looking at Nebula. */
    private fun watchTasks() {
        val last = mutableMapOf<String, TaskStatus>()
        Store.tasks.value.forEach { last[it.id] = it.status }
        scope.launch {
            Store.tasks.collect { list ->
                for (t in list) {
                    val prev = last[t.id]
                    last[t.id] = t.status
                    if (prev == null || prev == t.status) continue
                    val worth = t.status == TaskStatus.COMPLETED || t.status == TaskStatus.FAILED || t.status == TaskStatus.REQUIRES_INPUT
                    val foreground = ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
                    if (worth && !foreground) notify(t.id, t.title, when (t.status) {
                        TaskStatus.COMPLETED -> "Done · ${t.summary}"
                        TaskStatus.FAILED -> "Couldn't finish · ${t.summary}"
                        else -> "Needs you · ${t.input?.question ?: "tap to answer"}"
                    })
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun notify(taskId: String, title: String, text: String) {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val open = PendingIntent.getActivity(this, taskId.hashCode(),
            Intent(this, MainActivity::class.java).putExtra(MainActivity.EXTRA_TAB, "tasks").putExtra(MainActivity.EXTRA_TASK, taskId)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = NotificationCompat.Builder(this, CHANNEL_TASKS).setSmallIcon(R.drawable.ic_stat_nebula)
            .setContentTitle(title).setContentText(text).setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(open).setAutoCancel(true).setSilent(true).build()
        NotificationManagerCompat.from(this).notify(taskId.hashCode(), n)
    }

    companion object { const val CHANNEL_TASKS = "nebula_tasks" }
}
