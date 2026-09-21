package top.wkbin.taixu.harness.skill

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
 * 从技能的 name / description / category / triggerCommand 提取显著词：
 * - 英文 token：长度 ≥ 2，剔除高频虚词；
 * - 中文 2-gram：连续汉字串的一切相邻双字组合，剔除高频泛用词。
 *
 * 再检查显著词是否出现在**任务文本**中并累加得分：
 * - 技能全名整串命中 → [NAME_SCORE]
 * - 每个英文显著词命中 → [EN_SCORE]
 * - 每个中文显著词命中 → [CJK_SCORE]
 *
 * 总分达到 [MIN_SCORE] 才算命中。阈值取 4 的理由：单个英文词（3 分）不足以定罪
 * ——任务里顺口提一句 git 不代表要做 Git 工作流；需再叠加一个显著词（中文词或全名）
 * 才构成「任务确实落在该技能领域」的证据。
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

    /** 判定阈值。参见类注释中「阈值取 4」的论证。 */
    private const val MIN_SCORE = 4

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
        "完成", "要求", "方式", "情况", "问题", "处理", "过程", "时候", "方法",
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
        val task = taskText.trim()
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

        val name = skill.name.trim()
        if (name.isNotBlank() && taskLower.contains(name.lowercase())) {
            score += NAME_SCORE
            matched += name
        }

        val corpus = listOfNotNull(
            skill.name,
            skill.description,
            skill.category,
            skill.triggerCommand?.removePrefix("/"),
        ).joinToString(" ")
        val corpusLower = corpus.lowercase()
        val corpusEn = englishTokens(corpusLower)
        val corpusCjk = cjkBigrams(corpus)

        taskEn.filter { it in corpusEn }.forEach { term ->
            score += EN_SCORE
            matched += term
        }
        taskCjk.filter { it in corpusCjk }.forEach { term ->
            score += CJK_SCORE
            matched += term
        }

        if (score < MIN_SCORE) return null
        return Hit(skill, score, matched.take(MAX_MATCHED_TERMS))
    }

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
