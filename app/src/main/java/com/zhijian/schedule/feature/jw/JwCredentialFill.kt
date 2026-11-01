package com.zhijian.schedule.feature.jw

/**
 * 「这次页面加载要回填的凭据」。
 *
 * 只是把三个字符串打包过一遍 UI 层，没有任何生命周期：谁需要填、什么时候填由
 * [com.zhijian.schedule.feature.jw.JwImportViewModel] 决定，[WebViewBox] 只负责在
 * 页面加载完成的那一刻把它写进登录框。
 */
data class JwCredentialFill(
    /** 保存这条凭据时的页面 origin（`scheme://host`）；只有当前页 origin 与它一致才回填 */
    val origin: String,
    val username: String,
    val password: String,
)
