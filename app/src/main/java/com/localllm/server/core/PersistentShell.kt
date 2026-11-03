package com.localllm.server.core

import android.content.Context
import java.io.OutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock

/**
 * 持久 shell 会话：一个常驻的 proot `/bin/bash`，`run_bash` 之间保留状态。
 *
 * 为什么要有它：一次性 `sh -c` 每次都是新进程，`cd /tmp`、`export FOO=1`、shell 函数、
 * 后台任务在下一次调用里全部消失。模型于是被迫把每条命令写成 `cd xxx && ...` 的长串
 * （小模型还经常忘），也做不了「先 cd 到项目里，然后连着跑五条命令」这种自然的事。
 *
 * 实现要点（都是为了不踩坑）：
 * - 命令包在 `{ ... }` 里执行而不是 `( ... )`：子 shell 里的 cd/export 不会回流到会话。
 * - 每条命令后按固定格式打一行哨兵 `__L2S_END_<token>__ <退出码> <PWD>`，
 *   token 每次不同，所以即使用户自己 echo 同名文本也不会被误判成结束。
 * - 命令的 stdin 接到 /dev/null：否则 `cat`、`read` 这类会把后面的哨兵行吃掉，
 *   表现是「命令永远不结束」。
 * - 输出由独立线程读进有界队列；超时/进程死亡时**连同整棵 proot 树一起杀掉**重建，
 *   因为 `sh` 没有可靠的「中断当前前台命令但保留会话」的办法。
 * - 每个聊天会话一条 shell（用 sid 键），互不干扰；空闲 20 分钟自动回收。
 */
object PersistentShell {

    /** 输出回传上限：环境工具的产出也会进上下文，超长截断 */
    private const val MAX_OUTPUT_CHARS = 6000

    /** 空闲多久回收：proot 里一个 bash 常驻也要十几 MB，别一直占着 */
    private const val IDLE_MS = 20 * 60_000L

    /** 读取队列容量：防止后台任务疯狂输出把内存吃干（满了丢行并告知） */
    private const val QUEUE_CAPACITY = 4096

    /** 读到哨兵后再收多久残流（管道/stdio 缓冲的尾巴），有总时限免得被常驻任务拖住 */
    private const val SETTLE_MS = 400L

    data class Result(
        val exitCode: Int,
        /** 本次命令的输出（不含哨兵行） */
        val output: String,
        /** 命令执行完之后 shell 所在的目录；拿不到就是 null */
        val cwd: String?,
        /** 这次调用是否新建了 shell（首次 / 超时被杀 / 空闲回收 / 主动 reset）：状态从零开始 */
        val freshSession: Boolean,
        /**
         * 这条 shell 是被**重建**的（之前有过、现在没了），而不是本次会话第一次建立。
         * 界面提示文案要靠它区分：第一次建立时说「之前的 cd 不再有效」会让人莫名其妙。
         */
        val rebuilt: Boolean = false,
        val timedOut: Boolean = false,
        /** shell 自己退出了（比如命令里有 exit）：下一轮会重建，状态已丢 */
        val died: Boolean = false,
        /** **这条命令期间**仍在跑的后台进程 pid（逗号分隔）；null = 没有 */
        val backgroundJobs: String? = null,
        /** 输出被截断或丢行 */
        val truncated: Boolean = false,
    )

    private sealed class Ev {
        class Line(val text: String) : Ev()
        object Eof : Ev()
    }

    private class Session(
        val proc: Process,
        val stdin: OutputStream,
        val queue: LinkedBlockingQueue<Ev>,
        val reader: Thread,
        val overflow: AtomicBoolean,
    ) {
        /** 同一会话串行执行：两条命令同时写进同一个 stdin，输出就串味了 */
        val lock = ReentrantLock()

        @Volatile
        var lastUsedAt: Long = System.currentTimeMillis()
    }

    private val sessions = ConcurrentHashMap<String, Session>()
    private val startLock = Any()

    fun hasSession(sid: String): Boolean = sessions[sid]?.proc?.isAlive == true

    /** 主动清空：下次调用会从工作目录重新开始 */
    fun reset(sid: String) = close(sid)

    /**
     * 中断当前正在跑的命令。
     *
     * sh 没有「打断前台命令但保留会话」的可靠办法（发 SIGINT 给进程组会把 shell 自己也带上，
     * 交给后台再 wait 又会让 cd/export 落进子 shell），所以直接杀整棵 proot 树：
     * 下一轮自动重建，cd/env 状态丢失——这一点在界面上会明确写出来。
     */
    fun abort(sid: String) = close(sid)

    fun closeAll() {
        sessions.keys.toList().forEach { close(it) }
    }

