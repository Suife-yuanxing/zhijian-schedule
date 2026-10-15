package com.zhijian.schedule.di

import javax.inject.Qualifier

/**
 * 标记「下载用」OkHttpClient：总时长不设上限、读写超时放宽、不共享 WebView cookie。
 * 与未限定的通用客户端（15s/20s，用于教务抓取与 API 请求）区分开。
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class DownloadClient
