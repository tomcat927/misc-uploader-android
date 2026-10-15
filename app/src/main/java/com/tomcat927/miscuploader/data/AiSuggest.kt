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

    // ---- P2:本地清理建议(只建议;删除由用户确认后软删除) ----

    const val CLEANUP_SYSTEM_PROMPT =
        "你是手机存储清理助手。根据文件名和图片内容，挑出建议清理的文件（临时/缓存/重复/截图/无用大文件等）。" +
            "每个建议清理的文件输出一行，格式为「文件名\t理由」，不要编号、不要解释整段话。" +
            "重要文件（个人照片、证件、文档）不要建议删除；没有建议就只回复「无」。"

    fun cleanupPrompt(dir: String, fileNames: List<String>): String = buildString {
        appendLine("目录：$dir")
        appendLine("文件（共 ${fileNames.size} 个）：")
        fileNames.take(80).forEach { appendLine("- $it") }
        appendLine("请只列出建议清理的文件，每行：文件名<TAB>理由。")
    }

    /**
     * 解析清理建议:逐行取「文件名 + 理由」;仅保留确实存在于 [available] 的名字(防模型编造)。
     * 兼容「名<TAB>理由」「名 - 理由」「名：理由」「「名」理由」等写法。
     */
    fun parseCleanup(response: String, available: Set<String>): List<Pair<String, String>> {
        if (available.isEmpty()) return emptyList()
        val names = available.sortedByDescending { it.length } // 长名优先,避免前缀误配
        val out = mutableListOf<Pair<String, String>>()
        response.trim().lines().forEach { raw ->
            val line = raw.trim().trimStart('-', '*', ' ', '\t').removePrefix("「").trim()
            if (line.isEmpty()) return@forEach
            val name = names.firstOrNull { line.startsWith(it) } ?: return@forEach
            if (out.any { it.first == name }) return@forEach
            val reason = line.removePrefix(name)
                .trim()
                .trimStart(':', '：', '-', '—', '\t', ' ', '」', '”', '"', '，', ',')
            out += name to reason
        }
        return out
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
