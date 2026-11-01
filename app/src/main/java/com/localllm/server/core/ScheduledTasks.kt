package com.localllm.server.core

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.localllm.server.MainActivity
import com.localllm.server.R
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 定时任务：到点把一条指令排进任务队列，并发通知提醒（点通知打开 App 就开始跑）。
 *
 * 为什么是「排进队列 + 通知」而不是静默跑完：真正无人值守要解决两件事——
 * 后台拉起引擎、以及后台实例与界面实例共用同一份会话存储。这两件没做之前，
 * 谎称"全自动"只会让人以为任务跑了而其实没有。
 *
 * 调度用 AlarmManager.setExactAndAllowWhileIdle：本项目 targetSdk=28，
 * Android 12+ 的精确闹钟权限（SCHEDULE_EXACT_ALARM）不适用，不需要额外授权。
 */
object ScheduledTasks {

    private const val CHANNEL_ID = "scheduled_task"

    data class Task(
        val id: String,
        val name: String,
        val prompt: String,
        /** 每多少分钟一次；0 表示用 dailyHour/dailyMinute 的每天定时 */
        val everyMinutes: Int = 0,
        val dailyHour: Int = 8,
        val dailyMinute: Int = 0,
        val enabled: Boolean = true,
        val lastRunAt: Long = 0
    ) {
        fun describe(): String =
            if (everyMinutes > 0) "每 $everyMinutes 分钟"
            else String.format(java.util.Locale.CHINA, "每天 %02d:%02d", dailyHour, dailyMinute)

        /** 下一次触发的时间戳 */
        fun nextTriggerAt(from: Long = System.currentTimeMillis()): Long {
            if (everyMinutes > 0) return from + everyMinutes * 60_000L
            val cal = java.util.Calendar.getInstance().apply {
                timeInMillis = from
                set(java.util.Calendar.HOUR_OF_DAY, dailyHour)
                set(java.util.Calendar.MINUTE, dailyMinute)
                set(java.util.Calendar.SECOND, 0)
                set(java.util.Calendar.MILLISECOND, 0)
            }
            if (cal.timeInMillis <= from) cal.add(java.util.Calendar.DAY_OF_MONTH, 1)
            return cal.timeInMillis
        }
    }

    // ---- 存取 ----

    fun load(context: Context): List<Task> {
        val f = file(context)
        if (!f.isFile) return emptyList()
        return runCatching {
            val arr = JSONArray(f.readText())
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                Task(
                    id = o.optString("id").ifBlank { return@mapNotNull null },
                    name = o.optString("name").ifBlank { "未命名任务" },
                    prompt = o.optString("prompt"),
                    everyMinutes = o.optInt("everyMinutes"),
                    dailyHour = o.optInt("dailyHour", 8),
                    dailyMinute = o.optInt("dailyMinute", 0),
                    enabled = o.optBoolean("enabled", true),
                    lastRunAt = o.optLong("lastRunAt")
                )
            }
        }.getOrDefault(emptyList())
    }

    fun save(context: Context, tasks: List<Task>) {
        val arr = JSONArray()
        tasks.forEach { t ->
            arr.put(
                JSONObject()
                    .put("id", t.id).put("name", t.name).put("prompt", t.prompt)
                    .put("everyMinutes", t.everyMinutes)
                    .put("dailyHour", t.dailyHour).put("dailyMinute", t.dailyMinute)
                    .put("enabled", t.enabled).put("lastRunAt", t.lastRunAt)
            )
        }
        runCatching { file(context).writeText(arr.toString()) }
    }

    private fun file(context: Context) = File(context.filesDir, "scheduled_tasks.json")

    // ---- 调度 ----

    /** 重新登记所有启用的任务（增删改、开机、App 启动时都调一次） */
    fun rescheduleAll(context: Context) {
        load(context).forEach { t ->
            if (t.enabled) scheduleNext(context, t) else cancel(context, t)
        }
    }

    fun scheduleNext(context: Context, task: Task) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val at = task.nextTriggerAt()
        val pi = pendingIntent(context, task.id)
        runCatching {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        }.onFailure {
            // 老设备/受限环境下退回非精确闹钟（晚几分钟总比不触发好）
            runCatching { am.set(AlarmManager.RTC_WAKEUP, at, pi) }
        }
        ServerProcessManager.appLog("定时任务已登记：${task.name} → ${java.util.Date(at)}")
    }

    fun cancel(context: Context, task: Task) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        runCatching { am.cancel(pendingIntent(context, task.id)) }
    }

    private fun pendingIntent(context: Context, taskId: String): PendingIntent {
        val intent = Intent(context, TaskAlarmReceiver::class.java).apply {
            action = "com.localllm.server.SCHEDULED_TASK"
            putExtra("taskId", taskId)
        }
        return PendingIntent.getBroadcast(
            context, taskId.hashCode(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    // ---- 通知 ----

    fun notifyPending(context: Context, task: Task) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        runCatching {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "定时任务", NotificationManager.IMPORTANCE_DEFAULT).apply {
                    description = "定时任务到点提醒"
                }
            )
        }
        val open = PendingIntent.getActivity(
            context, task.id.hashCode(),
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val n = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_server)
            .setContentTitle("定时任务：${task.name}")
            .setContentText("已排入队列，点开立即执行：${task.prompt.take(40)}")
            .setStyle(NotificationCompat.BigTextStyle().bigText(task.prompt))
            .setContentIntent(open)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
        runCatching { nm.notify(task.id.hashCode(), n) }
    }
}

/**
 * 闹钟到点：把任务写进排队文件并提醒；同时把下一次闹钟登记好。
 * 只做「排队 + 通知」——后台真正跑起来要等下一次前台会话读队列。
 */
class TaskAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getStringExtra("taskId") ?: return
        val tasks = ScheduledTasks.load(context)
        val task = tasks.firstOrNull { it.id == id && it.enabled } ?: return

        PendingQueue.append(context, task.prompt)
        ServerProcessManager.appLog("定时任务到点：${task.name}（已排入队列）")
        ScheduledTasks.notifyPending(context, task)

        ScheduledTasks.save(
            context,
            tasks.map { if (it.id == id) it.copy(lastRunAt = System.currentTimeMillis()) else it }
        )
        ScheduledTasks.scheduleNext(context, task)
    }
}

/**
 * 排队文件的最小读写（不依赖 ViewModel）：闹钟到点时进程里可能还没有界面实例。
 * ChatViewModel 启动时会读同一个文件，把它并进内存队列。
 */
object PendingQueue {
    fun append(context: Context, text: String) {
        val f = File(context.filesDir, "task_queue.json")
        runCatching {
            val arr = if (f.isFile) runCatching { JSONArray(f.readText()) }.getOrDefault(JSONArray())
            else JSONArray()
            arr.put(JSONObject().put("sid", "").put("text", text))
            f.writeText(arr.toString())
        }
    }
}
