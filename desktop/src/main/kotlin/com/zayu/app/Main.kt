package com.zayu.app

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.window.WindowDraggableArea
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.WindowState
import androidx.compose.ui.window.application
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.zayu.app.data.network.XunleiDeviceFingerprint
import com.zayu.app.data.network.video.HeadlessWebView
import com.zayu.app.ui.MainScreen
import com.zayu.app.ui.clipboard.ClipboardLinkController
import com.zayu.app.ui.jcef.JcefHolder
import com.zayu.app.ui.theme.ComposeEmptyActivityTheme
import com.zayu.app.util.DarkMode
import com.zayu.app.util.DesktopActions
import com.zayu.app.util.GlassWindowFx
import com.zayu.app.util.RenderApiChooser
import com.zayu.app.util.SingleInstance
import com.zayu.app.util.TrayManager
import com.zayu.app.util.Win32PopupMenu
import com.zayu.app.util.WindowFx
import androidx.compose.ui.graphics.toComposeImageBitmap

// WindowExceptionHandler 在 Compose 1.7 仍标注为实验 API；本项目锁 1.7.3，用它是为了
// 关掉「退出时弹原生 Error 对话框」这个默认行为（见 application{} 里的 windowErrorHandler）
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
fun main(args: Array<String>) {
    // 渲染后端：默认 OPENGL（GPU 渲染，动画流畅且文字清晰，2026-09-05 用户实测确认）。
    // 实测数据（125% DPI）：SOFTWARE 每帧 CPU 光栅化约 130 万像素，设置/下载页持续交互只有
    // ~10 FPS 且 UI 线程饱和；DIRECT3D 动画流畅但文字灰度发虚且 hinting 调节无效；
    // OPENGL（ANGLE）两者兼顾。如需其他后端可设 ZAYUPC_RENDER_API=SOFTWARE / DIRECT3D。
    val osName = System.getProperty("os.name", "").lowercase()

    // 桌面上下文初始化（数据目录等）
    AppContext.init()

    // ★ 全局兜底：任何线程的未捕获异常都落盘。
    //   否则 GUI 进程一旦抛异常就是「静默消失」——启动器只把堆栈打到 stderr（双击启动时没人看得到），
    //   用户看到的只有「应用启动后自己退出了」，无法排查。
    Thread.setDefaultUncaughtExceptionHandler { thread, error ->
        runCatching { com.zayu.app.util.Log.e("Zayu-Crash", "未捕获异常（线程 ${thread.name}）", error) }
        runCatching {
            com.zayu.app.util.DiagnosticLog.error(
                com.zayu.app.util.DiagnosticLog.OPERATION,
                "uncaught_exception",
                summary = "thread=${thread.name} ${error.javaClass.name}: ${error.message}"
            )
            com.zayu.app.util.DiagnosticLog.flush(1000)
        }
    }

    // 单实例：第二个实例只把已有窗口还原到前台，然后自身退出。
    // ★ 必须做：两个实例共用同一数据目录时，CEF 的用户数据目录（cache\jcef）会撞车 ——
    //   Chromium 是单例进程模型，第二个实例抢不到 profile 锁会走它自己的「已有实例」处理，
    //   实测会把第一个实例的渲染 / 网络子进程杀掉（jcef.log：Tab Killed / Network service crashed），
    //   个别机器上还会被 Chromium 反向拉起系统里注册的浏览器。
    if (!SingleInstance.acquire()) {
        SingleInstance.focusExistingWindow()
        return
    }

    // ★ 老旧 / 基础显示适配器（如 ATI Radeon 3000）上，OPENGL(ANGLE) 会出现「界面有形状、
    //   但一个文字都不渲染」，这类设备由 [RenderApiChooser] 自动退到 SOFTWARE。
    //   必须在创建任何窗口 / Skiko 初始化之前设置；环境变量 ZAYUPC_RENDER_API 优先级更高。
    var softwareRendering = false
    if (osName.contains("win")) {
        val renderApi = System.getenv("ZAYUPC_RENDER_API")?.takeIf { it.isNotBlank() }
            ?: RenderApiChooser.chooseWindows()
        com.zayu.app.util.Log.i("Zayu-Render", "渲染后端：$renderApi")
        System.setProperty("skiko.renderApi", renderApi)
        softwareRendering = renderApi.equals("SOFTWARE", true)
    }
    // 动效降级：软件渲染时每帧 CPU 光栅化，位移动画/列表增删只会更卡（§3.7）
    runCatching { com.zayu.app.ui.theme.MotionPolicy.install(softwareRendering) }
    // 诊断日志：读开关（默认关）并定好落盘目录；开着才起写线程，关着这里几乎零开销
    runCatching { com.zayu.app.util.DiagnosticLog.install() }
    // 上次若被原生层硬崩带走（事件查看器里的 0xc0000005 访问违规等），Java 侧一行都写不出来，
    // 只有 JVM 自己落的 hs_err 有现场：搬进数据目录并记一条 WARN，随诊断包一并带走
    runCatching { com.zayu.app.util.DiagnosticLog.harvestJvmCrashLogs() }
    // 下载调试：读开关（默认关）。必须在任何下载开始之前装配，否则开关打开后先建的任务收不到事件
    runCatching { com.zayu.app.data.download.DownloadDebugLog.install() }
    // HTTP 代理装配：按用户设置的三选一模式注入全局 OkHttp 客户端。
    // 必须在 application { } 之前、AppContext.init() 之后执行（此时 Preferences 已可用，
    // 且早于任何网络请求发出，代理即时对 API / 下载 / 更新检查全部生效）。
    // 注意：system 模式只在启动（及设置变更）时读一次系统代理，运行中系统代理变化不自动跟随。
    runCatching {
        val settings = com.zayu.app.data.prefs.SettingsRepository()
        when (settings.proxyMode) {
            com.zayu.app.data.prefs.SettingsRepository.PROXY_MODE_MANUAL -> {
                if (settings.proxyHost.isNotBlank()) {
                    com.zayu.app.data.network.HttpClients.setProxy(settings.proxyHost, settings.proxyPort)
                } else {
                    com.zayu.app.util.Log.w("Zayu-Proxy", "手动代理模式未填写主机地址，按直连处理")
                    com.zayu.app.data.network.HttpClients.setProxy(null, 0)
                }
            }
            com.zayu.app.data.prefs.SettingsRepository.PROXY_MODE_SYSTEM -> {
                // 解析系统代理；解析不到（未启用 / PAC / 读取失败）时按直连处理（SystemProxy 内部已记日志）
                when (val info = com.zayu.app.data.network.SystemProxy.inspect()) {
                    is com.zayu.app.data.network.SystemProxy.Inspect.Proxy ->
                        com.zayu.app.data.network.HttpClients.setProxy(info.host, info.port)
                    else -> com.zayu.app.data.network.HttpClients.setProxy(null, 0)
                }
            }
            else -> com.zayu.app.data.network.HttpClients.setProxy(null, 0)
        }
        // HTTP/2 开关：默认关闭（仅 HTTP/1.1），开启后允许 ALPN 协商 h2。
        // 与代理同一时机装配，早于任何网络请求发出，启动即生效。
        com.zayu.app.data.network.HttpClients.setHttp2Enabled(settings.http2Enabled)
        // 下载引擎调优参数（读缓冲大小 / 慢连接抢占开关、阈值、判定时长）：
        // 与代理同一时机装配，读取「设置 → 实验性功能」的持久化值，下载任务开始前即生效。
        com.zayu.app.data.download.DownloadTuning.applyFrom(settings)
    }
    // 原生暗色模式：让 Win32 原生菜单（托盘右键）跟随系统暗色。必须在创建任何窗口前调用。
    DarkMode.enable()
    // 迅雷设备指纹（进程启动时初始化一次，等价原 Application.onCreate）
    XunleiDeviceFingerprint.init()

    // 诊断模式：--jcef-smoke [url]，创建内嵌浏览器加载页面并输出 Cookie 统计后退出
    if (args.contains("--jcef-smoke")) {
        val url = args.firstOrNull { it.startsWith("http") } ?: "https://pan.quark.cn"
        runJcefSmoke(url)
        return
    }

    // 启动优化：JCEF（内嵌 Chromium）初始化耗时数秒、首次运行解包 200MB 更久，
    // 若在主线程同步初始化会阻塞窗口显示。Windows/Linux 下改为窗口显示后后台初始化
    // （CEF 消息循环由 CefApp 内部 Swing Timer 在 AWT EDT 泵动，与初始化线程无关）。
    // macOS 受 AppKit 主线程约束，保持阻塞式初始化。
    val isMac = System.getProperty("os.name", "").lowercase().contains("mac")
    if (isMac) {
        JcefHolder.initBlocking()
    }

    // 进程退出前：先把 WAL 落回主库（否则数据只留在 -wal 里，异常退出后可能整库丢失），
    // 再尽力释放 JCEF
    Runtime.getRuntime().addShutdownHook(
        Thread {
            // 正常退出标记：下次日志里如果「有 diagnostic_session_start 却没有 session_end」，
            // 说明上次是被原生层直接带走的（Java 侧来不及写任何东西）
            runCatching { com.zayu.app.util.DiagnosticLog.sessionEnd() }
            com.zayu.app.data.bridge.BrowserBridgeServer.stop()
            com.zayu.app.data.db.AppDatabase.checkpointOnExit()
            // ★ CEF 的约定：所有 browser 关闭之后才能 CefShutdown。过去直接 dispose CefApp，
            //   等于在隐藏浏览器还活着时强关，GPU / renderer 子进程会被硬杀 ——
            //   Windows 上就表现为「退出瞬间闪两个崩溃报告框」
            runCatching { HeadlessWebView.closeQuietly() }
            JcefHolder.disposeQuietly()
        }
    )

    // 浏览器插件桥（本地回环 HTTP，供 MV3 扩展嗅探/劫持下载后与云析通信）：
    // 只监听 127.0.0.1，扩展侧按端口区间探测 /ping；端口全被占用时静默跳过（不影响主功能）。
    runCatching { com.zayu.app.data.bridge.BrowserBridgeServer.start() }
        .onFailure { com.zayu.app.util.Log.w("Zayu-Bridge", "启动浏览器插件桥失败：${it.message}") }

    application {
        val scope = rememberCoroutineScope()
        // 关闭时先淡出窗口再退出，消除原生窗口销毁瞬间的白屏闪烁
        // 必须是 Compose State：SideEffect 里赋值后要触发重组，
        // 否则 TitleBarContent/ResizeHandles 捕获的永远是 null（最小化/最大化静默失效）
        var mainWindow by remember { mutableStateOf<java.awt.Frame?>(null) }
        // 关闭请求信号：点击自绘 X 时置 true，由 MainScreen 决定退出 / 托盘 / 询问
        var closeRequested by remember { mutableStateOf(false) }
        // 抑制关闭请求：程序化隐藏窗口（最小化到托盘）时为 true，避免 onCloseRequest 重跑导致弹窗闪烁
        var suppressCloseRequest by remember { mutableStateOf(false) }
        // 最大化状态：自绘最大化/还原图标需要（由 ComponentListener 维护）
        var maximized by remember { mutableStateOf(false) }
        // 启动器闪屏模式（launcher 注入 ZAYUPC_SPLASH=1）：窗口可见性由 Compose 控制，
        // 与闪屏淡出交叉衔接（液态玻璃窗口逐像素透明，整窗 opacity 渐变不可用，
        // 由闪屏直接淡出完成过渡，这里不再做二次淡入）。
        val splashMode = System.getenv("ZAYUPC_SPLASH") == "1"

        // ★ Compose Desktop 默认把「窗口 / 场景」异常弹成原生 Error 对话框。
        //   最容易撞上的是关闭时的良性竞态（例如切完主题就退出时报
        //   "size set after ComposeScene is closed"）—— 进程其实已经正常退出，用户却看到一个
        //   莫名其妙的错误框。这里改成只写日志（进主日志，随诊断包一起导出），不再打扰用户。
        val windowErrorHandler = remember {
            object : androidx.compose.ui.window.WindowExceptionHandler {
                override fun onException(exception: Throwable) {
                    com.zayu.app.util.Log.e(
                        "Zayu-Window",
                        "窗口异常（未弹窗）：${exception.message}",
                        exception
                    )
                    runCatching {
                        com.zayu.app.util.DiagnosticLog.error(
                            com.zayu.app.util.DiagnosticLog.OPERATION,
                            "window_exception",
                            summary = exception.message ?: exception.javaClass.simpleName
                        )
                    }
                }
            }
        }

        // 窗口状态提升为可共享状态：视频预览的全屏开关（PreviewFullscreen）需要它
        // 启动位置：按「设置 → 主题与外观 → 窗口」的偏好决定；默认「上次关闭位置」——
        // 该项用平台默认位置起步，随后在窗口内容里按上次记录的坐标还原（见 restoreLastWindowPosition）。
        val startupPosition = remember { com.zayu.app.data.prefs.SettingsRepository().windowStartupPosition }
        val windowState = remember {
            WindowState(
                size = DpSize(1100.dp, 760.dp),
                position = when (startupPosition) {
                    // 桌面为 LTR：Start=左、End=右
                    com.zayu.app.data.prefs.SettingsRepository.WINDOW_POS_TOP_LEFT -> WindowPosition(Alignment.TopStart)
                    com.zayu.app.data.prefs.SettingsRepository.WINDOW_POS_BOTTOM_LEFT -> WindowPosition(Alignment.BottomStart)
                    com.zayu.app.data.prefs.SettingsRepository.WINDOW_POS_CENTER -> WindowPosition(Alignment.Center)
                    com.zayu.app.data.prefs.SettingsRepository.WINDOW_POS_TOP_RIGHT -> WindowPosition(Alignment.TopEnd)
                    com.zayu.app.data.prefs.SettingsRepository.WINDOW_POS_BOTTOM_RIGHT -> WindowPosition(Alignment.BottomEnd)
                    else -> WindowPosition.PlatformDefault
                }
            )
        }

        // ★ 真正生效的一层：Compose 1.7 把「窗口异常处理器」放在 internal API 后面
        //   （WindowExceptionHandlerFactory / LocalWindowExceptionHandlerFactory），
        //   只能反射注入（见文件末尾 windowExceptionHandlerLocal / windowExceptionFactory）。
        //   只设 ComposeWindow.exceptionHandler 是不够的：每次重组（例如切主题）Compose 都会用
        //   CompositionLocal 里的默认工厂把它覆盖回「弹原生 Error 对话框」。
        val exceptionLocal = remember { windowExceptionHandlerLocal() }
        val providedValues = remember(windowErrorHandler, exceptionLocal) {
            val factory = windowExceptionFactory(windowErrorHandler)
            if (exceptionLocal != null && factory != null) {
                arrayOf<androidx.compose.runtime.ProvidedValue<*>>(exceptionLocal.provides(factory))
            } else {
                emptyArray()
            }
        }
        androidx.compose.runtime.CompositionLocalProvider(*providedValues) {
        Window(
            onCloseRequest = {
                if (!suppressCloseRequest) closeRequested = true
            },
            title = "杂鱼Max 桌面端",
            // 窗口尺寸/位置由上游的 windowState 记忆（下次启动还原上次窗口）
            state = windowState,
            icon = remember { loadWindowIcon() },
            // 液态玻璃：去原生边框。
            // ★ transparent 必须为 true —— DWM 亚克力（GlassWindowFx.apply）要求客户区能透出 alpha，
            //   窗口一旦不透明，DwmExtendFrameIntoClientArea 打出来的玻璃面就全被盖住，玻璃彻底消失。
            //   上一次为了"顶部窗框不要透明"把它改成 false，直接导致桌面端没有液态玻璃。
            //   正确解法：窗口保持透明（玻璃链路通），由**标题栏自己铺不透明底**解决"透出背后窗口"——
            //   见下方 Column 顶部 WindowDraggableArea 的 background。
            undecorated = true,
            transparent = true,
            resizable = true,
        ) {
            // 最大化边界：无边框窗口最大化默认盖住任务栏，用工作区约束
            DisposableEffect(window) {
                val frame = window as? java.awt.Frame
                val listener = object : java.awt.event.ComponentAdapter() {
                    override fun componentResized(e: java.awt.event.ComponentEvent?) {
                        maximized = frame?.let {
                            (it.extendedState and java.awt.Frame.MAXIMIZED_BOTH) != 0
                        } ?: false
                    }
                }
                frame?.let {
                    runCatching {
                        it.maximizedBounds =
                            java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment().maximumWindowBounds
                    }
                    it.addComponentListener(listener)
                    maximized = (it.extendedState and java.awt.Frame.MAXIMIZED_BOTH) != 0
                }
                onDispose { frame?.removeComponentListener(listener) }
            }
            androidx.compose.runtime.SideEffect {
                mainWindow = window as? java.awt.Frame
                // 接管窗口异常：默认实现会弹原生「Error」对话框（退出期的良性竞态也会弹）
                runCatching { window.exceptionHandler = windowErrorHandler }
                com.zayu.app.ui.components.PreviewFullscreen.windowState = windowState
                // 退出全屏时恢复窗口矩形要用真实窗口（只靠 WindowState 不可靠）
                com.zayu.app.ui.components.PreviewFullscreen.window = window
                // 剪贴板分享链接弹窗：主窗口引用用于「主窗口失焦时才弹」判断 + 「打开」时切回前台
                ClipboardLinkController.mainWindow = mainWindow
            }
            // 「上次关闭位置」/「自定义」还原：用 AWT 屏幕像素坐标，避开 WindowState 的 dp/density 换算。
            // 只在启动时执行一次；记录的坐标不可见（如副屏被拔掉）时不动，交给平台默认位置。
            androidx.compose.runtime.LaunchedEffect(Unit) {
                when (startupPosition) {
                    com.zayu.app.data.prefs.SettingsRepository.WINDOW_POS_LAST ->
                        restoreWindowPosition(window as? java.awt.Frame, com.zayu.app.data.prefs.SettingsRepository().windowLastPosition)
                    com.zayu.app.data.prefs.SettingsRepository.WINDOW_POS_CUSTOM ->
                        restoreWindowPosition(window as? java.awt.Frame, com.zayu.app.data.prefs.SettingsRepository().windowCustomPosition)
                }
            }
            // 窗口出现后再后台启动 JCEF，登录页通过 browserReady 状态自动切换
            androidx.compose.runtime.LaunchedEffect(Unit) {
                JcefHolder.initInBackground()
                // 预热抖音解析用的隐藏浏览器：实测单次解析耗时几乎全在 Chromium
                // 冷启动与 acrawler 挑战上（诊断日志 1.8~9.4s），提前把浏览器与站点
                // Cookie 热起来，用户真正粘贴链接时即可省掉这段冷启动。
                HeadlessWebView.prewarm("https://www.douyin.com/")
            }
            // 液态玻璃：启用 DWM 系统背板（Win11 亚克力 / Win10 blur-behind 回落）。
            // 窗口 transparent = true 是前提，否则背板被不透明客户区盖住。
            // 失败静默降级为普通半透明，功能不受影响。
            androidx.compose.runtime.LaunchedEffect(Unit) {
                com.zayu.app.util.GlassWindowFx.apply(window)
            }
            // 系统托盘：JNA 调 Win32 TrackPopupMenu（原生菜单，中文正常，暗色跟随系统）
            androidx.compose.runtime.LaunchedEffect(Unit) {
                TrayManager.loadTrayIcon()?.let { icon ->
                    TrayManager.install(
                        image = icon,
                        onLeftClick = { DesktopActions.bringWindowToFront(mainWindow) },
                        onRightClick = { x, y ->
                            val cmd = Win32PopupMenu.show(
                                items = listOf(
                                    "显示主窗口" to 1,
                                    "-" to 0,
                                    "退出" to 2
                                ),
                                x = x, y = y
                            )
                            when (cmd) {
                                1 -> DesktopActions.bringWindowToFront(mainWindow)
                                2 -> {
                                    saveWindowPosition(mainWindow)
                                    val w = mainWindow
                                    if (w != null) WindowFx.fadeOutThen(w) { exitApplication() }
                                    else exitApplication()
                                }
                            }
                        }
                    )
                }
            }
            ComposeEmptyActivityTheme {
                // ★ 根部**不能**铺不透明背景：铺了就盖死 DWM 亚克力，玻璃就没了。
                //   用户要求的"窗框不要透明"由下面标题栏自己的 background 解决，不是靠整窗不透明。
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        // 半透明主题色垫层：让内容在玻璃上有足够对比度，又不至于完全盖住背景。
                        // 0.55 是实测折中值 —— 更低会看清桌面图标（吵），更高玻璃就看不出来了。
                        .background(androidx.compose.material3.MaterialTheme.colorScheme.background.copy(alpha = 0.55f))
                ) {
                    Column(modifier = Modifier.fillMaxSize()) {
                        // ---- 自绘标题栏：拖拽移动 / 双击最大化 / 最小化·最大化·关闭 ----
                        // WindowDraggableArea 是 WindowScope 扩展，必须在 Window 内容 lambda 里调用
                        WindowDraggableArea(
                            // 标题栏单独铺不透明底：这里是"窗框"的位置，透出背后窗口会像一条异色横带，
                            // 所以它必须是实色；内容区才交给 DWM 玻璃。
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(38.dp)
                                .background(androidx.compose.material3.MaterialTheme.colorScheme.background)
                        ) {
                            TitleBarContent(
                                frame = mainWindow,
                                icon = loadWindowIcon(),
                                maximized = maximized,
                                onToggleMax = { toggleMaximized(mainWindow) },
                                onClose = { closeRequested = true }
                            )
                        }
                        MainScreen(
                            closeRequested = closeRequested,
                            onCloseHandled = { closeRequested = false },
                            onExitApplication = {
                                saveWindowPosition(mainWindow)
                                val w = mainWindow
                                if (w != null) WindowFx.fadeOutThen(w) { exitApplication() }
                                else exitApplication()
                            },
                            onMinimizeToTray = {
                                // 记录当前窗口位置，再隐藏（下次启动「上次关闭位置」可还原）
                                saveWindowPosition(mainWindow)
                                // 隐藏窗口前先抑制关闭请求，避免 Compose 把 hide 当作 close 重跑逻辑
                                suppressCloseRequest = true
                                mainWindow?.isVisible = false
                                // 延迟解除抑制（确保 hide 事件已消费完毕）
                                scope.launch {
                                    kotlinx.coroutines.delay(50)
                                    suppressCloseRequest = false
                                }
                            }
                        )
                    }
                    // ---- 边缘缩放手柄（未最大化时才有意义） ----
                    if (!maximized) {
                        ResizeHandles(frame = mainWindow)
                    }
                }
            }
        }

        // 浏览器插件劫持下载的询问弹窗：独立无边框置顶窗口。
        // 与主窗口平级挂载——主窗口被最小化到托盘 / 被浏览器遮住时也能单独弹出，
        // 不会把整个主界面一起吊起来（区别于窗口内覆盖层的 FadeAlertDialog）。
        com.zayu.app.ui.components.BrowserHookDialogWindow()
        }
    }
}

