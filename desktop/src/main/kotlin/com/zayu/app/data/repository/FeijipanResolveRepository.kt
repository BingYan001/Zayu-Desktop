package com.zayu.app.data.repository

import com.zayu.app.data.network.FeijipanApi
import com.zayu.app.data.network.FeijipanConstants
import com.zayu.app.data.network.ShareLinkParser
import com.zayu.app.data.network.model.DownloadLink
import com.zayu.app.data.network.model.ShareFile
import com.zayu.app.data.network.model.ShareSession
import org.json.JSONObject
import java.net.URLEncoder

/**
 * 小飞机网盘分享解析仓库（文析 feijipan.dart 口径）：
 * getUuid → 分享根 recommend/list（首校验提取码）→ /ws/share/list 翻页列目录 →
 * /ws/file/download 返回加密地址 → AES-ECB 解出真直链。全程匿名可用。
 *
 * 会话里用 stoken 打包 "shareId|code|uuid"，避免改 ShareSession 结构。
 */
class FeijipanResolveRepository(
    private val api: FeijipanApi
) : ShareResolveRepository {

    private fun packSession(shareId: String, code: String, uuid: String) =
        listOf(shareId, code, uuid).joinToString("|") { URLEncoder.encode(it, "UTF-8") }

    private fun unpack(stoken: String): Triple<String, String, String> {
        val parts = stoken.split("|").map { java.net.URLDecoder.decode(it, "UTF-8") }
        require(parts.size == 3) { "小飞机分享会话无效，请重新解析" }
        return Triple(parts[0], parts[1], parts[2])
    }

    /** 列表行 → ShareFile。id 前缀 d:/f: 区分目录与文件（文析同款），fidToken 留空（小飞机取链不用）。 */
    private fun rowToFile(row: JSONObject, parent: String): ShareFile {
        val type = row.optInt("fileType", 0)
        require(type == 1 || type == 2) { "小飞机返回了不支持的文件类型" }
        val isDir = type == 2
        val rawId = row.optString(if (isDir) "folderId" else "fileId").removePrefix("d:").removePrefix("f:")
        require(rawId.matches(Regex("^\\d+$"))) { "小飞机文件标识无效，请刷新列表" }
        val name = row.optString(if (isDir) "folderName" else "fileName").trim()
        require(name.isNotEmpty()) { "小飞机未返回文件名" }
        val sizeKib = row.optString("fileSize").toLongOrNull() ?: 0L
        return ShareFile(
            fid = "${if (isDir) "d" else "f"}:$rawId",
            fname = name,
            fsize = if (isDir) 0 else sizeKib * 1024,
            isdir = isDir,
            pdirFid = parent,
            fidToken = "",
            modifyTime = row.optString("updTime").ifEmpty { row.optString("addTime") }
        )
    }

    override suspend fun createSession(link: String, pwd: String?, cookie: String): Result<ShareSession> =
        runCatching {
            val parsed = ShareLinkParser.parse(link)
                ?: return Result.failure(IllegalArgumentException("无法识别分享链接"))
            val code = pwd?.takeIf { it.isNotBlank() } ?: parsed.pwd.orEmpty()
            // uuid 拿不到就用本地随机串顶上（服务端只校验格式，分享流程不绑设备）
            val uuid = runCatching { api.getUuid() }.getOrElse { api.fallbackUuid() }
            // 首页即校验提取码 / 分享有效性（文析 openShare 同款）
            api.listSharePage(uuid, parsed.shareId, code, null, 1)
            ShareSession(
                shareId = parsed.shareId,
                stoken = packSession(parsed.shareId, code, uuid),
                title = "小飞机分享"
            )
        }

    override suspend fun listFiles(session: ShareSession, dirFid: String, cookie: String): Result<List<ShareFile>> =
        runCatching {
            val (_, code, uuid) = unpack(session.stoken)
            val parent = dirFid.ifBlank { FeijipanConstants.SHARE_ROOT }
            val all = mutableListOf<ShareFile>()
            val seen = mutableSetOf<String>()
            for (page in 1..FeijipanConstants.MAX_PAGES) {
                val data = api.listSharePage(
                    uuid, session.shareId, code,
                    if (parent == FeijipanConstants.SHARE_ROOT) null else parent.removePrefix("d:"),
                    page
                )
                val rows = mutableListOf<JSONObject>()
                val list = data.optJSONArray("list") ?: org.json.JSONArray()
                for (i in 0 until list.length()) {
                    val row = list.optJSONObject(i) ?: continue
                    // 首页行可能是 {fileList:[...]} 的分组结构（文析同款展开）
                    val inner = row.optJSONArray("fileList")
                    if (inner != null) {
                        for (j in 0 until inner.length()) {
                            inner.optJSONObject(j)?.let(rows::add)
                        }
                    } else {
                        rows.add(row)
                    }
                }
                for (row in rows) {
                    val file = rowToFile(row, parent)
                    if (seen.add(file.fid)) all.add(file)
                }
                val totalPage = data.optInt("totalPage", 0)
                if (if (totalPage > 0) page >= totalPage else rows.size < FeijipanConstants.PAGE_SIZE) break
            }
            all
        }

    override suspend fun ensureTempDir(cookie: String): Result<String> =
        Result.failure(UnsupportedOperationException("小飞机分享无需预建临时目录"))

    override suspend fun transferFile(
        session: ShareSession,
        file: ShareFile,
        toDirFid: String,
        cookie: String,
        skipSpaceCheck: Boolean
    ): Result<String> =
        Result.failure(UnsupportedOperationException("小飞机分享下载无需转存"))

    override suspend fun getDownloadLink(fid: String, cookie: String): Result<DownloadLink> =
        Result.failure(UnsupportedOperationException("小飞机分享请使用 getShareDownloadLink"))

    override suspend fun getShareDownloadLink(
        session: ShareSession,
        file: ShareFile,
        cookie: String,
        skipSpaceCheck: Boolean
    ): Result<DownloadLink> = runCatching {
        require(!file.isdir) { "请选择要下载的文件" }
        val (_, code, uuid) = unpack(session.stoken)
        val fileId = file.fid.removePrefix("f:").removePrefix("d:")
        val encrypted = api.getShareDownloadUrl(uuid, session.shareId, code, fileId)
        val url = api.decryptHex(encrypted)
        require(url.startsWith("http")) { "小飞机下载地址无效" }
        // 直链签名与 UA/Referer 相关；下载请求头由下载链路按平台统一注入，这里只回传地址
        DownloadLink(
            fid = file.fid,
            filename = file.fname,
            downloadUrl = url,
            size = file.fsize
        )
    }

    override suspend fun getGuestShareDownloadLink(
        session: ShareSession,
        file: ShareFile
    ): Result<DownloadLink> = getShareDownloadLink(session, file, "")

    override suspend fun cleanupTempDir(dirFid: String, cookie: String) {
        // 无临时目录，无需清理
    }
}
