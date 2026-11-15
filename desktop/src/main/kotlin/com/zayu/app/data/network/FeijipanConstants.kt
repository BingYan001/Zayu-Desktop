package com.zayu.app.data.network

/** 小飞机网盘（feijipan.com）常量。 */
object FeijipanConstants {

    const val API_BASE = "https://api.feijipan.com"
    const val WEB_SITE = "https://www.feijipan.com"

    /** 下载直链必须携带的头（取链与下载两侧一致，由下载链路按平台统一注入） */
    const val DOWNLOAD_UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
    const val DOWNLOAD_REFERER = "$WEB_SITE/"

    /**
     * 全站签名密钥（文析 AsterLink 同款）：请求的 `timestamp` 参数与分享下载地址
     * 都用这把 key 走 AES/ECB/PKCS5 加密 / 解密。
     */
    const val SIGN_KEY = "dingHao-disk-app"

    /** 客户端伪装参数（文析同款：devType 6 = web） */
    const val DEV_TYPE = "6"
    const val DEV_MODEL = "chrome"
    const val DEV_VERSION = "131"

    /** 分享根目录虚拟 id（文析用 "share-root"，保持一致便于排查） */
    const val SHARE_ROOT = "share-root"

    /** 分享列表页大小 */
    const val PAGE_SIZE = 60

    /** 分享列表最大翻页数（防御性上限，正常分享远到不了） */
    const val MAX_PAGES = 200
}