/** 最大化 / 还原切换 */
private fun toggleMaximized(frame: java.awt.Frame?) {
    if (frame == null) return
    frame.extendedState = if ((frame.extendedState and java.awt.Frame.MAXIMIZED_BOTH) != 0) {
        java.awt.Frame.NORMAL
    } else {
        java.awt.Frame.MAXIMIZED_BOTH
    }
}

/** 自绘标题栏内容：左图标标题（外层已套拖拽区）+ 右侧三枚窗口控制钮 */
@androidx.compose.runtime.Composable
private fun TitleBarContent(
    frame: java.awt.Frame?,
    icon: androidx.compose.ui.graphics.painter.Painter?,
    maximized: Boolean,
    onToggleMax: () -> Unit,
    onClose: () -> Unit
) {
    Row(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    // 双击标题栏 = 最大化 / 还原
                    detectTapGestures(onDoubleTap = { onToggleMax() })
                },
            verticalAlignment = Alignment.CenterVertically
        ) {
            Spacer(Modifier.width(14.dp))
            if (icon != null) {
                androidx.compose.foundation.Image(
                    painter = icon,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(8.dp))
            }
            Text(
                text = "杂鱼Max 桌面端",
                style = androidx.compose.material3.MaterialTheme.typography.labelLarge,
                color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.weight(1f))
            ChromeButton("最小化", onClick = {
                frame?.state = java.awt.Frame.ICONIFIED
            }, glyph = { MinimizeGlyph(it) })
            ChromeButton(if (maximized) "还原" else "最大化", onClick = { onToggleMax() },
                glyph = { if (maximized) RestoreGlyph(it) else MaximizeGlyph(it) })
            ChromeButton("关闭", danger = true, onClick = { onClose() },
                glyph = { CloseGlyph(it) })
        }
}

