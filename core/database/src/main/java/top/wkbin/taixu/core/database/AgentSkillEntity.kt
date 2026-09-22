package top.wkbin.taixu.core.database

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import top.wkbin.taixu.core.model.AgentSkill
import top.wkbin.taixu.core.model.BuiltinSkills
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 目录扫描但 frontmatter 无 description 时的占位描述。
 *
 * 它是"给人看的说明"，却被 SkillMatcher 无差别当作匹配语料：多个无描述技能共享同一句话，
 * 产出「目录/发现/自定/定义」四个高频 2-gram，任务里共现两个即可把技能推过注入阈值。
 * 匹配侧因此要把它识别出来并排除（见 SkillMatcher）。
 */
const val PLACEHOLDER_SKILL_DESCRIPTION = "从目录自动发现的 Skill"

@Entity(tableName = "agent_skills")
data class AgentSkillEntity(
    @androidx.room.PrimaryKey val id: String,
    val name: String,
    val description: String,
    val systemPrompt: String,
    val triggerCommand: String?,
    val iconName: String,
    val isEnabled: Boolean,
    val isBuiltin: Boolean,
    val isImmutable: Boolean,
    val category: String,
    val resourcePath: String?,
)

@Dao
interface AgentSkillDao {
    @Query("SELECT * FROM agent_skills ORDER BY isBuiltin DESC, name ASC")
    fun observeAll(): Flow<List<AgentSkillEntity>>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(skills: List<AgentSkillEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(skills: List<AgentSkillEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(skill: AgentSkillEntity)

    @Query("UPDATE agent_skills SET isEnabled = :enabled WHERE id = :id AND isImmutable = 0")
    suspend fun setEnabled(id: String, enabled: Boolean)

    @Query("DELETE FROM agent_skills WHERE id = :id AND isBuiltin = 0")
    suspend fun deleteCustom(id: String)
}

/** 可自动发现 Skill 的宿主目录与其在 PRoot 沙箱内的挂载前缀（如 /attachments/skills）。 */
data class SkillScanRoot(
    val hostDir: File,
    val guestPrefix: String,
)

@Singleton
class AgentSkillRepository @Inject constructor(
    private val dao: AgentSkillDao,
) {
    val allSkills: Flow<List<AgentSkill>> = dao.observeAll().map { rows -> rows.map(AgentSkillEntity::toModel) }
    val activeSkills: Flow<List<AgentSkill>> = allSkills.map { skills -> skills.filter { it.isEnabled } }

    private val directorySyncMutex = Mutex()

    /**
     * 把内置技能播种到库中，并**同步正文改动**。
     *
     * 历史行为是 `insertAll(IGNORE)` —— 只补缺、不更新已存在的行。后果：内置技能的正文
     * （systemPrompt）一旦在代码里修过，**已安装用户永远拿不到**（那一行早就存在，
     * `IGNORE` 直接跳过）。手机端没法像开发机那样清库，于是"改了技能但用户看不到"
     * 会一直静默持续。同族对比：`AgentSubagentDao.syncBuiltinCatalog` 早就用
     * `catalogRevision` 做了版本化重播种，技能侧缺失同一机制。
     *
     * 这里不引入 revision 常量（需要新表/迁移），改用**内容差异比较**——
     * 自愈且不会因为忘记递增版本号而失效：
     * - 逐字段比对"除 isEnabled 外的全部内容"；
     * - 仅当内容不一致时才 upsert；
     * - upsert 时沿用库中 `isEnabled`，**不覆盖用户手动开关**。
     *
     * 幂等：内容一致时不做任何写入（避免每次启动都全表 REPLACE）。
     */
    suspend fun ensureInitialized() {
        val existing = dao.observeAll().first().associateBy { it.id }
        val updates = BuiltinSkills.presets.mapNotNull { preset ->
            val target = preset.toEntity()
            val saved = existing[preset.id]
            when {
                saved == null -> target
                // 内容一致 → 不动（保住 isEnabled，也避免无谓写库）
                saved.contentEqualsIgnoringEnabled(target) -> null
                // 内容变了 → 用代码侧的新内容，但保留用户的启用选择
                else -> target.copy(isEnabled = saved.isEnabled)
            }
        }
        if (updates.isNotEmpty()) dao.upsertAll(updates)
    }

    suspend fun setEnabled(id: String, enabled: Boolean) {
        ensureInitialized()
        dao.setEnabled(id, enabled)
    }

    suspend fun addCustom(skill: AgentSkill) {
        ensureInitialized()
        dao.upsert(skill.toEntity())
    }

    suspend fun deleteCustom(id: String) {
        ensureInitialized()
        dao.deleteCustom(id)
    }

    /**
     * 递归扫描各根目录（含任意深度嵌套），把直属包含 SKILL.md / prompt.md 且尚未入库的
     * 目录自动注册为自定义 Skill。用户手动复制 Skill 文件夹（或把其他工具的整个
     * skills 目录整体复制）到 attachments/skills 或工作区 skills 目录即可被发现，
     * 无需通过 ZIP 逐个导入。按 resourcePath 去重，重复调用安全。
     *
     * 嵌套语义：目录树中任何直属含 SKILL.md 的目录都是一个 Skill——既支持
     * “单个 Skill 目录”，也支持“集合目录（如 rikkahub / aicode 的整个 skills 目录）”，
     * 以及 Skill 目录内再嵌套 Skill 子目录的多级结构。
     *
     * @return 本次新注册的 Skill 列表
     */
    suspend fun syncFromDirectories(roots: List<SkillScanRoot>): List<AgentSkill> = directorySyncMutex.withLock {
        ensureInitialized()
        val knownPaths = dao.observeAll().first()
            .mapNotNull { it.resourcePath }
            .mapTo(mutableSetOf()) { stored ->
                runCatching { File(stored).canonicalPath }.getOrDefault(stored)
            }
        val imported = mutableListOf<AgentSkill>()
        roots.forEach { root ->
            root.hostDir.walkTopDown()
                .maxDepth(MAX_SCAN_DEPTH)
                .filter { it.isDirectory && it != root.hostDir }
                .forEach { dir ->
                    val canonical = runCatching { dir.canonicalPath }.getOrNull() ?: return@forEach
                    if (canonical in knownPaths) return@forEach
                    val promptFile = directPromptFile(dir) ?: return@forEach
                    val relative = dir.relativeTo(root.hostDir).invariantSeparatorsPath
                    registerDir(dir, promptFile, root.guestPrefix.trimEnd('/') + "/" + relative, knownPaths, imported)
                }
        }
        imported
    }

    private suspend fun registerDir(dir: File, promptFile: File, guestPath: String, knownPaths: MutableSet<String>, imported: MutableList<AgentSkill>) {
        val markdown = runCatching { promptFile.readText().trim() }.getOrNull()
        if (markdown.isNullOrBlank()) return
        val metadata = parseFrontmatter(markdown)
        val skill = AgentSkill(
            id = "custom_" + UUID.randomUUID().toString().take(8),
            name = metadata["name"]?.let(::normalizeSkillName)
                ?: markdown.lineSequence().firstOrNull { it.startsWith("# ") }?.removePrefix("# ")?.trim()
                ?: dir.name,
            description = metadata["description"]?.takeIf { it.isNotBlank() }
                ?.take(MAX_DESCRIPTION_CHARS) ?: PLACEHOLDER_SKILL_DESCRIPTION,
            systemPrompt = markdown + "\n\n【Skill 资源目录】$guestPath\n如需执行该 Skill 附带的脚本，请先检查脚本内容与参数，再从此目录调用。",
            isBuiltin = false,
            category = "自定义",
            resourcePath = dir.absolutePath,
        )
        dao.upsert(skill.toEntity())
        knownPaths += runCatching { dir.canonicalPath }.getOrDefault(dir.absolutePath)
        imported += skill
    }

    /** 目录直属的提示词文件（不含嵌套子目录），优先 SKILL.md。 */
    private fun directPromptFile(dir: File): File? =
        dir.listFiles().orEmpty()
            .filter { it.isFile && it.name.lowercase() in SKILL_PROMPT_FILE_NAMES }
            .let { files -> files.firstOrNull { it.name.lowercase() == "skill.md" } ?: files.firstOrNull() }

    companion object {
        /** Skill 目录的提示词文件名（小写），供导入与扫描逻辑统一判定。 */
        val SKILL_PROMPT_FILE_NAMES = setOf("skill.md", "prompt.md")
        private const val MAX_SCAN_DEPTH = 6
        private const val MAX_DESCRIPTION_CHARS = 1024

        /** 从 SKILL.md 的 YAML frontmatter 中提取 name / description 等元数据。 */
        fun extractSkillMetadata(markdown: String, key: String): String? =
            parseFrontmatter(markdown)[key.lowercase()]?.takeIf { it.isNotBlank() }

        /**
         * 解析 SKILL.md 的 YAML frontmatter 为扁平 key→value 映射。
         *
         * 对齐 Claude Code / OpenMinis 生态 SKILL.md 的常见形态：
         * - UTF-8 BOM 与 CRLF 行尾容错（Windows 记事本保存的文件此前整体解析失败）；
         * - key 大小写不敏感，值可带单/双引号；
         * - `description: >` / `|-` 等多行折叠块（官方技能模板常见写法，此前解析为空）；
         * - 未加引号值尾部的 `# 注释` 剥离（加引号值里的 `#` 保留）。
         *
         * **刻意不支持的 YAML 形态**（窄实现，不是缺陷）：块标量内部的空行（遇空行即终止块）、
         * 嵌套映射（`test:` 下的子键会被误认为顶层 key）、引号内成对使用的引号。
         * SKILL.md 的 frontmatter 实际只用 name/description，无需完整 YAML 解析器。
         */
        fun parseFrontmatter(markdown: String): Map<String, String> {
            var text = markdown
            if (text.startsWith("\uFEFF")) text = text.substring(1)
            if (!text.startsWith("---")) return emptyMap()
            val lines = text.lineSequence().drop(1)
                .takeWhile { it.trim() != "---" && it.trim() != "..." }
                .toList()
            val result = mutableMapOf<String, String>()
            var index = 0
            while (index < lines.size) {
                val trimmed = lines[index].trim()
                if (trimmed.isEmpty() || trimmed.startsWith("#") || !trimmed.contains(':')) {
                    index++
                    continue
                }
                // 分隔冒号必须落在**引号之外**：`"name": value` 这类加引号的 key 若用
                // substringBefore(':') 会在引号内部断开，产出 key=`"na` 的垃圾条目，
                // 真正的键值对整条丢失；而引号在 .trim('"','\'') 时被剥掉，连痕迹都不留。
                val separator = unquotedColonIndex(trimmed)
                if (separator <= 0) {
                    index++
                    continue
                }
                val key = trimmed.substring(0, separator).trim().trim('"', '\'')
                var value = trimmed.substring(separator + 1).trim()
                index++
                if (value.startsWith(">") || value.startsWith("|")) {
                    // 折叠/字面块标量（含 chomping 修饰：>-、|-、>+ 等）。
                    //
                    // **两种风格都折成单行**（`|` 不按 YAML 规范保留换行）—— 这是刻意的产品取舍，
                    // 不是疏漏：description 只用于「技能目录一行摘要」与「设置页技能行」，
                    // 都是单行展示位；保留换行会让设置页出现突兀的多行描述。
                    // 目录侧另有 `desc.replace(Regex("\\s+"), " ")` 兜底，故折行不丢信息。
                    // 既有测试 `literal block and terminator ellipsis are handled` 固定了该行为。
                    val block = StringBuilder()
                    while (index < lines.size &&
                        (lines[index].startsWith(" ") || lines[index].startsWith("\t") || lines[index].isBlank())
                    ) {
                        block.append(' ').append(lines[index].trim())
                        index++
                    }
                    value = block.toString().trim()
                } else {
                    if (!value.startsWith("\"") && !value.startsWith("'")) {
                        val comment = value.indexOf(" #")
                        if (comment >= 0) value = value.substring(0, comment).trim()
                    }
                    value = value.trim('"', '\'')
                }
                if (key.isNotEmpty() && value.isNotEmpty()) result[key.lowercase()] = value
            }
            return result
        }

        /**
         * 首个**不在引号内**的 `:` 下标；没有则返回 -1。
         * 单/双引号状态分别跟踪（`"it's"` 内的单引号不应翻转状态）。
         */
        private fun unquotedColonIndex(line: String): Int {
            var inSingle = false
            var inDouble = false
            line.forEachIndexed { i, ch ->
                when {
                    ch == '\'' && !inDouble -> inSingle = !inSingle
                    ch == '"' && !inSingle -> inDouble = !inDouble
                    ch == ':' && !inSingle && !inDouble -> return i
                }
            }
            return -1
        }

        /** Claude/OpenMinis 技能名规范：小写、空白折叠为连字符、上限 64 字符。 */
        private fun normalizeSkillName(raw: String): String =
            raw.trim().lowercase().replace(Regex("\\s+"), "-").take(64)
    }
}

/**
 * 除 `isEnabled` 外的内容是否完全一致。
 *
 * 用于 [AgentSkillRepository.ensureInitialized] 判断内置技能是否需要同步：
 * `isEnabled` 是**用户的选择**，不属于"内容"，不能拿它来判定"要不要重播种"
 * （否则用户关掉某个技能后，下次启动就会把自己的选择判成"内容不同"而互相覆盖）。
 */
private fun AgentSkillEntity.contentEqualsIgnoringEnabled(other: AgentSkillEntity): Boolean =
    name == other.name &&
        description == other.description &&
        systemPrompt == other.systemPrompt &&
        triggerCommand == other.triggerCommand &&
        iconName == other.iconName &&
        isBuiltin == other.isBuiltin &&
        isImmutable == other.isImmutable &&
        category == other.category &&
        resourcePath == other.resourcePath

private fun AgentSkillEntity.toModel() = AgentSkill(
    id = id,
    name = name,
    description = description,
    systemPrompt = systemPrompt,
    triggerCommand = triggerCommand,
    iconName = iconName,
    isEnabled = isEnabled,
    isBuiltin = isBuiltin,
    isImmutable = isImmutable,
    category = category,
    resourcePath = resourcePath,
)

private fun AgentSkill.toEntity() = AgentSkillEntity(
    id = id,
    name = name,
    description = description,
    systemPrompt = systemPrompt,
    triggerCommand = triggerCommand,
    iconName = iconName,
    isEnabled = isEnabled,
    isBuiltin = isBuiltin,
    isImmutable = isImmutable,
    category = category,
    resourcePath = resourcePath,
)
