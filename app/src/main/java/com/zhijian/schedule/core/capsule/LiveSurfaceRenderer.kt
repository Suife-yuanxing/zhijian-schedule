package com.zhijian.schedule.core.capsule

import android.app.Notification
import android.content.Context
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Bundle
import androidx.annotation.RequiresApi
import java.util.Locale

/**
 * 一家厂商 / 一个系统能力的实时活动渲染器。
 *
 * 设计约束（来自官方文档，2026-10 核实）：
 * 1. **原生提升永远是第一优先级**，厂商私有参数是叠加而非替换；
 * 2. 私有参数注入失败**绝不能**影响通知本身发出，因此调用方必须整体 runCatching；
 * 3. 任何渲染器都不得使用自定义布局（RemoteViews）——原生提升一票否决，
 *    Apple / 华为 / vivo 同样禁止自定义整体布局，OPPO 宽卡片不能平移过来。
 */
interface LiveSurfaceRenderer {
    val surface: LiveSurface

    /** 是否在本机启用（厂商 + 系统版本双判断） */
    fun isApplicable(context: Context): Boolean

    /** Builder 阶段叠加：原生样式、状态 chip、提升请求等 */
    fun decorateBuilder(
        context: Context,
        builder: Notification.Builder,
        content: LiveContent,
    ) = Unit

    /** build 之后叠加：厂商 extras 协议（小米 miui.focus.param 等） */
    fun decorateNotification(
        context: Context,
        notification: Notification,
        content: LiveContent,
    ) = Unit
}

/**
 * Android 16 Live Updates 原生渲染器（跨厂商地基）。
 *
 * 官方提升的硬性条件，缺一项就**静默不提升**：
 * - 样式为默认 / BigTextStyle / CallStyle / ProgressStyle 之一；
 * - manifest 声明 `POST_PROMOTED_NOTIFICATIONS`（非运行时权限，本项目已声明）；
 * - 通过 `EXTRA_REQUEST_PROMOTED_ONGOING` 请求提升；
 * - 必须 ongoing、必须设 contentTitle；
 * - **不得**自定义布局（RemoteViews）、**不得**分组摘要、**不得** `setColorized(true)`；
 * - 渠道**不得** IMPORTANCE_MIN。
 *
 * 注意 `setColor` 与 `setColorized` 是两回事：前者给通知元素着色，
 * 后者把整条通知彩色化（这次才是一票否决项）。本项目两者都不用于实时卡片，
 * 课程颜色只通过进度段表达。
 */
object AospPromotedRenderer : LiveSurfaceRenderer {
    override val surface = LiveSurface.AOSP_PROMOTED

    /** 原生提升对所有设备都适用（低版本只是不提升，通知照常） */
    override fun isApplicable(context: Context): Boolean = true

    override fun decorateBuilder(
        context: Context,
        builder: Notification.Builder,
        content: LiveContent,
    ) {
        // API 36 以下没有 chip，也没有 ProgressStyle，进度由调用方的 setProgress 兜底
        if (Build.VERSION.SDK_INT < ANDROID_16) return

        if (content.chip.isNotEmpty()) {
            builder.setShortCriticalText(content.chip)
        }
        // 显式请求提升。API 36.1 才有 setRequestPromotedOngoing()，
        // compileSdk 36 下只能按字符串 key 写——这也是官方文档给出的兼容写法。
        builder.addExtras(
            Bundle().apply { putBoolean(EXTRA_REQUEST_PROMOTED_ONGOING, true) },
        )
    }

    /** API 36 正式 API 的 extra 键 */
    private const val EXTRA_REQUEST_PROMOTED_ONGOING = "android.requestPromotedOngoing"
    private const val ANDROID_16 = 36
}

/**
 * 小米 HyperOS 超级岛（OS3）/ 焦点通知（OS2）。
 *
 * **无需 SDK、无需额外权限、无需换服务**：在原生通知上挂 `miui.focus.param` 扩展参数即可，
 * 不支持该协议的设备会直接忽略。这是目前性价比最高的第二家。
 *
 * 协议要点（官方《小米超级岛开发指南》+ 模板接入示例）：
 * - 根节点 `param_v2`，内含 `business` / `enableFloat` / `updatable` / `ticker` /
 *   `aodTitle` / `param_island` / `baseInfo` / `hintInfo`；
 * - `param_island` 内 `islandProperty`（1 信息展示为主 / 2 操作为主）、
 *   `bigIslandArea` 与 `smallIslandArea` **均必填**、`islandTimeout` 单位秒（默认 3600）、
 *   `highlightColor` 文字强调色；
 * - 图片不内联，走 `miui.focus.pics` Bundle（key → Icon），JSON 里只写 key；
 * - `timeout` 单位**分钟**（通知卡片，默认 720），`islandTimeout` 单位**秒**（岛）；
 * - `miui.focus.param` 整串 **≤ 3072 字节**，超了必须降级成最小参数。
 */