/**
 * 窗口控制钮图标：对齐 Win11 / Chrome 的规格 —— 10×10 画布、1px 细线、无圆角描边。
 * 标题栏按钮是最容易被看出「自制感」的地方，所以尺寸/线宽/留白都按系统原生值来，
 * 不用 material 图标（Close 那种 24dp 实心叉太粗，最小化/最大化核心库里又没有）。
 */
private val GlyphSize = 10.dp
private val GlyphStroke = 1.dp

@androidx.compose.runtime.Composable
private fun MinimizeGlyph(tint: Color) {
    androidx.compose.foundation.Canvas(Modifier.size(GlyphSize)) {
        val y = size.height / 2f
        drawLine(tint, offset(0f, y), offset(size.width, y), GlyphStroke.toPx())
    }
}

@androidx.compose.runtime.Composable
private fun MaximizeGlyph(tint: Color) {
    androidx.compose.foundation.Canvas(Modifier.size(GlyphSize)) {
        val s = GlyphStroke.toPx() / 2f   // 描边居中，内缩半像素避免糊边
        drawRect(
            tint,
            topLeft = offset(s, s),
            size = Size(size.width - s * 2, size.height - s * 2),
            style = Stroke(GlyphStroke.toPx())
        )
    }
}

@androidx.compose.runtime.Composable
private fun RestoreGlyph(tint: Color) {
    androidx.compose.foundation.Canvas(Modifier.size(GlyphSize)) {
        val st = GlyphStroke.toPx()
        val half = st / 2f
        val box = size.width * 0.72f          // 后框略小一点，两框叠出「还原」层次
        // 后框（右上）：只画可见的两条边 + 短角，避免和前景框线糊成一团
        val backL = size.width - box
        drawLine(tint, offset(backL, half), offset(size.width - half, half), st)
        drawLine(tint, offset(size.width - half, half), offset(size.width - half, size.height - backL), st)
        // 前框（左下）：完整方框，压在后框上面
        drawRect(
            tint,
            topLeft = offset(half, backL),
            size = Size(box - st, box - st),
            style = Stroke(st)
        )
    }
}

