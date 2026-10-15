package com.tomcat927.miscuploader.data

/**
 * AI 落点建议的提示词与解析(纯函数,单测覆盖)。
 * 只建议不执行:解析出的目录由用户在确认框里决定是否采用。
 */
object AiSuggest {

    /** 归档建议:送给模型的仓库目录数上限 */
    private const val MAX_PROMPT_DIRS = 80

    /** 归档建议:送给模型的内容名上限 */
    private const val MAX_PROMPT_NAMES = 120

    /** 归档建议:送给模型的所选文件夹名上限 */
    private const val MAX_PROMPT_FOLDER_NAMES = 20

    const val SYSTEM_PROMPT =
        "你是文件归档助手。根据文件名、图片内容和仓库已有目录结构，判断这些内容最适合归档到仓库的哪个目录。" +
            "优先沿用已有目录；没有合适的可新建一个简洁的子目录。" +
            "所选文件夹的内容会直接放入你给出的目录，不要再多套一层文件夹名。" +
            "只回复一个以 / 开头的目录路径，不要任何解释、不要引号、不要多余文字。"

    /** 归档建议提示词:给出仓库已有目录(供沿用)+ 当前目录 + 待归档内容(文件夹已展开为内容) */
    fun prompt(
        existingDirs: List<String>,
        currentRemoteDir: String,
        itemNames: List<String>,
        hasFolder: Boolean = false,
        folderNames: List<String> = emptyList(),
    ): String = buildString {
        appendLine("仓库已有目录（供参考，尽量沿用）：")
        if (existingDirs.isEmpty()) {
            appendLine("（无）")
        } else {
            existingDirs.take(MAX_PROMPT_DIRS).forEach { appendLine("- $it") }
        }
        appendLine("当前所在目录：$currentRemoteDir")
        if (folderNames.isNotEmpty()) {
            appendLine("所选文件夹：${folderNames.take(MAX_PROMPT_FOLDER_NAMES).joinToString("、")}")
            appendLine("（文件夹的内容会直接放进你给出的目录，不要再多套一层同名文件夹）")
        }
        appendLine(if (hasFolder) "待归档内容（含文件夹，已展开）：" else "待归档文件：")
        itemNames.take(MAX_PROMPT_NAMES).forEach { appendLine("- $it") }
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

    private val LEADING_MARKER = Regex("^[\\s\\-*•·>#]+")
    private val LEADING_NUMBER = Regex("^\\d+\\s*[.、)）:：]\\s*")
    private val TRIM_CHARS = charArrayOf('「', '」', '『', '』', '"', '\'', '`', '*', ' ')

    /** 模型明确表示「无需清理」(区别于解析失败) */
    fun saysNothingToClean(response: String): Boolean {
        val t = response.trim().trim('。', '.', '!', '！', '?', '？', ' ', '\n', '\t')
        return t.isEmpty() || t in setOf("无", "无建议", "无需清理", "没有", "none", "None", "NONE")
    }

    /**
     * 解析清理建议:逐行取「文件名 + 理由」;仅保留确实存在于 [available] 的名字(防模型编造)。
     * 逐行先去掉列表标记(- / * / • / 1. / 1、/ 1) )与成对引号/反引号/markdown 粗体,
     * 再按「长名优先」匹配行首文件名;兼容「名<TAB>理由」「名 - 理由」「名：理由」等写法。
     */
    fun parseCleanup(response: String, available: Set<String>): List<Pair<String, String>> {
        if (available.isEmpty()) return emptyList()
        val names = available.sortedByDescending { it.length } // 长名优先,避免前缀误配
        val out = mutableListOf<Pair<String, String>>()
        response.trim().lines().forEach { raw ->
            var line = raw.trim()
            line = LEADING_MARKER.replace(line, "")
            line = LEADING_NUMBER.replace(line, "")
            line = line.trim().trim(*TRIM_CHARS).trim()
            if (line.isEmpty()) return@forEach
            val name = names.firstOrNull { line.startsWith(it) } ?: return@forEach
            if (out.any { it.first == name }) return@forEach
            val reason = line.removePrefix(name)
                .trim()
                .trimStart(':', '：', '-', '—', '–', '\t', ' ', ',', '，', ';', '；', ')', '）', ']', '】', '」', '』', '"', '\'', '`', '*')
                .trim()
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
