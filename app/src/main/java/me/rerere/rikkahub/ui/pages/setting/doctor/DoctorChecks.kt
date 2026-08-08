package me.rerere.rikkahub.ui.pages.setting.doctor

import android.Manifest
import android.content.Context
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import me.rerere.rikkahub.BuildConfig
import me.rerere.rikkahub.data.ai.tools.LocalToolOption
import me.rerere.rikkahub.data.ai.tools.local.AccessibilityServiceHandle
import me.rerere.rikkahub.data.ai.tools.local.NotificationListenerHandle
import me.rerere.rikkahub.data.ai.tools.local.PermissionHelper
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.getCurrentAssistant
import me.rerere.rikkahub.data.db.AppDatabase
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.data.repository.ScheduledJobRepository
import me.rerere.rikkahub.data.repository.ScheduledJobRunRepository
import me.rerere.rikkahub.data.telegram.TelegramBotPreferences
import me.rerere.rikkahub.service.TelegramBotService
import me.rerere.rikkahub.workflow.repository.WorkflowRepository
import me.rerere.rikkahub.browser.BrowserPreferences
import me.rerere.rikkahub.browser.BrowserToolDefaults
import java.net.InetAddress
import java.io.File

/**
 * Each row that depends on a system capability (a permission, an OS-level service binding,
 * Termux being installed) is "tool-aware": if no enabled tool needs the capability, the
 * row drops to INFO with a "not required" subtitle so the screen doesn't drown the user
 * in WARN noise about features they don't use.
 *
 * The map below records which [LocalToolOption] groups depend on which capability. The
 * answer comes from the tool registration code in `LocalTools.kt` — when a new tool is
 * added that needs a capability, also add its option here.
 */
private object Capability {
    val Notifications: Set<LocalToolOption> = setOf(
        LocalToolOption.Notification,        // post_notification tool
        LocalToolOption.TelegramBot,         // FGS notification
        LocalToolOption.CronJobs,            // CronJobWorker FGS notification
        LocalToolOption.Workflows,           // WorkflowTimeCronWorker FGS notification
    )
    val FineLocation: Set<LocalToolOption> = setOf(
        LocalToolOption.Location,            // get_location, geocode tools
        LocalToolOption.WifiInfo,            // SSID/BSSID on Android 10+
        LocalToolOption.Workflows,           // geofence_enter / geofence_exit triggers
    )
    val NotificationListener: Set<LocalToolOption> = setOf(
        LocalToolOption.NotificationListener,
        LocalToolOption.Workflows,           // notification_received trigger
    )
    val Accessibility: Set<LocalToolOption> = setOf(
        LocalToolOption.ScreenAutomation,    // take_screenshot, swipe, click_at, scroll, gesture
    )
    val Termux: Set<LocalToolOption> = setOf(
        LocalToolOption.Termux,
        LocalToolOption.SpeechToText,        // transcribe_audio_file uses Termux + whisper.cpp
        LocalToolOption.Ssh,                 // ssh_exec calls into termux ssh
    )
    val BatteryWhitelist: Set<LocalToolOption> = setOf(
        LocalToolOption.TelegramBot,         // long-poll loop
        LocalToolOption.CronJobs,            // worker fires
        LocalToolOption.Workflows,           // trigger receivers + cron worker
    )
    val AllFiles: Set<LocalToolOption> = setOf(
        LocalToolOption.Files,               // file_read / file_write to arbitrary paths
    )
    val Browser: Set<LocalToolOption> = setOf(
        LocalToolOption.Browser,             // 17 browser tools (in-app WebView)
    )
    // Phase 25 — Phase 3 second cut.
    val SendSms: Set<LocalToolOption> = setOf(
        LocalToolOption.SmsSend,
    )
    val Nfc: Set<LocalToolOption> = setOf(
        LocalToolOption.Nfc,
    )
    // Permissions that previously had no Doctor check at all. Each is gated on the tool that
    // actually needs it, so a denied perm only WARNs when its feature is enabled (opt-in) and
    // stays INFO otherwise. Closes the "Doctor reported all-clear while overlay etc. were denied"
    // gap.
    val Overlay: Set<LocalToolOption> = setOf(
        LocalToolOption.ScreenAutomation,    // "agent is working" overlay during automation
    )
    val WriteSettings: Set<LocalToolOption> = setOf(
        LocalToolOption.Brightness,          // set_brightness writes Settings.System
    )
    val BluetoothConnect: Set<LocalToolOption> = setOf(
        LocalToolOption.Workflows,           // workflow Bluetooth triggers read paired-device state
    )
    val NearbyWifi: Set<LocalToolOption> = setOf(
        LocalToolOption.WifiInfo,            // WiFi scan/info on Android 13+
    )
    val BackgroundLocation: Set<LocalToolOption> = setOf(
        LocalToolOption.Workflows,           // geofence triggers fire while the app is closed
    )
}

/** Friendly name for the row's "needed by:" subtitle. */
private fun LocalToolOption.shortName(): String = when (this) {
    LocalToolOption.Location -> "定位"
    LocalToolOption.WifiInfo -> "WiFi 信息"
    LocalToolOption.NotificationListener -> "通知监听"
    LocalToolOption.ScreenAutomation -> "屏幕自动化"
    LocalToolOption.Termux -> "Termux"
    LocalToolOption.SpeechToText -> "语音转文字"
    LocalToolOption.Ssh -> "SSH"
    LocalToolOption.TelegramBot -> "Telegram 机器人"
    LocalToolOption.CronJobs -> "定时任务"
    LocalToolOption.Workflows -> "工作流"
    LocalToolOption.Notification -> "通知"
    LocalToolOption.Files -> "文件"
    LocalToolOption.Browser -> "浏览器"
    LocalToolOption.SmsSend -> "发送短信"
    LocalToolOption.Wallpaper -> "壁纸"
    LocalToolOption.Keystore -> "密钥库"
    LocalToolOption.Nfc -> "NFC"
    LocalToolOption.ExternalStorage -> "外部存储"
    LocalToolOption.Archive -> "压缩包 (zip)"
    else -> this::class.simpleName ?: "?"
}

/**
 * Run every diagnostic check. Returns the flat list — the Doctor screen groups by
 * [DoctorCheck.category].
 *
 * Most checks are cheap (Settings.Secure reads, package manager queries, in-memory state)
 * but a few do I/O (DB integrity PRAGMA, DNS resolve). Run on Dispatchers.IO at the call
 * site; the function itself is suspending so individual probes can withTimeoutOrNull.
 *
 * Adding a new check: append to the appropriate `runXxxChecks` block. Each helper function
 * returns either a single check or a list. Keep checks short — one concern per row.
 */
