package com.zhijian.schedule.feature.update

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.zhijian.schedule.BuildConfig
import com.zhijian.schedule.core.update.UpdateChecker
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import javax.inject.Inject

/** 进入应用时自动检查更新：静默检查，仅当发现新版本时通知 UI 弹更新窗口 */
@HiltViewModel
class UpdatePromptViewModel
    @Inject
    constructor(
        private val updateChecker: UpdateChecker,
    ) : ViewModel() {
        /** 非空 = 有待更新版本，UI 弹出更新窗口 */
        var promptVersion by mutableStateOf<String?>(null)
            private set

        /**
         * 进入应用时自动检查更新。
         *
         * **debug 包直接跳过**：debug 版本号恒等于开发分支上的版本名（v1.0.15-debug），
         * 而线上 Release 已是同号或更高，于是每次冷启动都会弹「发现新版本 v1.0.15」——
         * 弹窗还会盖住「我的」页的入口卡，真机验收时每次都要多点一下「关闭」。
         * 这条检查对开发版没有任何意义（不会真的去装线上包），只挡路。
         *
         * 手动入口（「我的」→ 检查更新）不受影响，仍然可用。
         */
        fun checkOnLaunch() {
            if (BuildConfig.DEBUG) return
            viewModelScope.launch {
                // 检查失败（断网等）与已最新都不打扰用户
                val info = updateChecker.check().getOrNull() ?: return@launch
                promptVersion = info.version
            }
        }

        fun dismiss() {
            promptVersion = null
        }
    }
