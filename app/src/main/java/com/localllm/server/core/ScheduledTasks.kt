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
 * 定时任务：到点由 [ScheduledRunner] 决定怎么跑。
 *
 * - 无人值守开着（默认）：后台拉起本地引擎 → 在当前会话里跑一轮 → 发结果通知；
 * - 关掉：退回老行为，只把指令排进队列 + 发通知，点开 App 才跑。
 *
 * 能做到无人值守的两个前提本轮已补上：后台能拉起引擎（ServerService 是前台服务），
 * 以及后台与界面共用同一份会话（ChatViewModel 改成进程内单例）。
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
        val lastRunAt: Long = 0,
        /** 上一次执行的结果摘要（“完成 · 812 字” / “失败：引擎没起来”），空 = 没跑过 */
        val lastNote: String = ""
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
                    lastRunAt = o.optLong("lastRunAt"),
                    lastNote = o.optString("lastNote")
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
                    .put("lastNote", t.lastNote)
            )
        }
        runCatching { file(context).writeText(arr.toString()) }
    }

    /** 记一次执行结果（时间 + 一句话摘要），界面上能直接看到「上次跑了没、结果如何」 */
    fun markRun(context: Context, taskId: String, note: String, at: Long = System.currentTimeMillis()) {
        val tasks = load(context)
        if (tasks.none { it.id == taskId }) return
        save(
            context,
            tasks.map { if (it.id == taskId) it.copy(lastRunAt = at, lastNote = note) else it }
        )
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

    private fun ensureChannel(nm: NotificationManager) {
        runCatching {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "定时任务", NotificationManager.IMPORTANCE_DEFAULT).apply {
                    description = "定时任务到点提醒与结果"
                }
            )
        }
    }

    private fun openApp(context: Context, task: Task): PendingIntent =
        PendingIntent.getActivity(
            context, task.id.hashCode(),
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    /** 排队提醒：只在「未开启无人值守」时用 */
    fun notifyPending(context: Context, task: Task) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        ensureChannel(nm)
        val n = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_server)
            .setContentTitle("定时任务：${task.name}")
            .setContentText("已排入队列，点开立即执行：${task.prompt.take(40)}")
            .setStyle(NotificationCompat.BigTextStyle().bigText(task.prompt))
            .setContentIntent(openApp(context, task))
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
        NotifDiag.post(context, nm, task.id.hashCode(), n, "定时任务")
    }

    /** 后台已经开始跑了：这条通知用来解释「手机为什么突然发热」 */
    fun notifyRunning(context: Context, task: Task) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        ensureChannel(nm)
        val n = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_server)
            .setContentTitle("定时任务执行中：${task.name}")
            .setContentText("后台拉起本地引擎并开始跑这一轮，跑完会通知你")
            .setContentIntent(openApp(context, task))
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
        NotifDiag.post(context, nm, task.id.hashCode(), n, "定时任务")
    }

    /** 跑完了：正文摘要放在通知里，点开进会话看全文 */
    fun notifyResult(context: Context, task: Task, ok: Boolean, text: String) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        ensureChannel(nm)
        val body = text.trim().ifBlank { if (ok) "已完成（没有正文）" else "失败" }
        val n = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_server)
            .setContentTitle((if (ok) "定时任务已完成：" else "定时任务失败：") + task.name)
            .setContentText(body.take(80))
            .setStyle(NotificationCompat.BigTextStyle().bigText(body.take(1200)))
            .setContentIntent(openApp(context, task))
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
        NotifDiag.post(context, nm, task.id.hashCode(), n, "定时任务")
    }
}

/**
 * 闹钟到点：交给 [ScheduledRunner] 决定是「后台直接跑」还是「排队 + 通知」，
 * 顺手把下一次闹钟登记好（进程被杀也不影响下一次触发）。
 */
class TaskAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getStringExtra("taskId") ?: return
        val tasks = ScheduledTasks.load(context)
        val task = tasks.firstOrNull { it.id == id && it.enabled } ?: return

        runCatching { ScheduledRunner.fire(context, task) }
            .onFailure {
                ServerProcessManager.appLog("定时任务触发失败（${task.name}）：${it.message}")
                ScheduledTasks.markRun(context, task.id, "失败：${it.message?.take(60)}")
            }
        // 先把下一次登记好：本轮就算出错，明天的闹钟也得在
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
