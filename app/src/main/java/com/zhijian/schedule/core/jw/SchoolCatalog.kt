package com.zhijian.schedule.core.jw

/**
 * 已实证的学校档案（只收录有真实抓取证据的学校，避免「猜 URL」）。
 *
 * 学校档案只解决两件事：
 * 1. 用户只输入裸域名时，补全该校真实存在的教务子路径；
 * 2. 为后续「学校列表 / 一校一档」提供最小数据模型。
 *
 * 识别本身仍以 URL 路径指纹为主（/jsxsd/、/jwglxt/），不靠域名猜厂商。
 */
data class SchoolProfile(
    val id: String,
    val name: String,
    val hosts: Set<String>,
    /** 教务基路径，必须以 / 开头、以 / 结尾 */
    val basePath: String,
    val adapterId: String,
)

object SchoolCatalog {

    private val profiles = listOf(
        SchoolProfile(
            id = "dgpt",
            name = "东莞职业技术学院",
            hosts = setOf("jw.dgpt.edu.cn"),
            basePath = "/jsxsd/",
            adapterId = QiangZhiAdapter.id,
        ),
    )

    fun byHost(host: String): SchoolProfile? {
        val h = host.trim().lowercase()
        if (h.isEmpty()) return null
        return profiles.firstOrNull { h in it.hosts }
    }

    fun knownBasePath(host: String): String? = byHost(host)?.basePath

    fun all(): List<SchoolProfile> = profiles
}
