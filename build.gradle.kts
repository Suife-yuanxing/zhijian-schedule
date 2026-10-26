plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.hilt) apply false
    // 代码质量基建（2026-10 新增）：版本固定在版本目录，真正的 apply 在 :app。
    alias(libs.plugins.ktlint) apply false
    alias(libs.plugins.detekt) apply false
}
