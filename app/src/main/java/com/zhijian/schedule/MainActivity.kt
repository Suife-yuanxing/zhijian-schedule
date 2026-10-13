package com.zhijian.schedule

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import com.zhijian.schedule.capsule.LiveCapsuleService
import com.zhijian.schedule.core.data.prefs.SettingsStore
import com.zhijian.schedule.navigation.AppRoot
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject
    lateinit var settingsStore: SettingsStore

    /** 点实时胶囊进「今日」页的请求计数，AppRoot 观察它切一次 tab */
    private var openTodayCount by mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        requestNotificationPermissionIfNeeded()
        reviveCapsuleIfEnabled()
        // 冷启动带进来的请求直接消费；重建（如旋转）不重复消费
        if (savedInstanceState == null) consumeOpenToday(intent)
        setContent {
            AppRoot(openTodayCount = openTodayCount)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        consumeOpenToday(intent)
    }

    private fun consumeOpenToday(intent: Intent?) {
        if (intent?.getBooleanExtra(EXTRA_OPEN_TODAY, false) == true) {
            openTodayCount++
        }
    }

    /** 打开 App 时若「实时胶囊」开着但服务不在（如息屏被清理），顺手拉起 */
    private fun reviveCapsuleIfEnabled() {
        lifecycleScope.launch {
            if (settingsStore.settings.first().capsuleEnabled) {
                LiveCapsuleService.start(this@MainActivity)
            }
        }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1001)
        }
    }

    companion object {
        /** 实时胶囊点击的 PendingIntent 携带；据此跳到「今日」tab */
        const val EXTRA_OPEN_TODAY = "com.zhijian.schedule.OPEN_TODAY"
    }
}
