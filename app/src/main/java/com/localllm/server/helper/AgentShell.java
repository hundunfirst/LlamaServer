package com.localllm.server.helper;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.view.Display;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;

/**
 * 内置手机助手：以 shell 身份（uid 2000）常驻，替 App 持有一块 Agent 专用虚拟屏。
 *
 * 为什么必须是独立进程：新建一块**公有**虚拟屏要求包名属于调用者 uid，
 * 普通 App 没有这个能力（也拿不到 CAPTURE_VIDEO_OUTPUT）。无线调试以 shell 身份起这个
 * 进程后，宿主包名算 com.android.shell，系统就认了。
 *
 * 这个类只做三件事，其余动作（启动 App、点击、输入）宿主全部用 adb shell 做，接口面越小越好：
 * 1. 建一块与主屏同尺寸、带独立焦点的虚拟屏；
 * 2. 把画面持续导成 PNG，供宿主读取（虚拟屏截不了图：`screencap -d` 只认物理屏）；
 * 3. 看到 stop 文件就收摊退出。
 *
 * 用法（由 App 通过无线调试执行）：
 * CLASSPATH=/data/local/tmp/localllm-agent.dex nohup app_process /system/bin \
 *     com.localllm.server.helper.AgentShell &
 */
public final class AgentShell {

    /** 屏号写到这个文件，宿主读它才知道往哪块屏发命令 */
    private static final String DISPLAY_FILE = "/data/local/tmp/localllm_agent.display";
    /** 最新画面（JPEG）。PNG 一张近 1MB，base64 后经 adb 通道传回太慢；JPEG 对读屏/OCR 足够。
     *  文件名带屏号：多个 helper 同时活着时，共用一个文件会互相覆盖（真机上踩过，
     *  App 读到的是别人那块空屏的画面，OCR 永远读不到字）。 */
    private static String shotFile = "/data/local/tmp/localllm_agent.jpg";
    /** 宿主写这个文件表示要收摊；同样按屏号区分 */
    private static String stopPath = "/data/local/tmp/localllm_agent.stop";
    /**
     * 帧序号：每写一次画面就 +1。
     * 宿主用它判断"点了之后界面到底变没变"——读到旧画面就以为没点上、反复再点，
     * 真机上就是这么打转几十轮的。
     */
    private static String seqPath = "/data/local/tmp/localllm_agent.seq";
    /** 宿主写这个文件下发输入指令（tap/swipe/key），helper 执行后把结果写回 .out */
    private static String cmdPath = "/data/local/tmp/localllm_agent.cmd";
    private static String cmdOutPath = "/data/local/tmp/localllm_agent.out";
    /** 屏幕尺寸，宿主据此换算点击坐标 */
    private static final String SIZE_FILE = "/data/local/tmp/localllm_agent.size";

    private static final Object LOCK = new Object();
    private static byte[] frame;
    private static int stride;
    private static int frameW;
    private static int frameH;
    private static int frameSeq;
    private static int writtenSeq = -1;

    private AgentShell() {
    }

