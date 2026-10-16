package com.zhijian.schedule.core.jw

import com.zhijian.schedule.core.data.db.GradeEntity

/** 成绩在「抓取模型」与「持久化实体」之间的映射（成绩页与自动同步共用） */

fun GradeRecord.toEntity() = GradeEntity(
    term = term, code = code, name = name, score = score, scoreFlag = scoreFlag,
    credit = credit, hours = hours, gpa = gpa, retakeTerm = retakeTerm,
    examMode = examMode, examNature = examNature, attr = attr, category = category,
)

fun GradeEntity.toRecord() = GradeRecord(
    term = term, code = code, name = name, score = score, scoreFlag = scoreFlag,
    credit = credit, hours = hours, gpa = gpa, retakeTerm = retakeTerm,
    examMode = examMode, examNature = examNature, attr = attr, category = category,
)