@androidx.compose.runtime.Composable
private fun CloseGlyph(tint: Color) {
    androidx.compose.foundation.Canvas(Modifier.size(GlyphSize)) {
        val st = GlyphStroke.toPx()
        drawLine(tint, offset(0f, 0f), offset(size.width, size.height), st)
        drawLine(tint, offset(size.width, 0f), offset(0f, size.height), st)
    }
}

private fun offset(x: Float, y: Float) = androidx.compose.ui.geometry.Offset(x, y)

/**
 * 窗口控制钮：Win11 规格 —— 46×32 矩形热区（无圆角无圆底），
 * hover 铺一层浅灰（深色主题下为浅白 8%），关闭钮 hover 变系统红 #C42B1C 且图标转白。
 * 圆底 + 小图标是「自制感」的主要来源，改用大厂这种整块矩形高亮最稳妥。
 */
@androidx.compose.runtime.Composable
private fun ChromeButton(
    desc: String,
    danger: Boolean = false,
    onClick: () -> Unit,
    glyph: @androidx.compose.runtime.Composable (Color) -> Unit
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val scheme = androidx.compose.material3.MaterialTheme.colorScheme
    val tint = when {
        hovered && danger -> Color.White
        else -> scheme.onSurface.copy(alpha = 0.90f)
    }
    val background = when {
        hovered && danger -> Color(0xFFC42B1C)          // Win11 关闭钮红
        hovered -> scheme.onSurface.copy(alpha = 0.07f)
        else -> Color.Transparent
    }
    Box(
        modifier = Modifier
            .size(46.dp, 32.dp)
            .background(background)
            .hoverable(interaction)
            .clickable(
                interactionSource = interaction,
                indication = null,
                onClickLabel = desc
            ) { onClick() },
        contentAlignment = Alignment.Center
    ) {
        glyph(tint)
    }
}

