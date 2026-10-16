package com.zhijian.schedule.feature.update

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.zhijian.schedule.update.UpdateChecker
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.launch

/** 进入应用时自动检查更新：静默检查，仅当发现新版本时通知 UI 弹更新窗口 */
@HiltViewModel
class UpdatePromptViewModel @Inject constructor(
    private val updateChecker: UpdateChecker,
) : ViewModel() {

    /** 非空 = 有待更新版本，UI 弹出更新窗口 */
    var promptVersion by mutableStateOf<String?>(null)
        private set

    fun checkOnLaunch() {
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