    /**
     * 在持久会话里跑一条命令。
     *
     * **阻塞调用**：整个等待过程会卡在调用线程上。所以这里统一切到 IO 线程——
     * 曾经因为调用方（adb/install 工具）在主线程直接调它，命令跑几秒就把输入分发卡住，
     * 系统判 ANR 直接杀进程（真机实测：ANR in com.localllm.server，
     * 堆栈停在 PersistentShell.run → LinkedBlockingQueue.poll）。切在这里而不是各个调用方，
     * 这样谁都别想再踩一遍。
     *
     * @param initialCwd 会话（或重建后的会话）启动时所在目录
     * @param cdTo       只有模型**明确**给了 cwd 才带；带了就会改变会话当前目录（这正是持久化的意义）
     */
    suspend fun run(
        context: Context,
        sid: String,
        command: String,
        initialCwd: String,
        cdTo: String?,
        timeoutMs: Long,
        reset: Boolean = false,
    ): Result = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        runBlockingInternal(context, sid, command, initialCwd, cdTo, timeoutMs, reset)
    }

    private fun runBlockingInternal(
        context: Context,
        sid: String,
        command: String,
        initialCwd: String,
        cdTo: String?,
        timeoutMs: Long,
        reset: Boolean,
    ): Result {
        // 记下「这条会话之前有没有 shell」：决定了返回文案是「新建」还是「重建」
        val hadBefore = sessions[sid] != null
        if (reset) close(sid)
        var fresh = false
        var s = current(sid)
        if (s == null) {
            synchronized(startLock) {
                s = current(sid)
                if (s == null) {
                    val started = start(context, initialCwd)
                    sessions[sid] = started
                    s = started
                    fresh = true
                }
            }
        }
        val session = s ?: return Result(-2, "无法启动持久 shell", null, true)

        session.lock.lock()
        try {
            val token = java.lang.Long.toString(System.nanoTime(), 36)
            val created = fresh || !session.proc.isAlive
            val out = StringBuilder()
            var exitCode = 0
            var pwd: String? = null
            var jobs: String? = null
            var timedOut = false
            var died = false
            var truncated = false

            val wrote = runCatching {
                session.stdin.write(ShellProtocol.buildScript(command, cdTo, token).toByteArray())
                session.stdin.flush()
            }.isSuccess
            if (!wrote) died = true

            if (!died) {
                val deadline = System.currentTimeMillis() + timeoutMs
                while (true) {
                    val remain = deadline - System.currentTimeMillis()
                    val ev = if (remain <= 0) null else
                        runCatching { session.queue.poll(remain, TimeUnit.MILLISECONDS) }.getOrNull()
                    if (ev == null) {
                        timedOut = true
                        break
                    }
                    when (ev) {
                        is Ev.Eof -> {
                            died = true
                            break
                        }

                        is Ev.Line -> {
                            val sentinel = ShellProtocol.parseSentinel(ev.text, token)
                            if (sentinel != null) {
                                exitCode = sentinel.exitCode
                                pwd = sentinel.pwd
                                jobs = sentinel.backgroundJobs
                                break
                            }
                            if (out.length < MAX_OUTPUT_CHARS) {
                                out.append(ev.text).append('\n')
                            } else {
                                truncated = true
                            }
                        }
                    }
                }
            }

            if (session.overflow.get()) truncated = true
            // 读到哨兵不等于输出已经收干净：命令里的内建命令（echo/printf）往管道写时
            // 可能还压在 bash 的 stdio 缓冲里，晚几毫秒才出来。不做这一步的话，尾巴会被
            // 算到下一条命令头上——表现出来就是「输出莫名其妙串了」。
            // 有总时限：后台任务一直往 stdout 写的话，不能在这里无限收。
            val drainDeadline = System.currentTimeMillis() + SETTLE_MS
            while (!died && !timedOut && System.currentTimeMillis() < drainDeadline) {
                val tail = runCatching { session.queue.poll(60, TimeUnit.MILLISECONDS) }
                    .getOrNull() ?: break
                if (tail is Ev.Eof) break
                val text = (tail as Ev.Line).text
                if (out.length < MAX_OUTPUT_CHARS) out.append(text).append('\n') else truncated = true
            }
            // 超时/进程没了：这一轮之后会话已经不可信，直接葬掉，下次重建
            if (timedOut || died) {
                close(sid)
                if (timedOut) exitCode = 124 else exitCode = 137
            }
            return Result(
                exitCode = exitCode,
                output = out.toString().trimEnd(),
                cwd = pwd,
                freshSession = created,
                rebuilt = created && hadBefore,
                timedOut = timedOut,
                died = died && !timedOut,
                backgroundJobs = jobs,
                truncated = truncated,
            )
        } finally {
            session.lastUsedAt = System.currentTimeMillis()
            session.lock.unlock()
        }
    }

    /** 取可用会话：进程死了或空闲太久就回收掉 */
    private fun current(sid: String): Session? {
        val s = sessions[sid] ?: return null
        val idle = System.currentTimeMillis() - s.lastUsedAt > IDLE_MS
        if (!s.proc.isAlive || idle) {
            if (idle && s.proc.isAlive) {
                ServerProcessManager.appLog("持久 shell 空闲超时（${IDLE_MS / 60_000} 分钟），已回收")
            }
            close(sid)
            return null
        }
        return s
    }

    private fun close(sid: String) {
        val s = sessions.remove(sid) ?: return
        runCatching { s.stdin.close() }
        runCatching { s.proc.destroy() }
        runCatching {
            if (!s.proc.waitFor(2, TimeUnit.SECONDS)) s.proc.destroyForcibly()
        }
        runCatching { s.reader.interrupt() }
    }

    private fun start(context: Context, cwd: String): Session {
        val args = LinuxRuntime.prootArgs(context, cwd)
        // --noprofile --norc：别让 rootfs 里的 profile 打横幅，那些行会混进第一条命令的输出
        args.addAll(listOf("/bin/bash", "--noprofile", "--norc"))
        val pb = ProcessBuilder(args)
        pb.redirectErrorStream(true)
        LinuxRuntime.applyHostEnv(context, pb)
        val proc = pb.start()
        val queue = LinkedBlockingQueue<Ev>(QUEUE_CAPACITY)
        val overflow = AtomicBoolean(false)
        val reader = Thread({
            runCatching {
                proc.inputStream.bufferedReader(Charsets.UTF_8).use { r ->
                    while (true) {
                        val line = r.readLine() ?: break
                        if (!queue.offer(Ev.Line(line))) overflow.set(true)
                    }
                }
            }
            // EOF：腾一格把结束标记塞进去，否则队列满时这里会被静默丢掉、调用方只能等超时
            while (!queue.offer(Ev.Eof)) queue.poll()
        }, "l2s-persistent-shell")
        reader.isDaemon = true
        reader.start()
        return Session(proc, proc.outputStream, queue, reader, overflow)
    }
}