/**
 * 八向边缘缩放手柄：4 条边 + 4 个角，拖拽调整窗口 bounds（设备像素级，支持任意 DPI）。
 * 挂在内容层之上，仅 5~20dp 宽的细条，不挡正常交互。
 */
@androidx.compose.runtime.Composable
private fun BoxScope.ResizeHandles(frame: java.awt.Frame?) {
    if (frame == null) return
    val E = java.awt.Cursor.E_RESIZE_CURSOR
    val W = java.awt.Cursor.W_RESIZE_CURSOR
    val N = java.awt.Cursor.N_RESIZE_CURSOR
    val S = java.awt.Cursor.S_RESIZE_CURSOR
    val NW = java.awt.Cursor.NW_RESIZE_CURSOR
    val NE = java.awt.Cursor.NE_RESIZE_CURSOR
    val SW = java.awt.Cursor.SW_RESIZE_CURSOR
    val SE = java.awt.Cursor.SE_RESIZE_CURSOR
    // 四条边
    ResizeHandle(frame, Alignment.TopCenter, N, Modifier.fillMaxWidth(0.9f).height(5.dp)) { f, dx, dy -> adjustBounds(f, dx, dy, n = true) }
    ResizeHandle(frame, Alignment.BottomCenter, S, Modifier.fillMaxWidth(0.9f).height(5.dp)) { f, dx, dy -> adjustBounds(f, dx, dy, s = true) }
    ResizeHandle(frame, Alignment.CenterStart, W, Modifier.width(5.dp).fillMaxSize()) { f, dx, dy -> adjustBounds(f, dx, dy, w = true) }
    ResizeHandle(frame, Alignment.CenterEnd, E, Modifier.width(5.dp).fillMaxSize()) { f, dx, dy -> adjustBounds(f, dx, dy, e = true) }
    // 四个角（画在边条之上，优先命中）
    ResizeHandle(frame, Alignment.TopStart, NW, Modifier.size(20.dp)) { f, dx, dy -> adjustBounds(f, dx, dy, n = true, w = true) }
    ResizeHandle(frame, Alignment.TopEnd, NE, Modifier.size(20.dp)) { f, dx, dy -> adjustBounds(f, dx, dy, n = true, e = true) }
    ResizeHandle(frame, Alignment.BottomStart, SW, Modifier.size(20.dp)) { f, dx, dy -> adjustBounds(f, dx, dy, s = true, w = true) }
    ResizeHandle(frame, Alignment.BottomEnd, SE, Modifier.size(20.dp)) { f, dx, dy -> adjustBounds(f, dx, dy, s = true, e = true) }
}

