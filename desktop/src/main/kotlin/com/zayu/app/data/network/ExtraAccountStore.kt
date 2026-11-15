package com.zayu.app.data.network

import com.zayu.app.AppContext
import com.zayu.app.data.security.FileCredentialCipher
import com.zayu.app.util.Log

/**
 * 三家「粘贴 token/cookie」平台的凭据存储（PikPak / 联通云盘 / 天翼云盘）。
 *
 * 这三家没有网页登录流程（不走 WebView），登录方式是用户在浏览器里登录后
 * 把 access_token / Cookie 粘贴进来。存储链路与 [GitHubTokenStore] 完全一致：
 * [FileCredentialCipher] AES-256-GCM 加密后写 [AppContext.miscPrefs]，**不明文落盘**。
 *
 * ★ 解密失败不删密文（对齐上游 #179「凭证加固」）：密钥一时读不到（文件被占用）就删，
 *   等于把用户配置的凭据永久丢掉。本次返回 null（界面按「未登录」），下次能解开时自动恢复。
 */
object ExtraAccountStore {

    private const val KEY_PIKPAK = "pikpak_token_encrypted"
    private const val KEY_WOPAN = "wopan_token_encrypted"
    private const val KEY_TIANIY = "tianyi_cookie_encrypted"
    private const val PURPOSE = "extra_account"

    private val cipher = FileCredentialCipher()

    // ---------- 读 ----------

    /** PikPak access_token（login 弹窗里可能粘贴 refresh_token，由调用方换发后再存） */
    fun getPikpakToken(): String? = read(KEY_PIKPAK)

    /** 联通云盘 access token（纯 token / Bearer xxx / localStorage JSON 三种写法由仓库侧归一） */
    fun getWopanToken(): String? = read(KEY_WOPAN)

    /** 天翼云盘 web Cookie 整串（关键域 cloud.189.cn） */
    fun getTianyiCookie(): String? = read(KEY_TIANIY)

    // ---------- 写 / 清 ----------

    fun setPikpakToken(value: String?): Boolean = write(KEY_PIKPAK, value)
    fun setWopanToken(value: String?): Boolean = write(KEY_WOPAN, value)
    fun setTianyiCookie(value: String?): Boolean = write(KEY_TIANIY, value)

    // ---------- 内部 ----------

    private fun read(key: String): String? {
        val stored = AppContext.miscPrefs.get(key, null) ?: return null
        return runCatching { cipher.decrypt(stored, PURPOSE) }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
    }

    private fun write(key: String, value: String?): Boolean {
        val prefs = AppContext.miscPrefs
        val trimmed = value?.trim().orEmpty()
        if (trimmed.isEmpty()) {
            prefs.remove(key)
            return true
        }
        return runCatching {
            prefs.put(key, cipher.encrypt(trimmed, PURPOSE))
            true
        }.getOrElse {
            // 加密失败（密钥暂时不可用）：保留原值，不要覆盖成空
            Log.w("Zayu-ExtraAccount", "凭据加密失败，保留原值：${it.message ?: it.javaClass.simpleName}")
            false
        }
    }
}