/**
 * 持久 shell 的文本协议（纯函数，单测直接覆盖）。
 *
 * 每次调用的输入脚本形如：
 * ```
 * cd -- '/storage/emulated/0/Download'      # 只有模型明确给了 cwd 才带
 * {
 * <用户命令>
 * } </dev/null
 * __l2s_rc=$?; printf '__L2S_END_<token>|%s|%s|%s\n' "$__l2s_rc" "$PWD" "$(jobs -p | tr '\n' ',')"
 * ```
 *
 * 用 `|` 分隔而不是空格：PWD 里可能有空格（"My Documents"），按空格切会把目录切碎。
 * 第三个字段是仍在跑的后台进程 pid —— 它们的输出会混进后续命令，必须让模型知道。
 */
internal object ShellProtocol {

    internal data class Sentinel(val exitCode: Int, val pwd: String?, val backgroundJobs: String?)

    internal fun sentinelPrefix(token: String): String = "__L2S_END_${token}__"

    /** POSIX 单引号转义：' → '\'' */
    internal fun quoteSingle(s: String): String = "'" + s.replace("'", "'\\''") + "'"

    internal fun buildScript(command: String, cdTo: String?, token: String): String = buildString {
        if (cdTo != null && cdTo.isNotBlank()) {
            append("cd -- ").append(quoteSingle(cdTo)).append('\n')
        }
        // { } 而不是 ( )：子 shell 里的 cd/export 不回落到会话，持久化就白做了
        append("{\n").append(command.trimEnd()).append("\n} </dev/null\n")
        // \$ 是 shell 的变量/命令替换占位（Kotlin 里转义，免得被当成模板）
        append("__l2s_rc=\$?; printf '").append(sentinelPrefix(token))
            .append("|%s|%s|%s\\n' \"\$__l2s_rc\" \"\$PWD\" \"\$(jobs -p | tr '\\n' ',')\"\n")
    }

    /** 解析哨兵行；不是哨兵返回 null */
    internal fun parseSentinel(line: String, token: String): Sentinel? {
        val prefix = sentinelPrefix(token) + "|"
        if (!line.startsWith(prefix)) return null
        val parts = line.substring(prefix.length).split('|')
        val code = parts.getOrNull(0)?.trim()?.toIntOrNull() ?: 0
        val pwd = parts.getOrNull(1)?.trim()?.ifEmpty { null }
        val jobs = parts.getOrNull(2)?.trim()?.trim(',')?.ifEmpty { null }
        return Sentinel(code, pwd, jobs)
    }
}