/** 单个缩放手柄：细条 + 对应光标 + 拖拽改窗口 bounds */
@androidx.compose.runtime.Composable
private fun BoxScope.ResizeHandle(
    frame: java.awt.Frame,
    align: Alignment,
    cursor: Int,
    sizeMod: Modifier,
    onDrag: (java.awt.Frame, Float, Float) -> Unit
) {
    val density = LocalDensity.current.density
    Box(
        modifier = Modifier
            .align(align)
            .then(sizeMod)
            .pointerHoverIcon(PointerIcon(java.awt.Cursor.getPredefinedCursor(cursor)))
            .pointerInput(Unit) {
                detectDragGestures { change, amount ->
                    change.consume()
                    onDrag(frame, amount.x * density, amount.y * density)
                }
            }
    )
}

/** 按方向调整窗口 bounds（设备像素；北/西向拖动同时移动原点，带最小尺寸钳制） */
private fun adjustBounds(
    f: java.awt.Frame,
    dx: Float,
    dy: Float,
    n: Boolean = false,
    s: Boolean = false,
    w: Boolean = false,
    e: Boolean = false
) {
    val minW = 560
    val minH = 420
    var x = f.x
    var y = f.y
    var width = f.width
    var height = f.height
    if (e) width += dx.toInt()
    if (s) height += dy.toInt()
    if (w) {
        val nw = width - dx.toInt()
        if (nw >= minW) { width = nw; x += dx.toInt() }
    }
    if (n) {
        val nh = height - dy.toInt()
        if (nh >= minH) { height = nh; y += dy.toInt() }
    }
    width = width.coerceAtLeast(minW)
    height = height.coerceAtLeast(minH)
    f.setBounds(x, y, width, height)
}