object HyperOsIslandRenderer : LiveSurfaceRenderer {
    override val surface = LiveSurface.HYPEROS_ISLAND

    override fun isApplicable(context: Context): Boolean = LiveSurfaceSupport.supportsHyperOsIsland(context)

    override fun decorateNotification(
        context: Context,
        notification: Notification,
        content: LiveContent,
    ) {
        // 图标走 pics Bundle：JSON 里只引用 key，避免把二进制塞进参数字符串
        notification.extras.putBundle(
            PIC_BUNDLE_KEY,
            Bundle().apply {
                putParcelable(PIC_ISLAND, Icon.createWithResource(context, content.iconRes))
            },
        )
        notification.extras.putString(PARAM_KEY, buildParam(content))
    }

    /** 纯参数构建（不依赖 Context），供 JVM 单测直接校验协议结构 */
    internal fun paramForTest(content: LiveContent): String = buildParam(content)

    /** 岛停留时长计算，供单测校验上下限夹取 */
    internal fun islandTimeoutSecForTest(content: LiveContent): Long = islandTimeoutSec(content)

    private fun islandTimeoutSec(content: LiveContent): Long {
        val remain =
            content.countdownToMillis
                ?.let { (it - System.currentTimeMillis()) / MILLIS_PER_SECOND }
                ?: MAX_ISLAND_SEC
        return remain.coerceIn(MIN_ISLAND_SEC, MAX_ISLAND_SEC)
    }

    private fun buildParam(content: LiveContent): String {
        val highlight = hexColor(content.progressColor)
        // chip 只有极窄区域，压到 6 个字符以内（官方建议大字 ≤4 中文字）
        val shortChip = clip(content.chip, CHIP_MAX_CHARS)
        val title = clip(content.title, TITLE_MAX_CHARS)
        val body = clip(content.subtitle ?: content.detail, BODY_MAX_CHARS)

        val json = StringBuilder(JSON_BUFFER)
        json.append("{\"param_v2\":{")
        // ---- 交互能力 / 通知属性 ----
        json.append("\"protocol\":").append(PROTOCOL_VERSION).append(',')
        json.append("\"business\":\"").append(BUSINESS_COURSE).append("\",")
        json.append("\"enableFloat\":false,\"updatable\":true,")
        json.append("\"ticker\":\"").append(esc(shortChip)).append("\",")
        json.append("\"aodTitle\":\"").append(esc(shortChip)).append("\",")
        json.append("\"timeout\":").append(NOTIFICATION_TIMEOUT_MIN).append(',')
        // ---- 岛数据 ----
        json.append("\"param_island\":{")
        json.append("\"islandProperty\":").append(ISLAND_PROPERTY_INFO).append(',')
        json.append("\"islandTimeout\":").append(islandTimeoutSec(content)).append(',')
        json.append("\"highlightColor\":\"").append(highlight).append("\",")
        json.append("\"bigIslandArea\":{\"imageTextInfoLeft\":{")
        json.append("\"type\":").append(AREA_TYPE_IMAGE_TEXT).append(',')
        json.append("\"picInfo\":{\"type\":").append(PIC_TYPE_RES).append(",\"pic\":\"")
        json.append(PIC_ISLAND).append("\"},")
        json.append("\"textInfo\":{\"title\":\"").append(esc(title)).append("\",")
        json.append("\"content\":\"").append(esc(body)).append("\",")
        json.append("\"useHighLight\":true}},")
        json.append("\"textInfoRight\":{\"type\":").append(AREA_TYPE_TEXT).append(",\"textInfo\":{")
        json.append("\"title\":\"").append(esc(shortChip)).append("\"}}},")
        // 小岛：只有一个图标位
        json.append("\"smallIslandArea\":{\"picInfo\":{\"type\":").append(PIC_TYPE_RES)
        json.append(",\"pic\":\"").append(PIC_ISLAND).append("\"}}},")
        // ---- 焦点通知数据（岛展开态与焦点通知共用） ----
        json.append("\"baseInfo\":{\"type\":").append(BASE_INFO_TYPE).append(',')
        json.append("\"title\":\"").append(esc(title)).append("\",")
        json.append("\"content\":\"").append(esc(clip(content.detail ?: content.subtitle, BASE_MAX_CHARS)))
        json.append("\",\"colorTitle\":\"").append(highlight).append("\"}")
        json.append("}}")

        val raw = json.toString()
        // 协议上限 3072 字节：超了退化成只带标题的最小参数，宁可不完整也不要发不出去
        return if (raw.toByteArray(Charsets.UTF_8).size <= MAX_PARAM_BYTES) {
            raw
        } else {
            minimalParam(shortChip, title, highlight)
        }
    }

