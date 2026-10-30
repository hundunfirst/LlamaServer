package com.localllm.server.core

import android.content.Context
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * 内置 Linux 运行环境（PRoot + Ubuntu Base + Node）。
 *
 * 参考 DSH Go（ccch1mneyyy/dsh-go，AGPL-3.0）的机制，自实现：
 *  - proot 用 Termux 的 bionic 构建（libproot.so 等 4 个库在 jniLibs，由系统释放到
 *    nativeLibraryDir，targetSdk 28 下可以 exec）；
 *  - rootfs 是 ubuntu-base-24.04.5-arm64.zip + node arm64 zip（都在 assets/runtime/），
 *    首次使用时解压到 files/runtime/rootfs；
 *  - git / curl / python3 走「首次使用按需安装」：rootfs 自带 apt/dpkg（arm64 原生），
 *    bootstrap 时 apt-get install，源指向清华镜像（http，绕开 CA 证书鸡生蛋问题）。
 *
 * 定位：给本地 agent 一个能「写→跑→看报错→改」的真实环境。它不是安全边界：
 * 进程是 App 自己的 uid，rootfs 在 App 私有目录里，proot 只是用户态 chroot。
 */
object LinuxRuntime {

    private const val ROOTFS_ASSET = "runtime/rootfs.tar.gz"
    private const val NODE_ASSET = "runtime/node-arm64.tar.gz"
    private const val MARK_READY = "runtime/.ready"
    private const val MARK_APT = "runtime/.apt-installed"
    /** 内容版本：tar/结构有变化就 +1，旧设备靠它重新解压 */
    private const val READY_VERSION = "3"

    /** 输出回传上限：环境工具的产出也会进上下文，超长截断 */
    private const val MAX_OUTPUT_CHARS = 6000

    data class Result(val exitCode: Int, val output: String)

    fun rootfsDir(context: Context): File = File(context.filesDir, "runtime/rootfs")
    private fun tmpDir(context: Context): File = File(context.cacheDir, "proot-tmp")
    private fun nativeLibDir(context: Context): String = context.applicationInfo.nativeLibraryDir

    fun isReady(context: Context): Boolean =
        runCatching { File(context.filesDir, MARK_READY).readText() == READY_VERSION }.getOrDefault(false)

    /**
     * 文件工具授权目录的**真实路径**（/storage/emulated/0/...）。
     *
     * proot 里 bind 了 /storage，所以 bash 看到的这个路径和 SAF 是同一棵树——
     * 模型用 file_write 写进授权目录的文件，在 Linux 环境里直接就在这个路径下。
     * 解析不了（未授权 / 非 documents tree）时返回 null。
     */
    fun workdirAbsPath(context: Context): String? {
        val uriStr = AppSettings.workDirTreeUri
        if (uriStr.isBlank()) return null
        val uri = runCatching { android.net.Uri.parse(uriStr) }.getOrNull() ?: return null
        if ("tree" != uri.authority?.substringAfterLast('.')) return null
        val docId = runCatching {
            android.provider.DocumentsContract.getTreeDocumentId(uri)
        }.getOrNull() ?: return null
        val idx = docId.indexOf(':')
        if (idx <= 0) return null
        val storageId = docId.substring(0, idx)
        val rel = docId.substring(idx + 1)
        val root = if (storageId == "primary") "/storage/emulated/0" else "/storage/$storageId"
        return if (rel.isEmpty()) root else root + "/" + rel.replace("/", "/")
    }

    /** 首次使用：解压 rootfs + node、补运行时文件、按需安装基础工具。返回非空 = 失败原因 */
    fun prepare(context: Context): String {
        val rf = rootfsDir(context)
        if (!isReady(context)) {
            runCatching {
                context.assets.open(ROOTFS_ASSET).use { MinimalTar.extractGz(it, rf) }
                context.assets.open(NODE_ASSET).use { MinimalTar.extractGz(it, rf) }
            }.getOrElse { return "解压运行环境失败：${it.message ?: "未知错误"}" }
            applyPermissions(rf)
            writeGuestConfig(rf)
            File(rf, "root/.l2s").mkdirs()
            File(rf, "var/lib/dsh-app/empty").mkdirs()
            File(rf, "var/lib/dsh-app/shm").mkdirs()
            tmpDir(context).mkdirs()
            File(context.filesDir, MARK_READY).writeText(READY_VERSION)
        }
        if (!File(context.filesDir, MARK_APT).exists()) {
            val out = exec(
                context,
                "apt-get update -o Acquire::Retries=2 && " +
                        "DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends " +
                        "git ca-certificates curl python3",
                timeoutMs = 600_000
            )
            if (out.exitCode != 0) {
                return "基础工具安装失败（apt 退出码 ${out.exitCode}）：${out.output.take(500)}"
            }
            File(context.filesDir, MARK_APT).writeText("1")
        }
        return ""
    }

