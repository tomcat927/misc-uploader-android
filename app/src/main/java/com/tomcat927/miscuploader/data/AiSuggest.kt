package com.tomcat927.miscuploader.data

/**
 * AI 落点建议的提示词与解析(纯函数,单测覆盖)。
 * 只建议不执行:解析出的目录由用户在确认框里决定是否采用。
 */
object AiSuggest {

    const val SYSTEM_PROMPT =
        "你是文件归档助手。根据文件名和图片内容判断这些文件最适合归入哪个目录。" +
            "只回复一个以 / 开头的目录路径，不要任何解释、不要引号、不要多余文字。"

    fun prompt(existingDirs: List<String>, currentDir: String, fileNames: List<String>): String = buildString {
        appendLine("仓库已有目录：${if (existingDirs.isEmpty()) "（无）" else existingDirs.joinToString("、")}")
        appendLine("当前所在目录：$currentDir")
        appendLine("待上传文件（共 ${fileNames.size} 个）：")
        fileNames.take(50).forEach { appendLine("- $it") }
        appendLine("请只回复一个目录路径（以 / 开头）。可沿用当前目录，或用已有目录，或新建一个子目录。")
    }

    /**
     * 从模型回复里抽出第一个以 / 开头的路径:
     * 取首个含 '/' 的行 → 从首个 '/' 起 → 截到第一个空白 → 去尾部标点;抽不到则回落 fallback。
     * 目录名假定不含空白(本仓库目录均如此)。
     */
    fun parseDir(response: String, fallback: String): String {
        val line = response.trim().lineSequence().firstOrNull { it.contains('/') }?.trim().orEmpty()
        if (line.isEmpty()) return fallback
        val token = line.substring(line.indexOf('/')).takeWhile { !it.isWhitespace() }
        val candidate = token.trimEnd(
            '.', '。', ',', '，', ';', '；', ':', '：', '"', '\'', '`', '“', '”', ')', '）', ']', '】',
        )
        return UploadPlanning.normalizeDir(candidate.ifBlank { fallback })
    }
}