    private fun minimalParam(
        shortChip: String,
        title: String,
        highlight: String,
    ): String =
        "{\"param_v2\":{\"protocol\":" + PROTOCOL_VERSION +
            ",\"business\":\"" + BUSINESS_COURSE + "\",\"updatable\":true" +
            ",\"ticker\":\"" + esc(shortChip) + "\"" +
            ",\"param_island\":{\"islandProperty\":" + ISLAND_PROPERTY_INFO +
            ",\"bigIslandArea\":{\"imageTextInfoLeft\":{\"type\":" + AREA_TYPE_IMAGE_TEXT +
            ",\"picInfo\":{\"type\":" + PIC_TYPE_RES + ",\"pic\":\"" + PIC_ISLAND + "\"}" +
            ",\"textInfo\":{\"title\":\"" + esc(title) + "\"}}}," +
            "\"smallIslandArea\":{\"picInfo\":{\"type\":" + PIC_TYPE_RES +
            ",\"pic\":\"" + PIC_ISLAND + "\"}}}," +
            "\"baseInfo\":{\"type\":" + BASE_INFO_TYPE + ",\"title\":\"" + esc(title) +
            "\",\"colorTitle\":\"" + highlight + "\"}}}"

    /** JSON 字符串转义：只处理会破坏结构的字符，中文原样保留（协议是 UTF-8） */
    private fun esc(value: String?): String = (value ?: "").replace("\\", "\\\\").replace("\"", "\\\"")

    /** 按字符裁剪，避免区域溢出 */
    private fun clip(
        value: String?,
        max: Int,
    ): String {
        val text = value?.trim().orEmpty()
        return if (text.length <= max) text else text.take(max - 1) + "…"
    }

    private fun hexColor(color: Int): String = String.format(Locale.ROOT, "#%06X", MASK_RGB and color)

    private const val PARAM_KEY = "miui.focus.param"
    private const val PIC_BUNDLE_KEY = "miui.focus.pics"
    private const val PIC_ISLAND = "miui.focus.pic_island"
    private const val BUSINESS_COURSE = "course"
    private const val PROTOCOL_VERSION = 1
    private const val ISLAND_PROPERTY_INFO = 1
    private const val AREA_TYPE_IMAGE_TEXT = 1
    private const val AREA_TYPE_TEXT = 2
    private const val PIC_TYPE_RES = 1
    private const val BASE_INFO_TYPE = 2
    private const val MAX_PARAM_BYTES = 3072
    private const val MIN_ISLAND_SEC = 30L
    private const val MAX_ISLAND_SEC = 4L * 60 * 60
    private const val NOTIFICATION_TIMEOUT_MIN = 720
    private const val MILLIS_PER_SECOND = 1000L
    private const val CHIP_MAX_CHARS = 6
    private const val TITLE_MAX_CHARS = 16
    private const val BODY_MAX_CHARS = 24
    private const val BASE_MAX_CHARS = 32
    private const val JSON_BUFFER = 512
    private const val MASK_RGB = 0xFFFFFF
}

/**
 * 渲染器注册表。顺序即优先级：原生提升永远第一。
 *
 * 之所以把「原生」与「私有」放在同一个循环里，是因为它们共享同一份 [LiveContent]：
 * 一次内容投影、多路渲染，避免各厂商分支各自再算一遍文案导致口径漂移。
 */
object LiveSurfaceRenderers {
    private val all: List<LiveSurfaceRenderer> =
        listOf(
            AospPromotedRenderer,
            HyperOsIslandRenderer,
        )

    /** Builder 阶段：只跑原生（厂商私有协议都在 build 之后挂 extras） */
    @RequiresApi(36)
    fun decorateBuilder(
        context: Context,
        builder: Notification.Builder,
        content: LiveContent,
    ) {
        all
            .asSequence()
            .filter { it.isApplicable(context) }
            // 单家失败不影响其他家，更不影响通知本身
            .forEach { renderer -> runCatching { renderer.decorateBuilder(context, builder, content) } }
    }

    /** build 之后：挂各厂商的扩展参数 */
    fun decorateNotification(
        context: Context,
        notification: Notification,
        content: LiveContent,
    ) {
        all
            .asSequence()
            .filter { it.isApplicable(context) }
            .forEach { renderer ->
                runCatching { renderer.decorateNotification(context, notification, content) }
            }
    }
}
