import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
    // 代码质量基建（2026-10 新增）
    alias(libs.plugins.ktlint)
    alias(libs.plugins.detekt)
}

android {
    namespace = "com.zhijian.schedule"
    compileSdk = 36

    // 正式签名：keystore.properties 存在时使用 release 密钥（仓库外保存），否则回落 debug 签名
    val keystoreProps = Properties().apply {
        val f = rootProject.file("keystore.properties")
        if (f.exists()) f.inputStream().use { load(it) }
    }

    defaultConfig {
        applicationId = "com.zhijian.schedule"
        minSdk = 26
        targetSdk = 36
        versionCode = 15
        versionName = "1.0.14"

        resourceConfigurations += listOf("zh", "en")
    }

    signingConfigs {
        if (keystoreProps.isNotEmpty()) {
            create("release") {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = if (keystoreProps.isNotEmpty()) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    testOptions {
        unitTests {
            // 状态机单测会碰 android.util.Log（诊断打点）：默认桩抛异常，这里让它返回默认值
            isReturnDefaultValues = true
        }
    }

    packaging {
        resources {
            excludes += setOf(
                "/META-INF/{AL2.0,LGPL2.1}",
                "/META-INF/DEPENDENCIES",
                "/META-INF/LICENSE*",
                "/META-INF/NOTICE*",
                "META-INF/*.kotlin_module"
            )
        }
    }

    lint {
        // lintVital 需从 dl.google.com 拉 lint-gradle，国内常连接超时导致 release 构建失败；
        // 构建期关闭，lint 可另行手动运行
        checkReleaseBuilds = false
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

tasks.withType<Test>().configureEach {
    // 项目路径含中文（D:\课程表）：确保测试 Worker 以 UTF-8 解析类路径
    jvmArgs("-Dfile.encoding=UTF-8", "-Dsun.jnu.encoding=UTF-8")
}

// =============================================================================
// 代码质量基建（2026-10 新增）：ktlint + detekt
//
// 设计要点：
//  · 本项目是单模块 Android 应用，插件直接 apply 在 :app 上；从仓库根执行
//    `./gradlew ktlintCheck detekt` 会命中本模块的同名任务。
//  · 规则强度不打折，存量违例全部冻结在 baseline 里（存量放行、增量拦截）：
//    新增或被修改的文件一旦违规，对应任务立即 fail。
//      - ktlint baseline：config/ktlint/baseline.xml  重新生成：./gradlew ktlintGenerateBaseline
//      - detekt baseline：config/detekt/baseline.xml  重新生成：./gradlew detektBaseline
//  · 规则全量与阈值：.editorconfig（ktlint）与 config/detekt/detekt.yml（detekt）。
// =============================================================================

ktlint {
    // 版本显式钉死，避免将来升级 Gradle 插件时 ktlint 规则集跟着漂移：
    // 插件升级与规则升级必须分成两次可 review 的改动（14.2.0 的默认值就是 1.5.0）。
    version.set("1.5.0")
    // 现有 180 个 .kt 的历史违例冻结在 baseline 中；新增代码违规会 fail。
    baseline.set(rootProject.file("config/ktlint/baseline.xml"))
    // 报告与 baseline 里的文件路径一律用"相对仓库根"的形式。
    // 这一行是跨机器可用的前提：本机仓库路径是 D:\课程表（含中文），
    // 若写绝对路径，baseline 在 CI（/home/runner/work/...）上必然整体失配，
    // 而且中文路径在不同编码下会让 baseline XML 直接解析失败。
    relative.set(true)
    outputToConsole.set(true)
    ignoreFailures.set(false)
    // 只检查手写源码：排除 build/ 下的 KSP / Compose 编译器 / Hilt 生成物
    filter {
        exclude("**/build/**")
        exclude("**/generated/**")
    }
}

detekt {
    // 以 detekt 官方默认规则集为底（版本随插件固定 = 1.23.8），
    // config/detekt/detekt.yml 只写"差异部分"（对 Compose/Android 的合理放宽），
    // 避免把 800 行官方默认整份入库导致规则变更无人可审。
    buildUponDefaultConfig = true
    parallel = true
    config.setFrom(rootProject.file("config/detekt/detekt.yml"))
    baseline = rootProject.file("config/detekt/baseline.xml")
    // 同上：baseline 里的路径相对仓库根，保证本地/CI 两份 baseline 语义一致。
    basePath = rootProject.projectDir.absolutePath
}

tasks.withType<io.gitlab.arturbosch.detekt.Detekt>().configureEach {
    // 与 ktlint 一致：不扫 build/ 下的生成物
    exclude("**/build/**")
    reports {
        html.required.set(true)
        xml.required.set(true)
        txt.required.set(false)
        sarif.required.set(false)
        md.required.set(false)
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)

    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.core)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(libs.androidx.datastore.preferences)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.androidx.hilt.navigation.compose)
    implementation(libs.androidx.hilt.work)
    ksp(libs.androidx.hilt.compiler)

    implementation(libs.androidx.glance.appwidget)
    implementation(libs.androidx.work.runtime.ktx)

    implementation(libs.kotlinx.serialization.json)
    implementation(libs.okhttp)
    implementation(libs.jsoup)
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)
    implementation(libs.zxing.core)
    implementation(libs.mlkit.barcode.scanning)
    implementation(libs.mlkit.text.recognition.chinese)

    testImplementation(libs.junit)
    testImplementation(libs.okhttp.mockwebserver)
}
