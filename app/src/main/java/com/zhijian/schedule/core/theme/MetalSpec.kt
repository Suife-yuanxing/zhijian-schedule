package com.zhijian.schedule.core.theme

import androidx.compose.ui.graphics.Color

/**
 * 水银镜面材质参数（仅「水银镜面」预设非空）。
 *
 * 与 [GlassSpec]（清水玻璃）是**两套物理模型**，不是同一套的换色：
 * 玻璃靠「透明 + 边缘折射」，金属靠「不透明 + 反射方向 + 各向异性高光」。
 * 所以参数表也不同——这里没有 bodyAlpha / lens，只有 chrome / brushed / mirror / filmNm。
 */
data class MetalSpec(
    /** 金属 F0 染色（亮/暗）：抛光面的本色，白=镜钢、暖=黄铜、冷=铬 */
    val tintLight: Color,
    val tintDark: Color,
    /** 抛光程度 0..1：0 = 近乎全哑光（只剩倒角一圈镜面），1 = 全镜面 */
    val chrome: Float,
    /** 哑光面底色（亮/暗）：卡面主体，通常接近页面底但抬一档 */
    val faceLight: Color,
    val faceDark: Color,
    /** 高光强度：拉丝线 + 触摸光斑 + 边缘刃光的总闸 */
    val specular: Float,
    /** 拉丝强度 0..1（0 = 光滑如镜，1 = 强拉丝） */
    val brushed: Float,
    /** 拉丝方向（弧度）：0 = 横向拉丝，π/2 = 纵向拉丝 */
    val brushAngle: Float,
    /** 虹彩强度：氧化膜/薄膜干涉，金属在掠射角泛出的彩边 */
    val iridescence: Float,
    /** 氧化膜厚度（nm）：决定虹彩偏哪一色系 */
    val filmNm: Float,
    /** 环境亮度：程序化摄影棚的曝光 */
    val environ: Float,
    /** 屏幕空间反射强度（仅浮岛等大件）：映出下方页面的程度 */
    val mirror: Float,
    /** 反射增益：屏幕反射的亮度补偿（暗底上要抬一点才看得见） */
    val reflectGain: Float,
    /** 颗粒强度：金属最怕「塑料光」，一点颗粒就把质感拉回来 */
    val grain: Float,
    /** 页面底：极暗房间的底色 */
    val floorLight: Color,
    val floorDark: Color,
    /** 房间里的两团光：冷光（天顶偏左）与暖光（右后侧） */
    val coolLight: Color,
    val coolDark: Color,
    val warmLight: Color,
    val warmDark: Color,
    /** 环境光强度 */
    val ambientBright: Float,
    /** 卡片浮起的投影浓度（暗房间里金属板与桌面之间的那层暗） */
    val shadowAlpha: Float,
    /** 刃光强度：倒角外缘那一道被灯扫亮的高光（金属读作"薄板"的关键线索） */
    val blade: Float = 1.0f,
    /** 辉光强度：高光溢出的柔光，让亮部有"光晕"而不是硬边 */
    val bloom: Float = 0.45f,
    /** 接触阴影强度：贴边处压暗，让人觉得这块金属真的压在桌面上 */
    val contact: Float = 0.65f,
)