    /** 在 rootfs 里跑一条命令；cwd 是 rootfs 内路径（默认 /root） */
    fun exec(
        context: Context,
        command: String,
        cwd: String = "/root",
        timeoutMs: Long = 120_000,
        envExtra: List<String> = emptyList()
    ): Result {
        val rf = rootfsDir(context)
        val native = nativeLibDir(context)
        val args = mutableListOf(
            "$native/libproot.so",
            "--kill-on-exit", "--link2symlink", "--sysvipc", "-L", "-0",
            "--rootfs=${rf.absolutePath}", "--cwd=$cwd",
            "-b", "/dev", "-b", "/dev/urandom:/dev/random",
            "-b", "/proc",
            "-b", "/proc/self/fd:/dev/fd",
            "-b", "/proc/self/fd/0:/dev/stdin",
            "-b", "/proc/self/fd/1:/dev/stdout",
            "-b", "/proc/self/fd/2:/dev/stderr",
            "-b", "/sys",
            "-b", "${rf.absolutePath}/var/lib/dsh-app/empty:/sys/fs/selinux",
            "-b", "${rf.absolutePath}/var/lib/dsh-app/shm:/dev/shm",
            "-b", "/system", "-b", "/apex",
            // 全盘文件访问：legacy storage 下 App 能直接读 /storage，bind 进去后 bash 就是同一棵树
            "-b", "/storage",
            "/usr/bin/env", "-i",
            "HOME=/root", "USER=root", "LOGNAME=root", "SHELL=/bin/bash",
            "PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
            "LANG=C.UTF-8", "TERM=xterm-256color", "TMPDIR=/tmp"
        )
        args.addAll(envExtra)
        args.addAll(listOf("/bin/sh", "-c", command))

        val pb = ProcessBuilder(args)
        pb.redirectErrorStream(true)
        pb.environment()["PROOT_LOADER"] = "$native/libproot-loader.so"
        pb.environment()["PROOT_TMP_DIR"] = tmpDir(context).absolutePath
        pb.environment()["PROOT_L2S_DIR"] = "${rf.absolutePath}/root/.l2s"
        pb.environment()["LD_LIBRARY_PATH"] = native
        return runCatching {
            val proc = pb.start()
            // 边读边截断，避免攒到进程结束
            val sb = StringBuilder()
            proc.inputStream.bufferedReader(Charsets.UTF_8).use { r ->
                while (true) {
                    val line = r.readLine() ?: break
                    if (sb.length < MAX_OUTPUT_CHARS) {
                        sb.append(line).append('\n')
                    }
                }
            }
            val finished = proc.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
            if (!finished) {
                proc.destroy()
                if (!proc.waitFor(5, TimeUnit.SECONDS)) proc.destroyForcibly()
                return Result(124, sb.toString().trim() + "\n【命令超时（${timeoutMs / 1000}s），已强制终止】")
            }
            Result(proc.exitValue(), sb.toString().trim())
        }.getOrElse { e ->
            Result(-1, "无法启动 proot：${e.message ?: e.javaClass.simpleName}")
        }
    }

    // ---------------- 一次性准备 ----------------

    private fun extractZip(context: Context, asset: String, dest: File) {
        dest.mkdirs()
        context.assets.open(asset).use { MinimalTar.extractGz(it, dest) }
    }
    /** tar 里符号链接已原样解出；这里只补 zip 没有的 unix 权限位：
     *  bin/sbin 目录下全部可执行，其余 0644 */
    private fun applyPermissions(rf: File) {
        val execDirs = setOf(
            "bin", "sbin", "usr/bin", "usr/sbin", "usr/local/bin", "usr/local/sbin",
            "usr/libexec", "usr/lib", "usr/local/lib", "lib", "lib64", "usr/lib64"
        )
        fun walk(dir: File) {
            dir.listFiles()?.forEach { f ->
                if (f.isDirectory) {
                    f.setExecutable(true, false)
                    walk(f)
                } else {
                    val parent = f.parentFile?.name ?: ""
                    val grand = f.parentFile?.parentFile?.name ?: ""
                    val execLike = execDirs.contains(f.parentFile?.absolutePath
                        ?.removePrefix(rf.absolutePath)?.trimStart('/') ?: "") ||
                            execDirs.contains(parent) ||
                            (grand == "bin" && parent == "sbin") ||
                            f.name == "ld-linux-aarch64.so.1"
                    f.setExecutable(execLike, false)
                }
            }
        }
        rf.listFiles()?.forEach { d -> if (d.isDirectory) walk(d) }
        // 常见必需：/bin/bash、/usr/bin/env 已在 bin 目录规则里；dpkg 脚本靠 shebang + bash
    }

    private fun writeGuestConfig(rf: File) {
        fun write(path: String, content: String) {
            val f = File(rf, path)
            f.parentFile?.mkdirs()
            f.writeText(content)
        }
        write(
            "etc/resolv.conf",
            "nameserver 223.5.5.5\nnameserver 119.29.29.29\noptions timeout:2 attempts:2\n"
        )
        write("etc/hosts", "127.0.0.1 localhost\n::1 localhost ip6-localhost ip6-loopback\n")
        write("etc/hostname", "localhost\n")
        write("etc/timezone", "Etc/UTC\n")
        // apt 源：清华 ports（http，绕开 rootfs 里没有 CA 证书的鸡生蛋问题）
        write(
            "etc/apt/sources.list",
            "deb [arch=arm64] http://mirrors.tuna.tsinghua.edu.cn/ubuntu-ports/ noble main restricted universe multiverse\n" +
                    "deb [arch=arm64] http://mirrors.tuna.tsinghua.edu.cn/ubuntu-ports/ noble-updates main restricted universe multiverse\n" +
                    "deb [arch=arm64] http://mirrors.tuna.tsinghua.edu.cn/ubuntu-ports/ noble-security main restricted universe multiverse\n"
        )
        // proot 假装 root：apt 的降权 _apt 用户只会制造 setgroups/权限噪音（借鉴 DSH Go）
        write(
            "etc/apt/apt.conf.d/99proot",
            "APT::Sandbox::User \"root\";\n"
        )
    }
}