/**
 * 拿到 Compose 内部那个 CompositionLocal<WindowExceptionHandlerFactory>。
 * 名字在 1.7.3 里是 `androidx.compose.ui.window.WindowExceptionHandlerFactory_desktopKt
 * .getLocalWindowExceptionHandlerFactory()`（Kotlin 侧标了 internal，无法直接引用）。
 */
private fun windowExceptionHandlerLocal(): androidx.compose.runtime.ProvidableCompositionLocal<Any>? = runCatching {
    val holder = Class.forName("androidx.compose.ui.window.WindowExceptionHandlerFactory_desktopKt")
    // 不写死 getter 名：按「无参、返回 ProvidableCompositionLocal」找，Compose 小版本改名也不坏
    val getter = holder.declaredMethods.firstOrNull { m ->
        m.parameterCount == 0 &&
            m.returnType.name == "androidx.compose.runtime.ProvidableCompositionLocal"
    } ?: return@runCatching null
    @Suppress("UNCHECKED_CAST")
    getter.invoke(null) as androidx.compose.runtime.ProvidableCompositionLocal<Any>
}.getOrNull()

/**
 * 用 JDK 动态代理造一个 WindowExceptionHandlerFactory 实例（接口在 Kotlin 侧是 internal）。
 * Compose 调 `exceptionHandler(window)` 时返回我们传进来的 [handler]，从而不再弹默认错误框。
 */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
private fun windowExceptionFactory(
    handler: androidx.compose.ui.window.WindowExceptionHandler
): Any? = runCatching {
    val cls = Class.forName("androidx.compose.ui.window.WindowExceptionHandlerFactory")
    java.lang.reflect.Proxy.newProxyInstance(cls.classLoader, arrayOf(cls)) { proxy, method, args ->
        when (method.name) {
            // ★ 必须正确实现 Object 的三个方法：Compose 会把这个工厂放进 rememberUpdatedState，
            //   Intrinsics.areEqual 会调 equals() —— 返回 null 直接 NPE，整个窗口组合就挂了
            //   （表现为「启动后没有主窗口、进程还在」）。
            "equals" -> proxy === args?.firstOrNull()
            "hashCode" -> System.identityHashCode(proxy)
            "toString" -> "ZayuWindowExceptionHandlerFactory"
            "exceptionHandler" -> handler
            else -> null
        }
    }
}.getOrNull()

/**
 * 从 classpath 读取 icon.png 构建窗口图标（标题栏 / 任务栏）；
 * 失败返回 null（使用默认图标）。
 */
private fun loadWindowIcon(): androidx.compose.ui.graphics.painter.Painter? = runCatching {
    val bytes = Thread.currentThread().contextClassLoader
        ?.getResourceAsStream("icon.png")?.use { it.readBytes() }
        ?: return@runCatching null
    androidx.compose.ui.graphics.painter.BitmapPainter(
        org.jetbrains.skia.Image.makeFromEncoded(bytes).toComposeImageBitmap()
    )
}.getOrNull()

/**
 * 按偏好里记录的 AWT 屏幕像素坐标（"x,y"）把主窗口放回指定位置。
 * 用 AWT 坐标而非 WindowState 的 dp，避开缩放（density）换算误差；
 * 坐标落在任何屏幕之外（如副屏被拔掉）时不动，交给平台默认位置。
 * 供「上次关闭位置」与「自定义」两种启动位置共用（[saved] 为 null 时直接返回）。
 */
private fun restoreWindowPosition(frame: java.awt.Frame?, saved: String?) {
    if (saved.isNullOrBlank()) return
    val f = frame ?: return
    val parts = saved.split(',')
    if (parts.size != 2) return
    val x = parts[0].trim().toIntOrNull() ?: return
    val y = parts[1].trim().toIntOrNull() ?: return
    if (!isPointOnAnyScreen(x, y)) return
    runCatching { f.setLocation(x, y) }
}

