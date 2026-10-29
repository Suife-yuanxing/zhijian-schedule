package com.zhijian.schedule.core.notifications

import com.zhijian.schedule.core.repository.ScheduleRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/** 监听考试数据变化，全量重排考前提醒（增删改/导入后均生效） */
@Singleton
class ExamReminderCoordinator
    @Inject
    constructor(
        private val repository: ScheduleRepository,
        private val examAlarmScheduler: ExamAlarmScheduler,
    ) {
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        private var started = false

        @OptIn(FlowPreview::class)
        fun start() {
            if (started) return
            started = true
            scope.launch {
                repository.activeExams
                    .distinctUntilChanged()
                    .debounce(800)
                    .collect { exams -> examAlarmScheduler.rescheduleAll(exams) }
            }
        }

        /** 设备重启 / 应用更新后闹钟被系统清空，主动触发一次收集 */
        fun refresh() = start()
    }