class DoctorChecks(
    private val context: Context,
    private val settingsStore: SettingsStore,
    private val telegramPrefs: TelegramBotPreferences,
    private val workflowRepository: WorkflowRepository,
    private val scheduledJobRepository: ScheduledJobRepository,
    private val scheduledJobRunRepository: ScheduledJobRunRepository,
    private val conversationRepository: ConversationRepository,
    private val database: AppDatabase,
    // Pass 3: per-tool browser toggle store. Used by the browser write-tools-enabled INFO
    // row so the user can spot-check which side-effecting tools are currently switched on.
    // Optional + nullable so callers that don't construct this DoctorChecks via the DI
    // graph (a few legacy tests) keep compiling — the row is silently skipped when null.
    private val browserPreferences: BrowserPreferences? = null,
    // Phase 25 — SAF tree-grant store, backs the "granted directories" Doctor row.
    // Nullable + defaulted so legacy test paths that don't build the full DI graph compile.
    private val storageVolumeGrantStore: me.rerere.rikkahub.data.storage.StorageVolumeGrantStore? = null,
    // Surface the persisted LiteRT accelerator decision so the user can see whether their
    // local models actually engaged GPU/NPU or silently fell back to CPU.
    // Nullable + defaulted same as the others above for legacy test path compatibility.
    private val localRuntimePreferences: me.rerere.locallm.LocalRuntimePreferences? = null,
) {
    suspend fun runAll(): List<DoctorCheck> = withContext(Dispatchers.IO) {
        // Aggregate enabled tools across every assistant. A tool is "in use" if at least
        // one assistant has its LocalToolOption switched on. The Doctor uses this to
        // decide whether a missing capability is actually a problem worth flagging.
        val enabled: Set<LocalToolOption> = runCatching {
            settingsStore.settingsFlow.first().assistants.flatMap { it.localTools }.toSet()
        }.getOrDefault(emptySet())

        buildList {
            addAll(permissionChecks(enabled))
            addAll(serviceChecks(enabled))
            addAll(assistantChecks())
            addAll(databaseChecks(enabled))
            addAll(networkChecks())
            addAll(termuxChecks(enabled))
            addAll(browserChecks(enabled))
            addAll(maintenanceChecks())
            addAll(diagnosticsChecks(enabled))
        }
    }

    /**
     * Render the "needed by:" subtitle for a tool-aware row. If the requirement is currently
     * unsatisfied, list the enabled tools that demand it so the user knows why they should
     * care. Returns null when no enabled tool needs the capability — callers down-grade
     * severity to INFO in that case.
     */
    private fun requirersOf(cap: Set<LocalToolOption>, enabled: Set<LocalToolOption>): List<LocalToolOption> =
        cap.filter { it in enabled }

    // ----- Permissions ----------------------------------------------------------------

    private fun permissionChecks(enabled: Set<LocalToolOption>): List<DoctorCheck> = buildList {
        add(
            capabilityRow(
                id = "perm.notifications",
                category = DoctorCategory.Permissions,
                label = "通知权限",
                cap = Capability.Notifications,
                enabled = enabled,
                granted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                    PermissionHelper.hasRuntime(context, listOf(Manifest.permission.POST_NOTIFICATIONS)),
                grantedDetail = "已授予。",
                missingDetail = "前台服务通知、工具审批和工作流提醒需要此权限。",
                fix = FixAction.OpenAppRoute("打开应用权限", AppRouteKey.SettingPermissions),
            )
        )
        add(
            capabilityRow(
                id = "perm.location",
                category = DoctorCategory.Permissions,
                label = "精确定位权限",
                cap = Capability.FineLocation,
                enabled = enabled,
                granted = PermissionHelper.hasRuntime(context, listOf(Manifest.permission.ACCESS_FINE_LOCATION)),
                grantedDetail = "已授予。",
                missingDetail = "地理围栏触发以及在 Android 10+ 上读取 WiFi SSID 需要此权限。",
                fix = FixAction.OpenAppRoute("打开应用权限", AppRouteKey.SettingPermissions),
            )
        )
        add(
            capabilityRow(
                id = "perm.battery_opt",
                category = DoctorCategory.Permissions,
                label = "电池优化白名单",
                cap = Capability.BatteryWhitelist,
                enabled = enabled,
                granted = PermissionHelper.ignoresBatteryOptimizations(context),
                grantedDetail = "应用已在白名单中——后台服务可稳定运行。",
                missingDetail = "系统休眠（Doze）可能杀死 Telegram 机器人、定时任务和工作流。",
                fix = FixAction.OpenIntent(
                    label = "申请白名单",
                    intent = PermissionHelper.requestIgnoreBatteryOptimizationsIntent(context),
                ),
            )
        )
        add(
            capabilityRow(
                id = "perm.notification_listener",
                category = DoctorCategory.Permissions,
                label = "通知监听权限",
                cap = Capability.NotificationListener,
                enabled = enabled,
                granted = PermissionHelper.hasNotificationListener(context),
                grantedDetail = "已授予——监听器可读取通知。",
                missingDetail = "未授予。notification_received 触发器与通知相关工具将无法工作。",
                fix = FixAction.OpenIntent(
                    label = "打开设置",
                    intent = PermissionHelper.notificationListenerSettingsIntent(),
                ),
            )
        )
        add(
            capabilityRow(
                id = "perm.accessibility",
                category = DoctorCategory.Permissions,
                label = "无障碍服务",
                cap = Capability.Accessibility,
                enabled = enabled,
                granted = PermissionHelper.hasAccessibilityService(context),
                grantedDetail = "已在系统设置中启用。",
                missingDetail = "未启用。take_screenshot、swipe、scroll、click_at 和手势工具将无法工作。",
                fix = FixAction.OpenIntent(
                    label = "打开设置",
                    intent = PermissionHelper.accessibilitySettingsIntent(),
                ),
            )
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            add(
                capabilityRow(
                    id = "perm.all_files",
                    category = DoctorCategory.Permissions,
                    label = "所有文件访问权限",
                    cap = Capability.AllFiles,
                    enabled = enabled,
                    granted = PermissionHelper.hasAllFilesAccess(context),
                    grantedDetail = "已授予——file_read / file_write 工具可访问任意路径。",
                    missingDetail = "未授予。文件工具仅限于分区存储。",
                    fix = FixAction.OpenIntent(
                        label = "打开设置",
                        intent = PermissionHelper.allFilesAccessIntent(context),
                    ),
                )
            )
        }
        // Phase 25 — SEND_SMS runtime permission row for the send_sms tool.
        add(
            capabilityRow(
                id = "perm.send_sms",
                category = DoctorCategory.Permissions,
                label = "发送短信权限",
                cap = Capability.SendSms,
                enabled = enabled,
                granted = PermissionHelper.hasRuntime(context, listOf(Manifest.permission.SEND_SMS)),
                grantedDetail = "已授予。",
                missingDetail = "send_sms 工具需要此权限来发送短信。",
                fix = FixAction.OpenAppRoute("打开应用权限", AppRouteKey.SettingPermissions),
            )
        )
        // Previously-unchecked permissions, now covered. Each is tool-aware: it only WARNs when
        // the feature that needs it is enabled, so the opt-in philosophy holds (a denied perm for
        // a disabled tool stays INFO). This is what fixes the "Doctor said all-clear while
        // Display-over-other-apps etc. were ungranted" report.
        add(
            capabilityRow(
                id = "perm.overlay",
                category = DoctorCategory.Permissions,
                label = "在其他应用上层显示",
                cap = Capability.Overlay,
                enabled = enabled,
                granted = android.provider.Settings.canDrawOverlays(context),
                grantedDetail = "已授予。",
                missingDetail = "屏幕自动化期间无法显示\"正在工作\"悬浮层。",
                fix = FixAction.OpenAppRoute("打开应用权限", AppRouteKey.SettingPermissions),
            )
        )
        add(
            capabilityRow(
                id = "perm.write_settings",
                category = DoctorCategory.Permissions,
                label = "修改系统设置",
                cap = Capability.WriteSettings,
                enabled = enabled,
                granted = PermissionHelper.hasWriteSettings(context),
                grantedDetail = "已授予。",
                missingDetail = "没有此权限时 set_brightness 无法修改屏幕亮度。",
                fix = FixAction.OpenAppRoute("打开应用权限", AppRouteKey.SettingPermissions),
            )
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            add(
                capabilityRow(
                    id = "perm.bluetooth_connect",
                    category = DoctorCategory.Permissions,
                    label = "蓝牙连接",
                    cap = Capability.BluetoothConnect,
                    enabled = enabled,
                    granted = PermissionHelper.hasRuntime(context, listOf(Manifest.permission.BLUETOOTH_CONNECT)),
                    grantedDetail = "已授予。",
                    missingDetail = "工作流的蓝牙触发器无法读取已配对设备状态。",
                    fix = FixAction.OpenAppRoute("打开应用权限", AppRouteKey.SettingPermissions),
                )
            )
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(
                capabilityRow(
                    id = "perm.nearby_wifi",
                    category = DoctorCategory.Permissions,
                    label = "附近 WiFi 设备",
                    cap = Capability.NearbyWifi,
                    enabled = enabled,
                    granted = PermissionHelper.hasRuntime(context, listOf(Manifest.permission.NEARBY_WIFI_DEVICES)),
                    grantedDetail = "已授予。",
                    missingDetail = "没有此权限时，Android 13+ 上的 WiFi 扫描/信息可能受限。",
                    fix = FixAction.OpenAppRoute("打开应用权限", AppRouteKey.SettingPermissions),
                )
            )
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            add(
                capabilityRow(
                    id = "perm.background_location",
                    category = DoctorCategory.Permissions,
                    label = "后台定位",
                    cap = Capability.BackgroundLocation,
                    enabled = enabled,
                    granted = PermissionHelper.hasRuntime(context, listOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION)),
                    grantedDetail = "已授予。",
                    missingDetail = "应用关闭后，地理围栏工作流触发器将无法触发。",
                    fix = FixAction.OpenAppRoute("打开应用权限", AppRouteKey.SettingPermissions),
                )
            )
        }
        // Phase 25 — NFC combined hardware + system-toggle row. Tri-state: no hardware
        // (INFO, no fix), hardware present but disabled (WARN, open NFC settings), on (OK).
        run {
            val adapter = android.nfc.NfcAdapter.getDefaultAdapter(context)
            val nfcNeeders = requirersOf(Capability.Nfc, enabled)
            when {
                adapter == null -> add(
                    DoctorCheck(
                        id = "perm.nfc_enabled",
                        category = DoctorCategory.Permissions,
                        label = "NFC",
                        detail = "设备没有 NFC 硬件。",
                        severity = Severity.INFO,
                    )
                )
                !adapter.isEnabled -> add(
                    DoctorCheck(
                        id = "perm.nfc_enabled",
                        category = DoctorCategory.Permissions,
                        label = "NFC",
                        detail = if (nfcNeeders.isEmpty())
                            "NFC 已在系统设置中关闭。没有启用的工具需要它。"
                        else
                            "NFC 已在系统设置中关闭。需要方：" +
                                nfcNeeders.joinToString(", ") { it.shortName() } + ".",
                        severity = if (nfcNeeders.isEmpty()) Severity.INFO else Severity.WARN,
                        fix = if (nfcNeeders.isEmpty()) null else FixAction.OpenIntent(
                            label = "打开 NFC 设置",
                            intent = android.content.Intent(android.provider.Settings.ACTION_NFC_SETTINGS)
                                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
                        ),
                    )
                )
                else -> add(
                    DoctorCheck(
                        id = "perm.nfc_enabled",
                        category = DoctorCategory.Permissions,
                        label = "NFC",
                        detail = "NFC 硬件存在且已启用。",
                        severity = Severity.OK,
                    )
                )
            }
        }
    }

    /**
     * Build a capability-aware Doctor row.
     *   granted = true                                  -> Severity.OK
     *   granted = false AND no enabled tool needs cap   -> Severity.INFO ("not required")
     *   granted = false AND some enabled tool needs cap -> Severity.WARN ("needed by: …")
     *
     * The Fix button is offered only when granted=false AND at least one tool needs the
     * capability — we don't push the user to grant a permission they don't currently use.
     */
    private fun capabilityRow(
        id: String,
        category: DoctorCategory,
        label: String,
        cap: Set<LocalToolOption>,
        enabled: Set<LocalToolOption>,
        granted: Boolean,
        grantedDetail: String,
        missingDetail: String,
        fix: FixAction,
    ): DoctorCheck {
        val needers = requirersOf(cap, enabled)
        val severity = when {
            granted -> Severity.OK
            needers.isEmpty() -> Severity.INFO
            else -> Severity.WARN
        }
        val detail = when {
            granted -> grantedDetail
            needers.isEmpty() -> "没有启用的工具需要此项。"
            else -> "$missingDetail 需要方：${needers.joinToString(", ") { it.shortName() }}。"
        }
        return DoctorCheck(
            id = id,
            category = category,
            label = label,
            detail = detail,
            severity = severity,
            fix = if (!granted && needers.isNotEmpty()) fix else null,
        )
    }

    // ----- Background services ---------------------------------------------------------

    private suspend fun serviceChecks(enabled: Set<LocalToolOption>): List<DoctorCheck> = buildList {
        val tg = telegramPrefs.current()
        // Telegram bot: token, enabled flag, FGS state should agree.
        if (tg.enabled) {
            add(
                DoctorCheck(
                    id = "service.telegram_token",
                    category = DoctorCategory.Services,
                    label = "Telegram 机器人令牌",
                    // Don't render any portion of the token — Telegram bot tokens are
                    // formatted "<bot_id>:<secret>" and even the first 6 chars reveal the
                    // bot id, which an attacker could use to enumerate bot endpoints.
                    detail = if (tg.token.isNotBlank()) "已配置令牌（${tg.token.length} 个字符，已隐藏）。"
                    else "Telegram 机器人已启用但未设置令牌——服务启动时会失败。",
                    severity = if (tg.token.isNotBlank()) Severity.OK else Severity.FAIL,
                    fix = if (tg.token.isBlank())
                        FixAction.OpenAppRoute("打开 Telegram 设置", AppRouteKey.SettingTelegram)
                    else null,
                )
            )
            add(
                DoctorCheck(
                    id = "service.telegram_running",
                    category = DoctorCategory.Services,
                    label = "Telegram 机器人前台服务",
                    detail = if (TelegramBotService.isRunning) "服务正在运行。"
                    else "服务已停止。Telegram 消息将无法到达助手。看门狗会在下一次 30 分钟健康检查时重试。",
                    severity = when {
                        TelegramBotService.isRunning -> Severity.OK
                        tg.token.isBlank() -> Severity.INFO  // token issue covers this
                        else -> Severity.FAIL
                    },
                )
            )
        } else {
            add(
                DoctorCheck(
                    id = "service.telegram_off",
                    category = DoctorCategory.Services,
                    label = "Telegram 机器人",
                    detail = "已禁用——如果不用 Telegram 就没问题。",
                    severity = Severity.INFO,
                )
            )
        }
        // AccessibilityService binding — only flagged if a tool that needs it is enabled.
        val accNeeders = requirersOf(Capability.Accessibility, enabled)
        if (accNeeders.isNotEmpty()) {
            add(
                DoctorCheck(
                    id = "service.accessibility_bound",
                    category = DoctorCategory.Services,
                    label = "无障碍服务已绑定",
                    detail = if (AccessibilityServiceHandle.isRunning())
                        "服务对象存活——${accNeeders.joinToString(", ") { it.shortName() }} 可运行。"
                    else if (PermissionHelper.hasAccessibilityService(context))
                        "已在设置中启用但未绑定（Android 杀死了服务或服务尚未启动）。请先关闭再重新打开。"
                    else
                        "未启用。需要方：${accNeeders.joinToString(", ") { it.shortName() }}。",
                    severity = when {
                        AccessibilityServiceHandle.isRunning() -> Severity.OK
                        else -> Severity.WARN
                    },
                    fix = if (!AccessibilityServiceHandle.isRunning()) FixAction.OpenIntent(
                        label = "打开设置",
                        intent = PermissionHelper.accessibilitySettingsIntent(),
                    ) else null,
                )
            )
        }
        // NotificationListener binding — same logic.
        val nlNeeders = requirersOf(Capability.NotificationListener, enabled)
        if (nlNeeders.isNotEmpty()) {
            add(
                DoctorCheck(
                    id = "service.notification_listener_bound",
                    category = DoctorCategory.Services,
                    label = "通知监听器已绑定",
                    detail = if (NotificationListenerHandle.isBound())
                        "监听器已绑定——${nlNeeders.joinToString(", ") { it.shortName() }} 可运行。"
                    else if (PermissionHelper.hasNotificationListener(context))
                        "已授予但当前未绑定。请在设置中先关闭再打开。"
                    else
                        "未授予。需要方：${nlNeeders.joinToString(", ") { it.shortName() }}。",
                    severity = when {
                        NotificationListenerHandle.isBound() -> Severity.OK
                        else -> Severity.WARN
                    },
                    fix = if (!NotificationListenerHandle.isBound()) FixAction.OpenIntent(
                        label = "打开设置",
                        intent = PermissionHelper.notificationListenerSettingsIntent(),
                    ) else null,
                )
            )
        }
    }

    // ----- Active assistant ------------------------------------------------------------

    /**
     * Informational section. All rows are [Severity.INFO] — these are status rows, not
     * problem rows. The single "default assistant" row surfaces the assistant that:
     *   - New Telegram conversations use (when no explicit assistantId is configured).
     *   - Cron jobs run as (their assistantId is locked at job creation time, but new jobs
     *     inherit from the Settings default).
     *   - New in-app chats default to.
     *
     * A WARN row fires when the global assistant list is empty — that's a sign the settings
     * store was corrupted or a migration wiped the assistants list.
     *
     * A separate row shows the Telegram-bot-configured override if one is set.
     */
    private suspend fun assistantChecks(): List<DoctorCheck> = buildList {
        runCatching {
            val settings = settingsStore.settingsFlow.first()
            val assistants = settings.assistants
            val defaultAssistant = settings.getCurrentAssistant()

            // Row 1: default assistant name + id
            add(
                DoctorCheck(
                    id = "assistant.default",
                    category = DoctorCategory.AssistantInfo,
                    label = "默认助手",
                    detail = if (assistants.isEmpty())
                        "未配置任何助手——应用将无法开始对话。"
                    else
                        "\"${defaultAssistant.name.ifBlank { "(未命名)" }}\" " +
                        "（id: ${defaultAssistant.id.toString().take(8)}…）。" +
                        "未设置覆盖时，新聊天、定时任务和 Telegram 使用此助手。",
                    severity = if (assistants.isEmpty()) Severity.WARN else Severity.INFO,
                    fix = FixAction.OpenAppRoute("打开助手", AppRouteKey.Assistant),
                )
            )

            // Row 2: total assistant count
            add(
                DoctorCheck(
                    id = "assistant.count",
                    category = DoctorCategory.AssistantInfo,
                    label = "助手数量",
                    detail = "已配置 ${assistants.size} 个助手。",
                    severity = Severity.INFO,
                    fix = FixAction.OpenAppRoute("打开助手", AppRouteKey.Assistant),
                )
            )

            // Row 3: Telegram-bot assistant override (if set)
            val tg = telegramPrefs.current()
            if (tg.enabled && tg.assistantId != null) {
                val tgAssistant = tg.assistantId.let { id ->
                    runCatching {
                        val uuid = kotlin.uuid.Uuid.parse(id)
                        assistants.find { it.id == uuid }
                    }.getOrNull()
                }
                add(
                    DoctorCheck(
                        id = "assistant.telegram_override",
                        category = DoctorCategory.AssistantInfo,
                        label = "Telegram 机器人助手覆盖",
                        detail = when {
                            tgAssistant != null ->
                                "Telegram 入站消息路由到 \"${tgAssistant.name.ifBlank { "(未命名)" }}\" " +
                                "（id: ${tgAssistant.id.toString().take(8)}…）——覆盖全局默认设置。"
                            else ->
                                "已设置 Telegram 助手覆盖（id: ${tg.assistantId.take(8)}…），但未找到匹配的 " +
                                "助手。消息将回退到全局默认助手。"
                        },
                        severity = if (tgAssistant != null) Severity.INFO else Severity.WARN,
                        fix = if (tgAssistant == null)
                            FixAction.OpenAppRoute("打开 Telegram 设置", AppRouteKey.SettingTelegram)
                        else null,
                    )
                )
            }
        }
    }

    // ----- Database --------------------------------------------------------------------

    private suspend fun databaseChecks(enabled: Set<LocalToolOption>): List<DoctorCheck> = buildList {
        // Migration version
        val version = runCatching { database.openHelper.readableDatabase.version }.getOrDefault(-1)
        add(
            DoctorCheck(
                id = "db.version",
                category = DoctorCategory.Database,
                label = "数据库架构版本",
                // Room refuses to open the DB unless the stored version matches the compiled schema;
                // if we got here, version is the live schema version (migrations ran successfully).
                detail = if (version > 0) "v$version——迁移完成，架构一致。"
                else "无法读取数据库版本——Room 可能打开数据库失败。",
                severity = if (version > 0) Severity.OK else Severity.WARN,
            )
        )
        // Integrity check
        val integrity = runCatching {
            withTimeoutOrNull(5_000L) {
                database.openHelper.readableDatabase
                    .query("PRAGMA integrity_check;")
                    .use { c -> if (c.moveToFirst()) c.getString(0) else null }
            }
        }.getOrNull()
        // Offer an AutoFix only when the corruption mentions message_fts — that's the one
        // we know how to repair (DROP + recreate + reindex from the messages table). For
        // any other integrity failure, surface the message and let the user decide; we
        // don't blanket-rebuild things we don't know are safe.
        val mentionsFts = integrity != null && integrity != "ok" && integrity.contains("message_fts", ignoreCase = true)
        add(
            DoctorCheck(
                id = "db.integrity",
                category = DoctorCategory.Database,
                label = "数据库完整性检查",
                detail = when (integrity) {
                    null -> "完整性检查超时或失败。"
                    "ok" -> "PRAGMA integrity_check 返回正常。"
                    else -> "完整性检查返回：$integrity"
                },
                severity = if (integrity == "ok") Severity.OK else Severity.FAIL,
                fix = if (mentionsFts) FixAction.AutoFix(
                    label = "重建搜索索引",
                    run = {
                        runCatching {
                            val n = conversationRepository.repairAndRebuildIndexes()
                            AutoFixResult(ok = true, message = "已从 $n 个对话重建 message_fts。")
                        }.getOrElse {
                            AutoFixResult(
                                ok = false,
                                message = "修复失败：${it::class.simpleName}: ${it.message ?: "?"}",
                            )
                        }
                    },
                ) else null,
            )
        )
        // Workflows summary
        runCatching {
            val all = workflowRepository.observeAll().first()
            val enabled = all.count { it.entity.enabled }
            add(
                DoctorCheck(
                    id = "db.workflows",
                    category = DoctorCategory.Database,
                    label = "工作流",
                    detail = "共 ${all.size} 个，已启用 $enabled 个。",
                    severity = Severity.INFO,
                    fix = if (all.isNotEmpty())
                        FixAction.OpenAppRoute("打开工作流", AppRouteKey.SettingWorkflows)
                    else null,
                )
            )
        }
        // Scheduled jobs summary
        runCatching {
            val all = scheduledJobRepository.getAll()
            val enabled = all.count { it.enabled }
            add(
                DoctorCheck(
                    id = "db.scheduled_jobs",
                    category = DoctorCategory.Database,
                    label = "定时任务",
                    detail = "共 ${all.size} 个，已启用 $enabled 个。",
                    severity = Severity.INFO,
                    fix = if (all.isNotEmpty())
                        FixAction.OpenAppRoute("打开定时任务", AppRouteKey.SettingScheduledJobs)
                    else null,
                )
            )
        }
        // Stranded run rows (started but never finished — process killed mid-run)
        runCatching {
            val stranded = scheduledJobRunRepository.getStranded(System.currentTimeMillis() - 30 * 60_000L)
            add(
                DoctorCheck(
                    id = "db.stranded_runs",
                    category = DoctorCategory.Database,
                    label = "滞留的定时任务运行",
                    detail = if (stranded.isEmpty())
                        "无。Worker 一直正常完成所有运行。"
                    else
                        "${stranded.size} 个运行在 30 分钟前启动且未回报。可能是运行中途进程被杀。",
                    severity = if (stranded.isEmpty()) Severity.OK else Severity.WARN,
                )
            )
        }
        // Phase 25 — SAF granted-directories live count for the ExternalStorage tool.
        // Reconciles against the OS persisted-permission list so revoked grants drop off.
        val store = storageVolumeGrantStore
        if (store != null) {
            runCatching {
                val externalStorageEnabled = enabled.contains(LocalToolOption.ExternalStorage)
                val grants = store.reconcile()
                add(
                    DoctorCheck(
                        id = "storage.granted_directories",
                        category = DoctorCategory.Database,
                        label = "已授权目录",
                        detail = when {
                            !externalStorageEnabled && grants.isEmpty() ->
                                "外部存储工具未启用。不需要。"
                            grants.isEmpty() ->
                                "尚未授权任何目录。调用 grant_directory_access 添加一个。"
                            else ->
                                "已授权 ${grants.size} 个目录：" +
                                    grants.joinToString(", ") { it.displayName } + "."
                        },
                        severity = if (externalStorageEnabled && grants.isNotEmpty())
                            Severity.OK else Severity.INFO,
                    )
                )
            }
        }
    }

    // ----- Network & providers ---------------------------------------------------------

    private suspend fun networkChecks(): List<DoctorCheck> = buildList {
        runCatching {
            val settings = settingsStore.settingsFlow.first()
            val provs = settings.providers
            val configured = provs.count { p ->
                when (p) {
                    is me.rerere.ai.provider.ProviderSetting.OpenAI -> p.apiKey.isNotBlank()
                    is me.rerere.ai.provider.ProviderSetting.Google -> p.apiKey.isNotBlank()
                    is me.rerere.ai.provider.ProviderSetting.Claude -> p.apiKey.isNotBlank()
                    is me.rerere.ai.provider.ProviderSetting.AICore -> p.enabled  // on-device, no API key
                    // Local provider (LiteRT): usable when enabled AND at least one model has
                    // been loaded/downloaded. A disabled provider with no models is the factory
                    // default — don't count it.
                    is me.rerere.ai.provider.ProviderSetting.LiteRtLocal -> p.enabled && p.models.isNotEmpty()
                    // Local provider (llama.cpp): usable when enabled AND at least one model
                    // has been loaded, same criterion as LiteRT above.
                    is me.rerere.ai.provider.ProviderSetting.LlamaCppLocal -> p.enabled && p.models.isNotEmpty()
                    is me.rerere.ai.provider.ProviderSetting.Codex -> p.enabled  // OAuth, no API key
                    is me.rerere.ai.provider.ProviderSetting.Grok -> p.enabled  // OAuth, no API key
                    is me.rerere.ai.provider.ProviderSetting.GeminiOAuth -> p.enabled  // OAuth, no API key
                }
            }
            add(
                DoctorCheck(
                    id = "net.providers",
                    category = DoctorCategory.Network,
                    label = "已配置的 LLM 提供商",
                    detail = "共 ${provs.size} 个提供商，已配置 $configured 个（已设置 API key、已启用 AICore 或已加载本地模型）。",
                    severity = if (configured > 0) Severity.OK else Severity.WARN,
                    fix = FixAction.OpenAppRoute("打开提供商", AppRouteKey.SettingProvider),
                )
            )
        }
        // LiteRT accelerator status. The runtime's GPU -> CPU fallback is silent today:
        // if the device's OpenCL/OpenGL delegate fails to init (e.g. MLDrift's
        // "CreateSharedMemoryManager is not implemented" on some Adreno drivers), the
        // model loads on CPU and the user has no UI indication. LiteRtProvider now
        // persists the actually-chosen accelerator after every load; surface that here
        // so the user can confirm GPU is engaged.
        runCatching {
            val prefs = localRuntimePreferences
            if (prefs != null) {
                val accel = prefs.acceleratorFlow(me.rerere.locallm.LocalRuntime.LiteRT).first()
                val forceCpu = prefs.forceCpu(me.rerere.locallm.LocalRuntime.LiteRT)
                val detail = when {
                    accel == null -> "尚未探测。加速器在首次加载模型时确定。"
                    forceCpu && accel == "CPU" ->
                        "CPU（设置 → 本地 LiteRT 中已关闭“尝试 GPU”开关）。" +
                            "打开该开关可在下次加载时重试设备的 GPU。"
                    accel == "CPU" ->
                        "CPU（回退：GPU 代理在此设备上初始化失败，" +
                            "可能是 MLDrift 问题。点击设置 → 本地 LiteRT 中的“重新检测”" +
                            "以重新探测。）"
                    accel == "GPU" -> "GPU（OpenCL 或 OpenGL，由 LiteRT 内部探测选择）。"
                    accel == "QNN" || accel == "NPU" -> "NPU（Qualcomm QNN 代理）。"
                    accel == "NNAPI" -> "NNAPI。"
                    else -> "后端标签：$accel"
                }
                val severity = when {
                    accel == null -> Severity.INFO
                    accel == "CPU" && !forceCpu -> Severity.WARN  // unexpected fallback
                    else -> Severity.OK
                }
                add(
                    DoctorCheck(
                        id = "net.litert_accel",
                        category = DoctorCategory.Network,
                        label = "LiteRT 加速器",
                        detail = detail,
                        severity = severity,
                        fix = FixAction.OpenAppRoute(
                            "打开本地 LiteRT",
                            AppRouteKey.SettingProvider,
                        ),
                    )
                )
                // Performance telemetry — surface the last-known prefill/decode tok/s for
                // each model so the user (and the support team triaging a slow report)
                // can see at a glance whether the runtime is hitting expected rates. We
                // INFO when present; WARN never (the model could legitimately be slow on a
                // weak device — the user knows their hardware better than we do).
                val perfMap = prefs.perfTelemetryFlow(me.rerere.locallm.LocalRuntime.LiteRT).first()
                if (perfMap.isNotEmpty()) {
                    val rows = perfMap.values.sortedByDescending { it.sampledAtMs }
                    val detail = rows.joinToString("\n") { s ->
                        val spec = if (s.specDecodingEngaged) ", MTP on" else ""
                        "${s.modelId}: prefill ${"%.1f".format(s.prefillTps)} tok/s, " +
                            "decode ${"%.1f".format(s.decodeTps)} tok/s$spec"
                    }
                    add(
                        DoctorCheck(
                            id = "net.litert_perf",
                            category = DoctorCategory.Network,
                            label = "LiteRT 性能",
                            detail = "各模型最近一次的速度（基于字符的估算，" +
                                "英文文本约 10% 准确率）：\n$detail",
                            severity = Severity.INFO,
                            fix = FixAction.OpenAppRoute(
                                "打开本地 LiteRT",
                                AppRouteKey.SettingProvider,
                            ),
                        )
                    )
                }
                // Vision-encoder availability — surface any models the runtime had to drop
                // to text-only on this device's GPU. The provider's vision-CPU fallback
                // means a multimodal model still works for chat, but the user has lost
                // image input on this chip. Most common cause: Adreno 7xx + restrictive
                // OEM linker namespace (One UI / OriginOS) hitting upstream LiteRT-LM
                // issue #2292 (gpu_backend_opengl.cc:CreateSharedMemoryManager UNIMPLEMENTED).
                val visionUnavailable = prefs
                    .visionUnavailableFlow(me.rerere.locallm.LocalRuntime.LiteRT).first()
                if (visionUnavailable.isNotEmpty()) {
                    add(
                        DoctorCheck(
                            id = "net.litert_vision",
                            category = DoctorCategory.Network,
                            label = "LiteRT 视觉编码器",
                            detail = "此设备上无法使用视觉编码器的模型：" +
                                visionUnavailable.joinToString(", ") +
                                "。这些多模态模型以纯文本模式运行——聊天可用，" +
                                "图像输入不可用。通常可由未来的 LiteRT-LM SDK 更新修复" +
                                "（OpenGL 回退路径的 CreateSharedMemoryManager " +
                                "在上游尚未实现）。点击模型旁边的“重试视觉”" +
                                "（在 GPU 驱动更新后，于设置 → 本地 LiteRT 中）" +
                                "以清除该标记。",
                            severity = Severity.WARN,
                            fix = FixAction.OpenAppRoute(
                                "打开本地 LiteRT",
                                AppRouteKey.SettingProvider,
                            ),
                        )
                    )
                }
            }
        }
        // llama.cpp installed-model check. Unlike LiteRT, this runtime is CPU-only with no
        // vision encoder and nothing to probe for an accelerator, so there is no analogue
        // to net.litert_accel/_perf/_vision here — those would be reporting on things that
        // cannot vary on this build. The one thing that genuinely can go wrong: a model
        // registered in prefs whose backing file was moved, deleted, or lives on a volume
        // that got unmounted. Own id (net.llamacpp_models) so it can't collide with the
        // net.litert_* rows above.
        runCatching {
            val prefs = localRuntimePreferences
            if (prefs != null) {
                val installed = prefs.installedModels(me.rerere.locallm.LocalRuntime.LlamaCpp)
                val status = llamaCppModelStatus(installed)
                val detail = when {
                    status.total == 0 -> "未安装 llama.cpp 模型。"
                    status.missing.isEmpty() ->
                        "已安装 ${status.total} 个模型，磁盘上全部存在。"
                    else ->
                        "${status.missing.size} / ${status.total} 个已安装的 llama.cpp 模型" +
                            "在磁盘上缺失：${status.missing.joinToString(", ")}。文件可能" +
                            "已被移动、删除，或其存储卷已卸载。"
                }
                add(
                    DoctorCheck(
                        id = "net.llamacpp_models",
                        category = DoctorCategory.Network,
                        label = "llama.cpp 模型",
                        detail = detail,
                        severity = when {
                            status.total == 0 -> Severity.INFO
                            status.missing.isEmpty() -> Severity.OK
                            else -> Severity.WARN
                        },
                        fix = if (status.missing.isNotEmpty()) FixAction.OpenAppRoute(
                            "打开本地 llama.cpp",
                            AppRouteKey.SettingProvider,
                        ) else null,
                    )
                )
            }
        }
        // DNS sanity — confirms the OkHttp clients aren't stuck on a stale resolver.
        val dnsOk = withTimeoutOrNull(2_500L) {
            runCatching { InetAddress.getByName("dns.google") != null }.getOrDefault(false)
        } == true
        add(
            DoctorCheck(
                id = "net.dns",
                category = DoctorCategory.Network,
                label = "DNS 解析",
                detail = if (dnsOk) "dns.google 在 2.5 秒内解析成功。"
                else "DNS 解析失败或超时。NetworkChangeMonitor 会在网络变化时清除 OkHttp 连接池——如果此项持续红色，请检查网络连接。",
                severity = if (dnsOk) Severity.OK else Severity.WARN,
            )
        )
    }

    // ----- Termux ----------------------------------------------------------------------

    private fun termuxChecks(enabled: Set<LocalToolOption>): List<DoctorCheck> = buildList {
        val needers = requirersOf(Capability.Termux, enabled)
        // Skip the entire category when no Termux-using tool is enabled — keeps the
        // Doctor screen focused on what the user actually configured.
        if (needers.isEmpty()) return@buildList

        val pm = context.packageManager
        val termuxInstalled = runCatching { pm.getPackageInfo("com.termux", 0); true }.getOrDefault(false)
        add(
            DoctorCheck(
                id = "termux.installed",
                category = DoctorCategory.Termux,
                label = "已安装 Termux",
                detail = if (termuxInstalled) "此设备已安装 com.termux。"
                else "未安装 Termux。需要方：${needers.joinToString(", ") { it.shortName() }}。",
                severity = if (termuxInstalled) Severity.OK else Severity.WARN,
            )
        )
        if (termuxInstalled) {
            val runCommandPerm = runCatching {
                val perm = "com.termux.permission.RUN_COMMAND"
                context.checkSelfPermission(perm) == android.content.pm.PackageManager.PERMISSION_GRANTED
            }.getOrDefault(false)
            add(
                DoctorCheck(
                    id = "termux.run_command",
                    category = DoctorCategory.Termux,
                    label = "Termux RUN_COMMAND 权限",
                    detail = if (runCommandPerm) "已授予——RikkaHub 可以向 Termux 发送 shell 命令。"
                    else "未授予。请在本地工具中重新开关 Termux 选项以查看授权后对话框。",
                    severity = if (runCommandPerm) Severity.OK else Severity.WARN,
                )
            )
        }
    }

    // ----- Browser (Pass 3) ------------------------------------------------------------

    /**
     * Pass 3: Doctor rows for the in-app browser feature.
     *  - `browser.profile_dir_writable` — the WebView profile lives at
     *    `${filesDir}/browser-profile/`. The directory MUST exist + be writable for cookies
     *    to persist across app restarts. AutoFix re-creates it on demand.
     *  - `browser.write_tools_status` — informational live count of which write-tools the
     *    user has switched on. Lets a user spot-check at a glance whether `browser_type`
     *    is unintentionally enabled. INFO severity, no fix action.
     *
     * The category is [DoctorCategory.Permissions] per the spec ("Permissions / Services").
     * Both rows are emitted regardless of master Browser-toggle state, but their severity
     * downgrades to INFO when no assistant has [LocalToolOption.Browser] enabled (matches
     * the existing capability-aware pattern used throughout the file).
     */
    private fun browserChecks(enabled: Set<LocalToolOption>): List<DoctorCheck> = buildList {
        val needers = requirersOf(Capability.Browser, enabled)
        val browserNeeded = needers.isNotEmpty()

        // Row 1: profile dir writable (with AutoFix to mkdirs).
        val profileDir = File(context.filesDir, "browser-profile")
        val exists = runCatching { profileDir.exists() && profileDir.isDirectory }.getOrDefault(false)
        val writable = exists && runCatching { profileDir.canWrite() }.getOrDefault(false)
        val ok = exists && writable
        add(
            DoctorCheck(
                id = "browser.profile_dir_writable",
                category = DoctorCategory.Permissions,
                label = "浏览器配置目录",
                detail = when {
                    ok && browserNeeded -> "${profileDir.absolutePath} 存在且可写——Cookie 可持久保存。"
                    ok -> "${profileDir.absolutePath} 存在。没有启用的工具需要它。"
                    !exists && browserNeeded -> "目录不存在。Cookie 和 localStorage 将无法持久保存。需要方：浏览器。"
                    !exists -> "目录不存在。没有启用的工具需要它。"
                    !writable && browserNeeded -> "目录存在但不可写。需要方：浏览器。"
                    else -> "目录存在但不可写。"
                },
                severity = when {
                    ok -> Severity.OK
                    browserNeeded -> Severity.WARN
                    else -> Severity.INFO
                },
                fix = if (!ok && browserNeeded) FixAction.AutoFix(
                    label = "创建目录",
                    run = {
                        val created = runCatching { profileDir.mkdirs() }.getOrDefault(false)
                        val nowOk = profileDir.exists() && profileDir.canWrite()
                        AutoFixResult(
                            ok = nowOk,
                            message = if (nowOk) "已创建 ${profileDir.absolutePath}。"
                            else if (created) "目录已创建但仍不可写——请检查存储权限。"
                            else "mkdirs() 返回 false；底层存储可能为只读。",
                        )
                    },
                ) else null,
            )
        )

        // Row 2: write-tools live count (INFO only). Skipped silently if BrowserPreferences
        // wasn't injected — the row is purely informational and the test harness paths
        // that don't construct prefs shouldn't fail.
        val prefs = browserPreferences
        if (prefs != null) {
            val snapshot = runCatching { prefs.snapshotBlocking() }.getOrDefault(BrowserToolDefaults.DEFAULT_ENABLED)
            val onWriteTools = BrowserToolDefaults.WRITE_TOOLS.filter { snapshot[it] == true }
            val detail = if (onWriteTools.isEmpty())
                "当前已启用的有副作用浏览器工具数：0。所有写入类工具均未开启。"
            else
                "当前已启用的有副作用浏览器工具数：${onWriteTools.size}（${onWriteTools.joinToString(", ") { it.removePrefix("browser_") }}）。"
            add(
                DoctorCheck(
                    id = "browser.write_tools_status",
                    category = DoctorCategory.Permissions,
                    label = "浏览器写入工具已启用",
                    detail = detail,
                    severity = Severity.INFO,
                )
            )
        }
    }

    // ----- Maintenance -----------------------------------------------------------------

    private fun maintenanceChecks(): List<DoctorCheck> = buildList {
        // Cache size on disk
        val cacheBytes = directorySize(context.cacheDir)
        add(
            DoctorCheck(
                id = "maint.cache_size",
                category = DoctorCategory.Maintenance,
                label = "应用缓存大小",
                detail = "缓存占用 ${humanBytes(cacheBytes)}。 " +
                    if (cacheBytes > 200L * 1024 * 1024) "建议清理——已超过 200 MB。" else "处于正常范围。",
                severity = if (cacheBytes > 500L * 1024 * 1024) Severity.WARN else Severity.OK,
                fix = FixAction.AutoFix(
                    label = "清理缓存",
                    run = {
                        val freed = clearDirectoryContents(context.cacheDir)
                        AutoFixResult(ok = true, message = "已释放 ${humanBytes(freed)}。")
                    },
                ),
            )
        )
    }

    // ----- Diagnostics summary ---------------------------------------------------------

    private fun diagnosticsChecks(enabled: Set<LocalToolOption>): List<DoctorCheck> = listOf(
        DoctorCheck(
            id = "diag.app",
            category = DoctorCategory.Diagnostics,
            label = "应用构建",
            detail = "RikkaHub-agent ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) — debug=${BuildConfig.DEBUG}",
            severity = Severity.INFO,
        ),
        DoctorCheck(
            id = "diag.android",
            category = DoctorCategory.Diagnostics,
            label = "Android",
            detail = "API ${Build.VERSION.SDK_INT} (${Build.VERSION.RELEASE}) on ${Build.MANUFACTURER} ${Build.MODEL}",
            severity = Severity.INFO,
        ),
        DoctorCheck(
            id = "diag.runtime",
            category = DoctorCategory.Diagnostics,
            label = "运行时",
            detail = run {
                val rt = Runtime.getRuntime()
                val freeMb = rt.freeMemory() / (1024 * 1024)
                val totalMb = rt.totalMemory() / (1024 * 1024)
                val maxMb = rt.maxMemory() / (1024 * 1024)
                "堆内存：可用 $freeMb MB / 共 $totalMb MB（上限 $maxMb MB）"
            },
            severity = Severity.INFO,
        ),
        DoctorCheck(
            id = "diag.enabled_tools",
            category = DoctorCategory.Diagnostics,
            label = "各助手启用的工具",
            detail = if (enabled.isEmpty()) "未启用任何本地工具——智能体功能将无法工作。"
            else "已启用 ${enabled.size} 个工具组。",
            severity = if (enabled.isEmpty()) Severity.WARN else Severity.INFO,
        ),
    )

    private fun directorySize(dir: File): Long = runCatching {
        if (!dir.exists()) return@runCatching 0L
        dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
    }.getOrDefault(0L)

    private fun clearDirectoryContents(dir: File): Long {
        var freed = 0L
        runCatching {
            dir.listFiles()?.forEach { f ->
                freed += directorySize(f)
                f.deleteRecursively()
            }
        }
        return freed
    }

    private fun humanBytes(bytes: Long): String {
        val mb = 1024.0 * 1024
        val gb = mb * 1024
        return when {
            bytes < mb -> "%.0f KB".format(bytes / 1024.0)
            bytes < gb -> "%.1f MB".format(bytes / mb)
            else -> "%.2f GB".format(bytes / gb)
        }
    }
}

/**
 * Pure decision logic backing the "net.llamacpp_models" row: given the filename ->
 * absolute-path map from [me.rerere.locallm.LocalRuntimePreferences.installedModels],
 * report the total installed count and which filenames' backing file is no longer on
 * disk. Extracted to a top-level function (rather than left inline) so it's unit-testable
 * on the JVM without an Android Context — [DoctorChecks] itself needs one for every other
 * check, which rules out constructing it directly in a plain JUnit test.
 */
internal data class LlamaCppModelStatus(val total: Int, val missing: List<String>)

internal fun llamaCppModelStatus(installed: Map<String, String>): LlamaCppModelStatus =
    LlamaCppModelStatus(
        total = installed.size,
        missing = installed.filterValues { path -> !File(path).exists() }.keys.sorted(),
    )