    public static void main(String[] args) {
        System.out.println("agent-shell start uid=" + android.os.Process.myUid());
        try {
            // 单实例：多个 helper 会各建一块屏，输入和焦点全乱（真机上累积过 4-6 个，
            // 表现就是"点了没反应"）。已有实例活着就安静退出。
            if (anotherInstanceRunning()) {
                System.out.println("agent-shell another instance is running, exit");
                System.exit(0);
            }
            writeText(PID_FILE, android.os.Process.myPid() + "\n");
            Looper.prepareMainLooper();
            System.out.println("agent-shell looper ready");
            Context ctx = shellContext();
            System.out.println("agent-shell context ok pkg=" + ctx.getPackageName());
            DisplayManager dm = displayManager(ctx);
            System.out.println("agent-shell display manager ok");

            DisplayMetrics metrics = new DisplayMetrics();
            Display primary = dm.getDisplay(Display.DEFAULT_DISPLAY);
            primary.getRealMetrics(metrics);
            int w = metrics.widthPixels;
            int h = metrics.heightPixels;
            int dpi = metrics.densityDpi;
            writeText(SIZE_FILE, w + "x" + h + "\n");
            System.out.println("agent-shell size=" + w + "x" + h + " dpi=" + dpi);

            ImageReader reader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 3);
            System.out.println("agent-shell reader ok, creating display");
            VirtualDisplay vd = createDisplay(dm, reader, w, h, dpi);
            int id = vd.getDisplay().getDisplayId();
            // 末尾必须有换行：宿主会把几个小文件一起 cat 回来，没有换行会粘成一行
            writeText(DISPLAY_FILE, id + "\n");
            // 有的系统把新建的虚拟屏默认设成 freeform：App 缩成小窗，窗口也不会被正常
            // 挂到这块屏上——没有焦点窗口、无障碍看不见、点击没有接收方。Mika 建屏后
            // 第一件事就是显式设成全屏，我们之前漏了这条。
            try {
                Process p = new ProcessBuilder(
                        "wm", "set-display-windowing-mode", "-d", String.valueOf(id), "1")
                        .redirectErrorStream(true).start();
                p.waitFor();
                System.out.println("agent-shell windowing-mode=fullscreen");
            } catch (Throwable t) {
                System.out.println("windowing mode failed: " + t);
            }
            // 画面与停止信号按屏号分开，避免多个实例互相覆盖
            shotFile = "/data/local/tmp/localllm_agent_" + id + ".jpg";
            stopPath = "/data/local/tmp/localllm_agent_" + id + ".stop";
            seqPath = "/data/local/tmp/localllm_agent_" + id + ".seq";
            cmdPath = "/data/local/tmp/localllm_agent_" + id + ".cmd";
            cmdOutPath = "/data/local/tmp/localllm_agent_" + id + ".out";
            try { new File(stopPath).delete(); } catch (Throwable ignored) { }
            try { new File(seqPath).delete(); } catch (Throwable ignored) { }
            try { new File(cmdPath).delete(); } catch (Throwable ignored) { }
            try { new File(cmdOutPath).delete(); } catch (Throwable ignored) { }
            System.out.println("agent-shell display=" + id);

            HandlerThread ht = new HandlerThread("agent-shell-frames");
            ht.start();
            reader.setOnImageAvailableListener(new ImageReader.OnImageAvailableListener() {
                @Override
                public void onImageAvailable(ImageReader r) {
                    Image img = null;
                    try {
                        img = r.acquireLatestImage();
                        if (img == null) return;
                        ByteBuffer buf = img.getPlanes()[0].getBuffer();
                        byte[] data = new byte[buf.remaining()];
                        buf.get(data);
                        synchronized (LOCK) {
                            frame = data;
                            stride = img.getPlanes()[0].getRowStride();
                            frameW = img.getWidth();
                            frameH = img.getHeight();
                            frameSeq++;
                        }
                    } catch (Throwable t) {
                        System.out.println("frame error: " + t);
                    } finally {
                        if (img != null) img.close();
                    }
                }
            }, new Handler(ht.getLooper()));

            // 常驻：有新画面才落盘（静止时不重复写，省电也省 IO）
            File stop = new File(stopPath);
            long lastStopCheck = 0;
            int pokeTick = 0;
            while (true) {
                Thread.sleep(200);
                // 定期给这块屏报一次"用户活动"：闲置久了系统会让它睡过去，画面冻住、
                // 点击也没有反应（真机上就是"点了没动静"）。每 10 秒一次，代价可以忽略。
                if (++pokeTick % 50 == 0) pokeDisplay(id);
                long now = System.currentTimeMillis();
                if (now - lastStopCheck > 1000) {
                    lastStopCheck = now;
                    if (stop.exists()) {
                        System.out.println("agent-shell stopping on request");
                        break;
                    }
                }
                int seq;
                synchronized (LOCK) {
                    seq = frameSeq;
                }
                if (seq != writtenSeq && seq > 0) {
                    if (savePng()) {
                        writtenSeq = seq;
                        // 记下帧号：宿主据此判断"动作之后画面有没有更新"
                        writeText(seqPath, seq + "\n");
                    }
                }
                // 输入指令：宿主写 <id>.cmd，这里注入后把结果写回 <id>.out。
                // 不用 `adb shell input -d`：实测那条命令在虚拟屏上事件会被丢掉
                // （屏上没有焦点窗口），只有 setDisplayId + injectInputEvent 这条路通。
                File cmd = new File(cmdPath);
                if (cmd.exists()) {
                    String line = readText(cmdPath).trim();
                    try { cmd.delete(); } catch (Throwable ignored) { }
                    if (line.length() > 0) {
                        writeText(cmdOutPath, runInputCommand(line, id) + "\n");
                    }
                }
            }
            runCatchingRelease(vd);
            try { stop.delete(); } catch (Throwable ignored) { }
            try { new File(DISPLAY_FILE).delete(); } catch (Throwable ignored) { }
            System.out.println("agent-shell end");
        } catch (Throwable t) {
            System.out.println("agent-shell fatal: " + t);
            t.printStackTrace();
        }
        System.exit(0);
    }

    /**
     * 可信屏优先：有独立焦点（不抢主屏输入）、不会被当成"没内容"。
     * 有的系统 shell 没有 ADD_TRUSTED_DISPLAY，会被拒，这时退回普通标志——屏照用，
     * 只是在安卓 12/13 上可能短暂影响主屏焦点。
     */
    private static VirtualDisplay createDisplay(DisplayManager dm, ImageReader reader,
                                               int w, int h, int dpi) {
        int base = (1 << 0) | (1 << 3) | (1 << 6) | (1 << 8); // PUBLIC|OWN_CONTENT_ONLY|SUPPORTS_TOUCH|DESTROY_CONTENT_ON_REMOVAL
        int trusted = base | (1 << 10) | (1 << 11) | (1 << 12) | (1 << 14) | (1 << 16);
        try {
            VirtualDisplay vd = dm.createVirtualDisplay("localllm-agent", w, h, dpi, reader.getSurface(), trusted);
            System.out.println("agent-shell flags=0x" + Integer.toHexString(trusted) + " (trusted)");
            return vd;
        } catch (Throwable t) {
            System.out.println("trusted display refused, falling back: " + t);
            VirtualDisplay vd = dm.createVirtualDisplay("localllm-agent", w, h, dpi, reader.getSurface(), base);
            System.out.println("agent-shell flags=0x" + Integer.toHexString(base) + " (plain)");
            return vd;
        }
    }

    private static Context shellContext() throws Exception {
        Class<?> at = Class.forName("android.app.ActivityThread");
        java.lang.reflect.Method systemMain = at.getDeclaredMethod("systemMain");
        systemMain.setAccessible(true);
        Object thread = systemMain.invoke(null);
        Context ctx = (Context) at.getMethod("getSystemContext").invoke(thread);
        // 系统按「包名属于调用者 uid」校验虚拟屏的宿主，shell 进程必须报 com.android.shell
        return ctx.createPackageContext("com.android.shell", 0);
    }

    private static DisplayManager displayManager(Context ctx) throws Exception {
        java.lang.reflect.Constructor<DisplayManager> c =
                DisplayManager.class.getDeclaredConstructor(Context.class);
        c.setAccessible(true);
        return c.newInstance(ctx);
    }

    private static boolean savePng() {
        byte[] data;
        int st, w, h;
        synchronized (LOCK) {
            data = frame;
            st = stride;
            w = frameW;
            h = frameH;
        }
        if (data == null || w <= 0 || h <= 0) return false;
        Bitmap full = null;
        Bitmap crop = null;
        try {
            int strideW = st / 4;
            full = Bitmap.createBitmap(strideW, h, Bitmap.Config.ARGB_8888);
            full.copyPixelsFromBuffer(ByteBuffer.wrap(data));
            crop = Bitmap.createBitmap(full, 0, 0, w, h);
            File tmp = new File(shotFile + ".tmp");
            FileOutputStream fos = new FileOutputStream(tmp);
            crop.compress(Bitmap.CompressFormat.JPEG, 85, fos);
            fos.close();
            // 原子替换：宿主可能正在读上一张
            File dst = new File(shotFile);
            if (dst.exists()) dst.delete();
            return tmp.renameTo(dst);
        } catch (Throwable t) {
            System.out.println("save error: " + t);
            return false;
        } finally {
            if (full != null) full.recycle();
            if (crop != null) crop.recycle();
        }
    }

    private static void writeText(String path, String value) {
        try {
            FileOutputStream fos = new FileOutputStream(path);
            fos.write(value.getBytes());
            fos.close();
        } catch (Throwable t) {
            System.out.println("write error " + path + ": " + t);
        }
    }

    private static void runCatchingRelease(VirtualDisplay vd) {
        try {
            vd.release();
        } catch (Throwable t) {
            System.out.println("release error: " + t);
        }
    }

    // ---- 输入注入 ----

    /**
     * 执行一条输入指令：`tap x y` / `swipe x1 y1 x2 y2 [ms]` / `key BACK`。
     *
     * 为什么不用 `adb shell input -d <屏号>`：实测在虚拟屏上那些事件会被直接丢掉
     * （屏上没有焦点窗口），界面对点击毫无反应，模型就以为没点中、反复重试。
     * 这里走 InputManager.injectInputEvent + setDisplayId，和 Mika 的做法一致。
     */
    private static String runInputCommand(String line, int displayId) {
        try {
            String[] p = line.split("\\s+");
            String cmd = p[0].toLowerCase();
            switch (cmd) {
                case "tap":
                    return injectTap(displayId, Integer.parseInt(p[1]), Integer.parseInt(p[2]))
                            ? "ok" : "inject failed";
                case "longpress":
                    return injectLongPress(
                            displayId, Integer.parseInt(p[1]), Integer.parseInt(p[2]),
                            p.length > 3 ? Integer.parseInt(p[3]) : 800
                    ) ? "ok" : "inject failed";
                case "doubletap":
                    return injectDoubleTap(displayId, Integer.parseInt(p[1]), Integer.parseInt(p[2]))
                            ? "ok" : "inject failed";
                case "swipe":
                    return injectSwipe(
                            displayId, Integer.parseInt(p[1]), Integer.parseInt(p[2]),
                            Integer.parseInt(p[3]), Integer.parseInt(p[4]),
                            p.length > 5 ? Integer.parseInt(p[5]) : 300
                    ) ? "ok" : "inject failed";
                case "key":
                    return injectKey(displayId, p[1]) ? "ok" : "inject failed";
                case "paste":
                    return injectPaste(displayId) ? "ok" : "inject failed";
                case "start":
                    String err = startAppOnDisplay(displayId, p[1]);
                    return err == null ? "ok" : ("error: " + err);
                default:
                    return "unknown cmd " + cmd;
            }
        } catch (Throwable t) {
            return "error: " + t;
        }
    }

    private static boolean injectTap(int displayId, int x, int y) {
        long now = android.os.SystemClock.uptimeMillis();
        android.view.MotionEvent down = android.view.MotionEvent.obtain(
                now, now, android.view.MotionEvent.ACTION_DOWN, x, y, 0);
        android.view.MotionEvent up = android.view.MotionEvent.obtain(
                now, now + 60, android.view.MotionEvent.ACTION_UP, x, y, 0);
        boolean ok = inject(down, displayId);
        return inject(up, displayId) && ok;
    }

    /** 缓存的 IPowerManager，省得每 10 秒都反射找一遍 */
    private static Object powerManager;
    private static java.lang.reflect.Method userActivityMethod;

    /** 给指定屏报一次用户活动，让它保持清醒（Mika 的 DisplayAwake 做的事） */
    private static void pokeDisplay(int displayId) {
        try {
            if (powerManager == null) {
                Object binder = Class.forName("android.os.ServiceManager")
                        .getMethod("getService", String.class).invoke(null, "power");
                powerManager = Class.forName("android.os.IPowerManager$Stub")
                        .getMethod("asInterface", android.os.IBinder.class).invoke(null, binder);
                userActivityMethod = powerManager.getClass().getMethod(
                        "userActivity", int.class, long.class, int.class, int.class);
            }
            // USER_ACTIVITY_EVENT_TOUCH = 2
            userActivityMethod.invoke(
                    powerManager, displayId, android.os.SystemClock.uptimeMillis(), 2, 0);
        } catch (Throwable ignored) {
            // 拿不到就算了：屏照常用，只是没人替它续亮
        }
    }

    /** 注入 Ctrl+V：中文没法用 `input text` 打进输入框，只能把文字放剪贴板再粘贴 */
    private static boolean injectPaste(int displayId) {
        long now = android.os.SystemClock.uptimeMillis();
        int meta = android.view.KeyEvent.META_CTRL_ON | android.view.KeyEvent.META_CTRL_LEFT_ON;
        android.view.KeyEvent down = new android.view.KeyEvent(
                now, now, android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_V, 0, meta);
        android.view.KeyEvent up = new android.view.KeyEvent(
                now, now + 40, android.view.KeyEvent.ACTION_UP, android.view.KeyEvent.KEYCODE_V, 0, meta);
        boolean ok = inject(down, displayId);
        return inject(up, displayId) && ok;
    }

    /** 长按：按下后按住不动，到时间再抬。很多操作（删除、呼出菜单、拖拽）只认这个 */
    private static boolean injectLongPress(int displayId, int x, int y, int holdMs) {
        long now = android.os.SystemClock.uptimeMillis();
        int hold = Math.max(300, Math.min(holdMs, 5000));
        android.view.MotionEvent down = android.view.MotionEvent.obtain(
                now, now, android.view.MotionEvent.ACTION_DOWN, x, y, 0);
        if (!inject(down, displayId)) return false;
        android.os.SystemClock.sleep(hold);
        android.view.MotionEvent up = android.view.MotionEvent.obtain(
                now, now + hold, android.view.MotionEvent.ACTION_UP, x, y, 0);
        return inject(up, displayId);
    }

    /** 双击：两下之间隔 80 毫秒（系统自己判断双击的上限通常是 300 毫秒） */
    private static boolean injectDoubleTap(int displayId, int x, int y) {
        boolean first = injectTap(displayId, x, y);
        android.os.SystemClock.sleep(80);
        boolean second = injectTap(displayId, x, y);
        return first && second;
    }

    private static boolean injectSwipe(int displayId, int x1, int y1, int x2, int y2, int ms) {        long now = android.os.SystemClock.uptimeMillis();
        inject(android.view.MotionEvent.obtain(
                now, now, android.view.MotionEvent.ACTION_DOWN, x1, y1, 0), displayId);
        int steps = 12;
        for (int i = 1; i <= steps; i++) {
            long t = now + (long) ms * i / steps;
            float x = x1 + (x2 - x1) * i / (float) steps;
            float y = y1 + (y2 - y1) * i / (float) steps;
            inject(android.view.MotionEvent.obtain(
                    now, t, android.view.MotionEvent.ACTION_MOVE, x, y, 0), displayId);
        }
        return inject(android.view.MotionEvent.obtain(
                now, now + ms, android.view.MotionEvent.ACTION_UP, x2, y2, 0), displayId);
    }

    private static boolean injectKey(int displayId, String name) {
        int code = keyCode(name);
        if (code <= 0) return false;
        long now = android.os.SystemClock.uptimeMillis();
        android.view.KeyEvent down = new android.view.KeyEvent(
                now, now, android.view.KeyEvent.ACTION_DOWN, code, 0);
        android.view.KeyEvent up = new android.view.KeyEvent(
                now, now + 40, android.view.KeyEvent.ACTION_UP, code, 0);
        boolean ok = inject(down, displayId);
        return inject(up, displayId) && ok;
    }

    private static int keyCode(String name) {
        String n = name.toUpperCase().replace("KEYCODE_", "");
        switch (n) {
            case "BACK": return android.view.KeyEvent.KEYCODE_BACK;
            case "ENTER": return android.view.KeyEvent.KEYCODE_ENTER;
            case "DEL":
            case "DELETE": return android.view.KeyEvent.KEYCODE_DEL;
            case "TAB": return android.view.KeyEvent.KEYCODE_TAB;
            default:
                try { return Integer.parseInt(name); } catch (Throwable t) { return -1; }
        }
    }

    private static boolean inject(android.view.InputEvent e, int displayId) {
        try {
            // InputEvent.setDisplayId 是隐藏 API，只能反射
            e.getClass().getMethod("setDisplayId", int.class).invoke(e, displayId);
            Object im = inputManager();
            java.lang.reflect.Method m = im.getClass()
                    .getMethod("injectInputEvent", android.view.InputEvent.class, int.class);
            return Boolean.TRUE.equals(m.invoke(im, e, 0));
        } catch (Throwable t) {
            System.out.println("inject error: " + t);
            return false;
        }
    }

    private static Object inputManager() throws Exception {
        try {
            Class<?> g = Class.forName("android.hardware.input.InputManagerGlobal");
            return g.getMethod("getInstance").invoke(null);
        } catch (Throwable t) {
            Class<?> c = Class.forName("android.hardware.input.InputManager");
            return c.getMethod("getInstance").invoke(null);
        }
    }

    private static String readText(String path) {
        try {
            java.io.FileInputStream in = new java.io.FileInputStream(path);
            byte[] buf = new byte[Math.max(1, in.available())];
            int n = in.read(buf);
            in.close();
            return n > 0 ? new String(buf, 0, n) : "";
        } catch (Throwable t) {
            return "";
        }
    }

    /** 记录本进程 pid，用来做单实例判断 */
    private static final String PID_FILE = "/data/local/tmp/localllm_agent.pid";

    /**
     * 用系统正式接口把 App 启动到指定屏：`IActivityTaskManager.startActivityAsUser` +
     * `ActivityOptions.setLaunchDisplayId`。
     *
     * 为什么不用 `am start --display`：那条路遇到"App 已在别处运行"会把 intent 交给现存实例
     * （真机上提示 "delivered to currently running top-most instance"），新屏上其实什么都没开；
     * 而且用 am 起来的窗口不会被正常挂到那块屏上（无焦点窗口、无障碍也看不到），
     * 于是读屏是空的、点击也没有接收方。Mika 用的就是这里这条正式接口。
     *
     * 返回 null 表示成功，否则是失败原因。
     */
    private static String startAppOnDisplay(int displayId, String packageName) {
        try {
            android.content.Context ctx = shellContext();
            android.content.Intent intent = ctx.getPackageManager().getLaunchIntentForPackage(packageName);
            if (intent == null) return "找不到 " + packageName + " 的启动入口";
            intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
            android.app.ActivityOptions options = android.app.ActivityOptions.makeBasic();
            options.setLaunchDisplayId(displayId);
            // 安卓 14 起，从后台起页面要显式声明允许（Mika 的注释里也点了这一条）；
            // 不声明会被系统以 START_BACKGROUND_ACTIVITY_NOT_ALLOWED(-94) 拒掉
            try {
                options.getClass()
                        .getMethod("setPendingIntentBackgroundActivityStartMode", int.class)
                        .invoke(options, 1 /* MODE_BACKGROUND_ACTIVITY_START_ALLOWED */);
            } catch (Throwable ignored) { }
            try {
                options.getClass()
                        .getMethod("setAllowBackgroundActivityStarts", boolean.class)
                        .invoke(options, true);
            } catch (Throwable ignored) { }

            Object atm = Class.forName("android.app.ActivityTaskManager")
                    .getMethod("getService").invoke(null);
            Object appThread = Class.forName("android.app.ActivityThread")
                    .getMethod("currentActivityThread").invoke(null);
            Object threadBinder = appThread.getClass()
                    .getMethod("getApplicationThread").invoke(appThread);

            for (java.lang.reflect.Method m : atm.getClass().getMethods()) {
                if (!m.getName().equals("startActivityAsUser")) continue;
                Class<?>[] pt = m.getParameterTypes();
                if (pt.length != 12) continue;
                Object result = m.invoke(
                        atm, threadBinder, "com.android.shell", null, intent, null, null, null,
                        0, 0, null, options.toBundle(), -2 /* USER_CURRENT */);
                int code = (result instanceof Integer) ? (Integer) result : 0;
                if (code >= 0) {
                    System.out.println("agent-shell started " + packageName + " on display " + displayId);
                    return null;
                }
                return "startActivityAsUser 返回 " + code;
            }
            return "没有找到 startActivityAsUser(12 参数)";
        } catch (Throwable t) {
            return "startActivityAsUser 失败：" + t;
        }
    }

    /** 已有一个 AgentShell 在跑？读 /proc 判断，避免误杀或重启时建第二块屏 */
    private static boolean anotherInstanceRunning() {
        try {
            String text = readText(PID_FILE).trim();
            if (text.isEmpty()) return false;
            int pid = Integer.parseInt(text);
            if (pid == android.os.Process.myPid()) return false;
            File cmdline = new File("/proc/" + pid + "/cmdline");
            if (!cmdline.exists()) return false;
            java.io.BufferedReader r = new java.io.BufferedReader(new java.io.FileReader(cmdline));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null) sb.append(line);
            r.close();
            return sb.toString().contains("AgentShell");
        } catch (Throwable t) {
            return false;
        }
    }
}
