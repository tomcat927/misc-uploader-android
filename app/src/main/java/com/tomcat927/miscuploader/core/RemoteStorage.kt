package com.tomcat927.miscuploader.core

import java.io.File

/**
 * 上传协议接口缝(拍板:纯 REST 实现;迁离 alist 系时补第二个实现即可)
 *
 * 契约见私有仓 tianyi-misc-repo docs/client-protocol-decision.md:
 * - 响应强制 JSON 解析 + code==200,非 JSON(200+HTML)一律失败
 * - 401 只重登一次
 * - 上传进度 = 客户端字节计数,协议无关
 */
interface RemoteStorage {
    /** 登录并持有会话 token(成功后 list/mkdir/upload 可用) */
    suspend fun login()

    /** 列目录(相对用户 base_path;"/" = 根) */
    suspend fun list(path: String, page: Int = 1, refresh: Boolean = false): List<FsEntry>

    /** 建目录(逐级;已存在返回错误码由调用方按"已存在"处理) */
    suspend fun mkdir(path: String)

    /**
     * 逐级确保父目录存在(忽略"已存在"类错误,misc-sync.py 同款语义;
     * 真正无法创建时由后续上传报错走失败路径)。
     */
    suspend fun mkdirp(remotePath: String)

    /** 文件元信息(fs/get):size 用于预览上限判断,rawUrl 用于图片直连/下载 */
    suspend fun fileInfo(remotePath: String): RemoteFileInfo

    /** 下载远程文件到本地目标(流式,经 raw_url 跟随重定向) */
    suspend fun downloadTo(
        remotePath: String,
        target: File,
        onProgress: (sent: Long, total: Long) -> Unit = { _, _ -> },
    )

    /**
     * 流式上传,内存恒定。
     * @param remotePath 目标完整路径(以 "/" 开头,含文件名)
     * @param overwrite false 时同名存在 → OpenListApiException(403),由调用方当"已存在"闸门
     * @param onProgress 已发送字节 / 总字节(客户端字节计数)
     */
    suspend fun upload(
        file: File,
        remotePath: String,
        overwrite: Boolean = true,
        onProgress: (sent: Long, total: Long) -> Unit = { _, _ -> },
    )
}
