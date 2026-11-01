package com.zhijian.schedule.feature.jw

import android.webkit.JavascriptInterface
import com.zhijian.schedule.core.jw.credential.JwCredentialScripts

/**
 * 凭据桥：唯一一条「页面 → 原生」的账号密码通道。
 *
 * ## 为什么单独一条桥
 * [com.zhijian.schedule.core.jw.script.JwScriptRunner.PaperJavascriptBridge] 是脚本导入的信封通道，
 * 语义是「一段脚本跑完了」。凭据承载的是用户输入的明文，混进脚本通道会让
 * 「脚本执行失败」和「密码没抓到」共用同一条错误路径；分开之后各自的失败互不牵连。
 *
 * ## 谁可能调用它
 * 这个对象注入在当前 WebView 的**所有**页面里，任何被加载的页面都能调用 [onCandidate]。
 * 因此收到的东西一律按不可信输入处理：[JwImportViewModel] 会先校验 origin 与用户配置的
 * 教务地址同源、再校验字段长度与字符集合，最后才落库。
 *
 * 回调跑在 WebView 的 JavaBridge 线程，切线程由 ViewModel 负责。
 */
internal class JwCredentialBridge(
    private val onCandidate: (String) -> Unit,
    private val onLoggedIn: () -> Unit,
) {
    @JavascriptInterface
    fun onCandidate(json: String?) {
        if (json.isNullOrBlank()) return
        onCandidate(json)
    }

    /** 页面自己判定「已经进了教务系统」：此刻暂存的账号密码才算被服务器验证过 */
    @JavascriptInterface
    fun onLoggedIn() {
        onLoggedIn()
    }

    companion object {
        /** 与 [JwCredentialScripts.BRIDGE_OBJECT] 必须是同一个名字 */
        const val NAME: String = JwCredentialScripts.BRIDGE_OBJECT
    }
}
