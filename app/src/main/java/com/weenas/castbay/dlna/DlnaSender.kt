package com.weenas.castbay.dlna

import com.weenas.castbay.R

/**
 * Which app is casting over DLNA. DLNA doesn't say which phone is casting, but apps' requests
 * name the app in their User-Agent (iQiyi: "UPnP/1.0 IQIYIDLNA/iqiyidlna/NewDLNA/1.0").
 */
object DlnaSender {
    const val IQIYI = "iqiyi"
    const val BILIBILI = "bilibili"
    const val NETEASE_MUSIC = "netease_music"
    const val QQ_MUSIC = "qq_music"
    const val TENCENT_VIDEO = "tencent_video"
    const val YOUKU = "youku"
    const val MANGO_TV = "mango_tv"
    const val KUGOU = "kugou"
    const val KUWO = "kuwo"

    /** The app's name (a string resource), or null for an unknown [id]. */
    fun nameRes(id: String): Int? = when (id) {
        IQIYI -> R.string.sender_iqiyi
        BILIBILI -> R.string.sender_bilibili
        NETEASE_MUSIC -> R.string.sender_netease_music
        QQ_MUSIC -> R.string.sender_qq_music
        TENCENT_VIDEO -> R.string.sender_tencent_video
        YOUKU -> R.string.sender_youku
        MANGO_TV -> R.string.sender_mango_tv
        KUGOU -> R.string.sender_kugou
        KUWO -> R.string.sender_kuwo
        else -> null
    }

    /** User-Agent fragments (lowercase) to the app; first match wins. */
    private val PATTERNS = listOf(
        "iqiyi" to IQIYI,
        "bili" to BILIBILI,
        "cloudmusic" to NETEASE_MUSIC,
        "netease" to NETEASE_MUSIC,
        "qqmusic" to QQ_MUSIC,
        "qqlive" to TENCENT_VIDEO,
        "tencentvideo" to TENCENT_VIDEO,
        "youku" to YOUKU,
        "mgtv" to MANGO_TV,
        "imgotv" to MANGO_TV,
        "kugou" to KUGOU,
        "kuwo" to KUWO,
    )

    /** Media hosts (lowercase fragments) to the app, for apps that send no User-Agent. */
    private val HOSTS = listOf(
        "126.net" to NETEASE_MUSIC,
        "qqmusic" to QQ_MUSIC,
        "iqiyi.com" to IQIYI,
        "bilivideo" to BILIBILI,
        "kugou" to KUGOU,
        "kuwo" to KUWO,
        "youku" to YOUKU,
        "mgtv" to MANGO_TV,
    )

    /** The app whose servers [url] is on (NetEase Cloud Music sends no User-Agent), or "". */
    fun fromUrl(url: String?): String {
        val host = url?.substringAfter("://")?.substringBefore('/')?.substringBefore(':')?.lowercase() ?: return ""
        return HOSTS.firstOrNull { (fragment, _) -> fragment in host }?.second.orEmpty()
    }

    /** The app's id, or "" when the User-Agent doesn't name a known one. */
    fun fromUserAgent(userAgent: String?): String {
        val agent = userAgent?.lowercase() ?: return ""
        return PATTERNS.firstOrNull { (fragment, _) -> fragment in agent }?.second.orEmpty()
    }
}
