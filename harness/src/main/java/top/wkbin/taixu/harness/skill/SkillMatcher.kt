package top.wkbin.taixu.harness.skill

import top.wkbin.taixu.core.database.PLACEHOLDER_SKILL_DESCRIPTION
import top.wkbin.taixu.core.model.AgentSkill

/**
 * 技能的**机械预匹配器**：不依赖模型自觉，由系统按任务文本与技能元数据做确定性打分，
 * 高置信命中即判定「本轮任务适用该技能」。
 *
 * ### 为什么要它
 * 技能目录（name + description）早已常驻系统提示，`load_skill` 工具也在，
 * 但「从看见到调用」这一段**全凭模型自觉**：实测中模型接手任务后往往凭自身记忆直接开干，
 * 成套的专业做法（含踩过的坑与验收标准）被闲置；更糟的是这种遗漏**无声无息**——
 * 没有任何可观测信号，用户不追问就永远不知道。
 *
 * 文案层面的「开工前先扫一遍」属于软约束，模型可以（也确实会）读过即忘。
 * 本匹配器把「要不要用技能」从模型的自由裁量，变成**系统的确定性判定**：
 * 调用方（SystemPromptBuilder）拿到命中结果后**直接注入技能正文**，
 * 使「按需加载」这一步不再依赖模型主动发起工具调用。
 *
 * ### 算法（技能侧显著词反向匹配，高精度优先、宁缺毋滥）
 * 从技能的 name / description / triggerCommand 提取显著词（**不含 category**，
 * 理由见 [score] 内的注释；目录扫描的占位描述也不进语料）：
 * - 英文 token：长度 ≥ 2，剔除高频虚词；
 * - 中文 2-gram：连续汉字串的一切相邻双字组合，剔除高频泛用词。
 *
 * 任务文本与语料都先做 NFKC 归一（全角→半角），与 @提及链路 `normalizeMentionKey` 同口径；
 * 全名命中要求词边界与最小长度（见 [isNameHit]）。
 *
 * 得分仅用于**排序**；是否命中改按**家族计数**判定：技能全名整串命中，或同一家族
 * （英文词 / 中文 2-gram）内出现 ≥[MIN_EN_SIGNALS] / ≥[MIN_CJK_SIGNALS] 个显著词。
 *
 * 为什么不能用总分累加：旧口径是「英文词 3 分 + 中文 2-gram 2 分 + 全名 6 分，总分 ≥4」，
 * 于是「一个英文词 + 一个中文 bigram = 5 分」也能定罪。这两个信号分属不同家族、互不印证
 * ——实测「用 java 开发一个页面」把 Android 逆向工作流判成适用（java 来自 description、
 * 开发 来自 category），而任务与逆向毫无关系。按家族计数堵住这条互相凑数的路径，
 * 代价是牺牲「单个英文词 + 单个中文词」这类本就勉强的召回。
 *
 * ### 已知局限（如实标注，勿当作全覆盖）
 * 只扫技能**元数据**，不扫 systemPrompt 正文（正文动辄数千字，纳入会产生大量显著词、
 * 显著抬高误报）。因此描述里没写到的关键词无法命中，例如「帮我构建 APK」在
 * `/buildguard` 的描述未含 "apk" 时不会自动命中。这类漏召回由模型的主动判断兜底
 * ——本机制的目标是**消除无声遗漏**，而非做到 100% 覆盖。
 */
internal object SkillMatcher {

    /** 命中结果：技能 + 得分 + 命中的显著词（供日志排查与单测断言）。 */
    data class Hit(
        val skill: AgentSkill,
        val score: Int,
        val matchedTerms: List<String> = emptyList(),
    )

    /**
     * 自动注入上限。刻意取 1：高精度匹配下最相关的那个已足以填补「从不加载」的主要缺口；
     * 多注入会线性吃掉上下文预算（单技能正文上限 8000 字符），且边际收益递减。
     * 其余技能仍可由模型自行 `load_skill` 或用户 @ 提及获得。
     */
    const val AUTO_INJECT_MAX = 1

    /**
     * 英文家族至少几个显著词才判定命中。
     *
     * 取代旧的「总分 ≥4」：见 [score] 中关于「一个英文词 + 一个中文 bigram 互相凑数」的论证。
     * 英文 token 已有长度 ≥2 与停用词过滤，2 个即构成"任务确实落在该领域"的证据。
     */
    private const val MIN_EN_SIGNALS = 2

