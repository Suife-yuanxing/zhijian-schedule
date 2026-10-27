package com.zhijian.schedule.core.capsule

import android.content.Context
import android.os.Build
import android.provider.Settings

/**
 * 实时活动内容的**厂商无关**投影：由 [CapsulePlan] 派生，供各厂商渲染器消费。
 *
 * 之所以单独抽出来，是因为各家字段差异极大（原生 ProgressStyle / 小米超级岛 JSON /
 * vivo notification.superx.* / 华为 notification.live.*），但语义是同一套：
 * 一个图标 + 一个短状态 + 一个标题 + 一条辅助信息 + 可选的进度与倒计时。
 * 厂商差异只允许发生在渲染层，状态机 [CapsulePlan] 保持纯数据。
 */
data class LiveContent(
    /** 服务图标资源（小图标） */
    val iconRes: Int,
    /** 状态栏胶囊 / 状态 chip 用的极短文本，如「剩 32 分」。各家都只给很小一块区域 */
    val chip: String,
    /** 标题，如「正在上课 · 大学英语」 */
    val title: String,
    /** 副标题，如「第7-8节 · 教305 · 14:00-15:40 · 张老师」 */
    val subtitle: String?,
    /** 辅助信息，如「15:40 下课 · 还剩 32 分钟」 */
    val detail: String?,
    /** 进度 0-100（倒着消耗）；null 表示当前状态没有进度语义 */
    val progress: Int?,
    /** 进度条颜色（课程色），仅用于进度段，不得用于通知整体着色 */
    val progressColor: Int,
    /** 倒计时目标时刻（epoch 毫秒）；null 表示无倒计时 */
    val countdownToMillis: Long?,
    /** 本次内容指纹（不含厂商维度），用于去重（分钟级变化） */
    val key: String,
    /**
     * 本次实时活动的「场次」标识（一节课 / 一个课前倒数窗口），粒度远粗于 [key]。
     * 用于「用户划掉卡片后本场次不再重发」的记账，以及各厂商实例 id 的复用。
     */
    val sessionKey: String?,
    /** 是否处于实时态（决定是否值得请求系统提升） */
    val live: Boolean,
)

/**
 * 实时活动的投放面（厂商 / 系统能力）。
 *
 * - [AOSP_PROMOTED]：Android 16 Live Updates 原生提升。ColorOS 16 会把它渲染成流体云，
 *   MagicOS 走同一套安卓实时通知规范，属于**跨厂商地基**。
 * - 其余为本喵识别的私有承载面，需要各自的扩展参数，且大多需要厂商审核。
 */
enum class LiveSurface {
    AOSP_PROMOTED,
    COLOROS_FLUID_CLOUD,
    HYPEROS_ISLAND,
    MAGICOS_CAPSULE,
    ORIGINOS_ATOM,
    HARMONY_LIVE_VIEW,
}

/**
 * 系统能力探测 + 厂商识别。
 *
 * 关键事实（官方文档口径，2026-10 核实）：
 * - `Notification.ProgressStyle` / `setShortCriticalText` / `canPostPromotedNotifications`
 *   / `hasPromotableCharacteristics` 都在 **API 36** 引入；
 * - 但**提升形态本身**（promoted treatment）需要 **Android 16 QPR1（API 36.1）**，
 *   在 API 36.0 上通知仍会就地更新，只是不会被提升成胶囊。
 * 因此「系统版本够不够」与「能不能请求提升」是两件事，必须分开判断。
 */
object LiveSurfaceSupport {
    /** 厂商标识关键字，按「谁先匹配谁是」的顺序排列 */
    private val XIAOMI_KEYS = listOf("xiaomi", "redmi", "poco")
    private val HONOR_KEYS = listOf("honor")
    private val VIVO_KEYS = listOf("vivo", "iqoo")
    private val OPPO_KEYS = listOf("oppo", "oneplus", "realme")
    private val HUAWEI_KEYS = listOf("huawei")

    /** 系统是否支持查询提升资格；null = 该系统不支持查询（低于 API 36） */
    fun canPromote(context: Context): Boolean? =
        if (Build.VERSION.SDK_INT >= ANDROID_16) {
            runCatching {
                context
                    .getSystemService(android.app.NotificationManager::class.java)
                    ?.canPostPromotedNotifications()
            }.getOrNull()
        } else {
            null
        }

    /**
     * 是否达到 promoted 形态的实际门槛。API 36.0 能发 ProgressStyle 但不会被提升，
     * 设置页文案必须按这个区分，否则用户会把「胶囊不出现」当成 bug。
     */
    fun supportsPromotedTreatment(): Boolean = Build.VERSION.SDK_INT >= ANDROID_16_1

    /** 厂商识别。只用于决定「要不要注入私有扩展参数」，绝不影响原生提升路径 */
    fun vendor(): LiveSurface {
        val maker = (Build.MANUFACTURER ?: "").lowercase()
        val brand = (Build.BRAND ?: "").lowercase()
        val keys =
            setOf(
                maker.split(WHITESPACE).firstOrNull() ?: "",
                brand.split(WHITESPACE).firstOrNull() ?: "",
            )
        return when {
            keys.matchesAny(XIAOMI_KEYS) -> LiveSurface.HYPEROS_ISLAND
            keys.matchesAny(HONOR_KEYS) -> LiveSurface.MAGICOS_CAPSULE
            keys.matchesAny(VIVO_KEYS) -> LiveSurface.ORIGINOS_ATOM
            keys.matchesAny(OPPO_KEYS) -> LiveSurface.COLOROS_FLUID_CLOUD
            keys.matchesAny(HUAWEI_KEYS) -> LiveSurface.HARMONY_LIVE_VIEW
            else -> LiveSurface.AOSP_PROMOTED
        }
    }

    /**
     * 小米焦点通知协议版本：1 = OS1，2 = OS2，3 = 超级岛（OS3）。
     * 读系统设置项，取不到按 0 处理（视为不支持私有岛）。
     */
    fun hyperOsFocusProtocol(context: Context): Int =
        runCatching {
            Settings.System.getInt(
                context.contentResolver,
                SETTING_FOCUS_PROTOCOL,
                FOCUS_PROTOCOL_NONE,
            )
        }.getOrDefault(FOCUS_PROTOCOL_NONE)

    /** 当前设备是否值得注入小米超级岛参数（OS3 才有岛） */
    fun supportsHyperOsIsland(context: Context): Boolean =
        vendor() == LiveSurface.HYPEROS_ISLAND &&
            hyperOsFocusProtocol(context) >= FOCUS_PROTOCOL_ISLAND

    private const val ANDROID_16 = 36
    private const val ANDROID_16_1 = 36.1
    private const val WHITESPACE = " "
    private const val SETTING_FOCUS_PROTOCOL = "notification_focus_protocol"
    private const val FOCUS_PROTOCOL_NONE = 0
    private const val FOCUS_PROTOCOL_ISLAND = 3
}

/** 厂商关键字集合是否命中任一 */
private fun Set<String>.matchesAny(keys: List<String>): Boolean =
    keys.any { key -> any { token -> token.contains(key) } }