/** 记录主窗口当前屏幕坐标，供下次启动「上次关闭位置」还原；最大化时不记录（否则还原到最大化矩形）。 */
private fun saveWindowPosition(frame: java.awt.Frame?) {
    val f = frame ?: return
    if (f.extendedState and java.awt.Frame.MAXIMIZED_BOTH != 0) return
    runCatching {
        com.zayu.app.data.prefs.SettingsRepository().windowLastPosition = "${f.x},${f.y}"
    }
}

/** 点 (x, y) 是否落在任一屏幕范围内（含多显示器负坐标布局） */
private fun isPointOnAnyScreen(x: Int, y: Int): Boolean = runCatching {
    java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment()
        .screenDevices
        .any { it.defaultConfiguration.bounds.contains(x, y) }
}.getOrDefault(true)

/**
 * 内嵌浏览器诊断冒烟：主线程初始化 JCEF → 创建浏览器加载页面 → 等待 20s → 输出 Cookie 统计。
 */
private fun runJcefSmoke(url: String) {
    println("SMOKE: init JCEF...")
    JcefHolder.initBlocking()
    val app = JcefHolder.app()
    if (app == null) {
        println("SMOKE: JCEF init FAILED")
        return
    }
    println("SMOKE: creating browser for $url ...")
    val client = app.createClient()
    client.addLoadHandler(object : org.cef.handler.CefLoadHandler {
        override fun onLoadingStateChange(
            browser: org.cef.browser.CefBrowser,
            isLoading: Boolean,
            canGoBack: Boolean,
            canGoForward: Boolean
        ) {
            println("SMOKE: loading=$isLoading url=${browser.url}")
        }

        override fun onLoadStart(
            browser: org.cef.browser.CefBrowser,
            frame: org.cef.browser.CefFrame,
            transitionType: org.cef.network.CefRequest.TransitionType
        ) {
        }

        override fun onLoadEnd(
            browser: org.cef.browser.CefBrowser,
            frame: org.cef.browser.CefFrame,
            httpStatusCode: Int
        ) {
            println("SMOKE: loadEnd status=$httpStatusCode url=${frame.url}")
        }

        override fun onLoadError(
            browser: org.cef.browser.CefBrowser,
            frame: org.cef.browser.CefFrame,
            errorCode: org.cef.handler.CefLoadHandler.ErrorCode,
            errorText: String,
            failedUrl: String
        ) {
            println("SMOKE: LOAD ERROR $errorCode $errorText $failedUrl")
        }
    })
    val browser = client.createBrowser(url, false, false)
    // JCEF 默认延迟到 UI 组件显示时才真正创建；冒烟无 UI，需立即创建
    browser.createImmediately()
    println("SMOKE: browser created, waiting 25s for page load...")
    Thread.sleep(25000)

    // 竞态实证：visitAllCookies 回调异步，0ms 立即读 vs 500ms 后读
    val manager = org.cef.network.CefCookieManager.getGlobalManager()
    fun readCookies(): List<String> {
        val list = java.util.concurrent.ConcurrentLinkedQueue<String>()
        manager.visitAllCookies(
            object : org.cef.callback.CefCookieVisitor {
                override fun visit(
                    cookie: org.cef.network.CefCookie,
                    count: Int,
                    total: Int,
                    delete: org.cef.misc.BoolRef
                ): Boolean {
                    list.add("${cookie.name}=${cookie.value}")
                    return true
                }
            }
        )
        Thread.sleep(500)
        return list.toList()
    }
    // 第一轮：visitAllCookies 返回后先不等待，立即取快照（用另一队列演示竞态）
    val immediate = java.util.concurrent.ConcurrentLinkedQueue<String>()
    manager.visitAllCookies(
        object : org.cef.callback.CefCookieVisitor {
            override fun visit(
                cookie: org.cef.network.CefCookie,
                count: Int,
                total: Int,
                delete: org.cef.misc.BoolRef
            ): Boolean {
                immediate.add("${cookie.name}=${cookie.value}")
                return true
            }
        }
    )
    val immediateCount = immediate.size
    // 等待回调完成后再取一遍
    val waitedCount = readCookies().size
    println("SMOKE: cookies read immediately(no wait)=$immediateCount, after 500ms=$waitedCount")
    println("SMOKE: cookie samples=${readCookies().take(5)}")

    // API 回环验证：写入测试 Cookie 再读取
    val testCookie = org.cef.network.CefCookie(
        "smoke.test", "roundtrip", ".quark.cn", "/",
        true, false, null, null, false, null
    )
    runCatching { manager.setCookie("https://pan.quark.cn", testCookie) }
    val counter = java.util.concurrent.atomic.AtomicInteger(0)
    runCatching {
        manager.visitAllCookies(
            object : org.cef.callback.CefCookieVisitor {
                override fun visit(
                    cookie: org.cef.network.CefCookie,
                    count: Int,
                    total: Int,
                    delete: org.cef.misc.BoolRef
                ): Boolean {
                    counter.incrementAndGet()
                    if (cookie.name == "smoke.test") {
                        println("SMOKE: roundtrip cookie value=${cookie.value}")
                    }
                    return true
                }
            }
        )
    }
    Thread.sleep(500)
    println("SMOKE: total cookies=${counter.get()}")
    runCatching { browser.close(true) }
    runCatching { client.dispose() }
    println("SMOKE: done")
}