    /**
     * 中文家族至少几个 2-gram 才判定命中。
     *
     * 比英文高一门：2-gram 是"任意相邻两个汉字"，噪音远高于成词 token——技能描述里的
     * 「冲突」「诊断」这类泛用双字词，任务里顺口就会出现。实测旧口径下
     * 「这两个诊断结论有冲突，帮我复核一遍」仅靠 冲突+诊断 两个 bigram 就把 git_workflow
     * 判成适用。取 3 之后该反例被挡下，而「git 分支合并冲突，生成提交信息」这类真实域内
     * 任务仍有 6 个 bigram 命中，召回不受影响。
     */
    private const val MIN_CJK_SIGNALS = 3

    /** 全名命中参与打分的最小长度（字符）：短名任意子串即误配。 */
    private const val MIN_NAME_MATCH_CHARS = 4

    /** 技能全名整串命中：最强信号。 */
    private const val NAME_SCORE = 6

    /** 英文显著词命中权重。 */
    private const val EN_SCORE = 3

    /** 中文显著词命中权重。 */
    private const val CJK_SCORE = 2

    private const val MAX_MATCHED_TERMS = 8

    private val EN_TOKEN = Regex("[a-z][a-z0-9_+#.-]*")
    private val CJK_RANGE = '\u4e00'..'\u9fff'

    private val EN_STOPWORDS = setOf(
        "the", "and", "for", "with", "use", "using", "via", "you", "your",
        "can", "will", "not", "are", "was", "were", "has", "have", "this",
        "that", "from", "into", "out", "all", "any", "but", "its", "is",
        "to", "of", "in", "on", "or", "an", "as", "by", "at", "be", "if",
    )

    /**
     * 中文高频泛用 2-gram。这些词在技能描述里很常见，在任务文本里也几乎必然出现，
     * 若不放行会把阈值稀释成「随便说句话都能命中」。
     */
    private val CJK_STOPWORDS = setOf(
        "分析", "使用", "进行", "支持", "可以", "如何", "什么", "这个", "一下",
        "以及", "或者", "并且", "相关", "工具", "内容", "信息", "规则", "提供",
        "需要", "快速", "自动", "通过", "一个", "我们", "是否", "可能", "实现",
        "完成", "要求", "方式", "情况", "问题", "处理", "过程", "方法",
        "结果", "系统", "功能", "能力", "场景", "领域", "项目", "时候",
    )

    /**
     * 对任务文本做机械匹配。
     *
     * @param taskText 本轮用户任务的原文（通常是最新一条用户消息）。
     * @param skills   候选技能（内部自行过滤 `isEnabled` 与空正文）。
     * @param maxHits  最多返回条数，默认 [AUTO_INJECT_MAX]。
     */
    fun match(
        taskText: String,
        skills: List<AgentSkill>,
        maxHits: Int = AUTO_INJECT_MAX,
    ): List<Hit> {
        val task = normalize(taskText)
        if (task.isBlank() || maxHits <= 0) return emptyList()
        val taskLower = task.lowercase()
        val taskEn = englishTokens(taskLower)
        val taskCjk = cjkBigrams(task)

        return skills.asSequence()
            .filter { it.isEnabled && it.systemPrompt.isNotBlank() }
            .mapNotNull { skill -> score(skill, taskLower, taskEn, taskCjk) }
            .sortedByDescending { it.score }
            .take(maxHits)
            .toList()
    }

    private fun score(
        skill: AgentSkill,
        taskLower: String,
        taskEn: Set<String>,
        taskCjk: Set<String>,
    ): Hit? {
        val matched = mutableListOf<String>()
        var score = 0

        // 语料只取 name / description / triggerCommand：
        //  · category 对「技能属于哪」有区分度，对「这轮任务要不要它」没有——同分类所有技能
        //    共享同一个类别词，任务顺口提到领域词就给每个同类技能加分（实测「编程开发」一词
        //    把 code_refactor 推过阈值，而任务与代码重构无关）；
        //  · 目录扫描的占位描述同理（见 PLACEHOLDER_SKILL_DESCRIPTION）。
        val description = skill.description.takeIf { it.isNotBlank() && it != PLACEHOLDER_SKILL_DESCRIPTION }
        val corpus = listOfNotNull(
            normalize(skill.name).takeIf { it.isNotBlank() },
            description?.let(::normalize),
            skill.triggerCommand?.removePrefix("/")?.let(::normalize),
        ).joinToString(" ")
        if (corpus.isBlank()) return null
        val corpusLower = corpus.lowercase()
        val corpusEn = englishTokens(corpusLower)
        val corpusCjk = cjkBigrams(corpus)

        val nameHit = isNameHit(taskLower, normalize(skill.name).lowercase())
        if (nameHit) {
            score += NAME_SCORE
            matched += skill.name.trim()
        }

        val enHits = taskEn.filter { it in corpusEn }
        val cjkHits = taskCjk.filter { it in corpusCjk }
        enHits.forEach { term ->
            score += EN_SCORE
            matched += term
        }
        cjkHits.forEach { term ->
            score += CJK_SCORE
            matched += term
        }

        // 判定门槛按**家族**计数，不再用总分累加。旧口径（EN 3 分 + CJK 2 分 + NAME 6 分，
        // 总分 ≥4 即过线）下，"一个英文词 + 一个中文 bigram = 5 分"也能定罪——两个分属不同
        // 家族、互不印证的弱信号互相凑数，实测把「用 java 开发一个页面」判成需要 Android 逆向
        // 工作流（java 来自 description、开发 来自 category）。现在必须同一家族内出现 ≥2 个
        // 显著词，或技能全名整串命中，才认定"任务确实落在该技能领域"。
        // 第四条是跨家族佐证：一个英文显著词（域内术语，已过长度与停用词过滤）加上两个中文
        // bigram。它保住「帮我处理一下 git 分支合并冲突」这类真实域内任务（git + 分支 + 冲突），
        // 而所有已复现的误报反例都不具备这个形态——它们要么纯中文泛用词（诊断+冲突），
        // 要么只有一个英文词配一个中文词（java+开发、apk+构建）。
        val qualified = nameHit ||
            enHits.size >= MIN_EN_SIGNALS ||
            cjkHits.size >= MIN_CJK_SIGNALS ||
            (enHits.isNotEmpty() && cjkHits.size >= MIN_CJK_SIGNALS - 1)
        if (!qualified) return null
        return Hit(skill, score, matched.take(MAX_MATCHED_TERMS))
    }

    /**
     * 全名命中判定：要求词边界与最小长度。
     *
     * 裸 `contains` 下，任意 1–2 字符技能名（目录名/frontmatter 名/正文标题/UI 自建/进化产出
     * 五条入口都没有长度下限）都会被无关任务里的任意子串推过阈值——实测技能名 `re` 命中
     * "recent"、`c` 命中 "class"、`库` 命中"数据库"。英文侧显著词走的是 token 集合相等
     * （天然带边界），全名侧却退化成子串包含，两侧精度口径不一致。
     */
    private fun isNameHit(taskLower: String, nameLower: String): Boolean {
        if (nameLower.length < MIN_NAME_MATCH_CHARS) return false
        var from = 0
        while (true) {
            val idx = taskLower.indexOf(nameLower, from)
            if (idx < 0) return false
            val before = taskLower.getOrNull(idx - 1)
            val after = taskLower.getOrNull(idx + nameLower.length)
            if (!before.isAsciiWordChar() && !after.isAsciiWordChar()) return true
            from = idx + 1
        }
    }

    private fun Char?.isAsciiWordChar(): Boolean =
        this != null && ((this in 'a'..'z') || (this in '0'..'9') || this == '_')

    /** NFKC 归一（全角→半角、兼容字符→标准形），与 @提及链路 `normalizeMentionKey` 同口径。 */
    private fun normalize(text: String): String =
        java.text.Normalizer.normalize(text.trim(), java.text.Normalizer.Form.NFKC)

    private fun englishTokens(lowerText: String): Set<String> =
        EN_TOKEN.findAll(lowerText)
            .map { it.value }
            .filter { it.length >= 2 && it !in EN_STOPWORDS }
            .toSet()

    private fun cjkBigrams(text: String): Set<String> {
        val out = LinkedHashSet<String>()
        for (i in 0 until text.length - 1) {
            val a = text[i]
            val b = text[i + 1]
            if (a in CJK_RANGE && b in CJK_RANGE) {
                val gram = "$a$b"
                if (gram !in CJK_STOPWORDS) out += gram
            }
        }
        return out
    }
}
