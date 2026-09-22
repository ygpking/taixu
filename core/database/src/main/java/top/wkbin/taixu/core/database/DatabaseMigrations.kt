package top.wkbin.taixu.core.database

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** 保留已有模型档案，为多 Key 轮询追加非敏感配置列。 */
val MIGRATION_27_28 = object : Migration(27, 28) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE harness_models ADD COLUMN apiKeyCount INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE harness_models ADD COLUMN requestsPerMinutePerKey INTEGER NOT NULL DEFAULT 0")
    }
}

/** 新建快捷短语与常用指令表 quick_phrases */
val MIGRATION_28_29 = object : Migration(28, 29) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS quick_phrases (
                id TEXT NOT NULL PRIMARY KEY,
                title TEXT NOT NULL,
                content TEXT NOT NULL,
                description TEXT NOT NULL DEFAULT '',
                iconName TEXT NOT NULL DEFAULT 'Play',
                targetProjectType TEXT,
                isEnabled INTEGER NOT NULL DEFAULT 1,
                sortOrder INTEGER NOT NULL DEFAULT 0,
                isBuiltin INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent(),
        )
    }
}

/**
 * harness 消息存储从单表 harness_messages 迁移到树形 lanes/entries 模型。
 *
 * 此前 29→30 的迁移从未注册：停留在 v29 schema 的存量设备升级时 Room 找不到
 * 迁移路径，fallbackToDestructiveMigration 会删除整库。补上该迁移后，此类设备
 * 可正常升级；唯一代价是旧 harness_messages 行无法机械映射为 lanes/entries，
 * 不做搬运（升级前该数据在 v29 上早已无法被当前代码读取）。
 *
 * DDL 必须与 schemas/30.json 的 createSql 逐字一致（含索引），否则 Room 校验
 * 失败仍会触发破坏性回退。
 */
val MIGRATION_29_30 = object : Migration(29, 30) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("DROP TABLE IF EXISTS harness_messages")
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS `harness_entries` (`sequence` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `id` TEXT NOT NULL, `sessionId` TEXT NOT NULL, `parentId` TEXT, `createdAt` INTEGER NOT NULL, `entryType` TEXT NOT NULL, `customType` TEXT, `payloadJson` TEXT NOT NULL)""",
        )
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_harness_entries_id` ON `harness_entries` (`id`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_harness_entries_sessionId_sequence` ON `harness_entries` (`sessionId`, `sequence`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_harness_entries_sessionId_parentId` ON `harness_entries` (`sessionId`, `parentId`)")
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS `harness_lanes` (`sessionId` TEXT NOT NULL, `name` TEXT NOT NULL, `leafId` TEXT, `currentOperationId` TEXT, `modelId` TEXT, `thinkingLevel` TEXT NOT NULL, `faulted` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`sessionId`, `name`))""",
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_harness_lanes_sessionId_currentOperationId` ON `harness_lanes` (`sessionId`, `currentOperationId`)")
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS `harness_operations` (`id` TEXT NOT NULL, `sessionId` TEXT NOT NULL, `laneName` TEXT NOT NULL, `kind` TEXT NOT NULL, `status` TEXT NOT NULL, `phase` TEXT NOT NULL, `startedAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, `startLeafId` TEXT, `stateJson` TEXT NOT NULL, `pendingEffectKind` TEXT, `pendingEffectId` TEXT, `replayPolicy` TEXT, `attempt` INTEGER NOT NULL, PRIMARY KEY(`id`))""",
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_harness_operations_sessionId_laneName` ON `harness_operations` (`sessionId`, `laneName`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_harness_operations_status_updatedAt` ON `harness_operations` (`status`, `updatedAt`)")
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS `harness_queue_items` (`id` TEXT NOT NULL, `sessionId` TEXT NOT NULL, `laneName` TEXT NOT NULL, `operationId` TEXT, `queueType` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, `payloadJson` TEXT NOT NULL, PRIMARY KEY(`id`))""",
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_harness_queue_items_sessionId_laneName_queueType_createdAt` ON `harness_queue_items` (`sessionId`, `laneName`, `queueType`, `createdAt`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_harness_queue_items_operationId` ON `harness_queue_items` (`operationId`)")
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS `harness_usage` (`sequence` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `id` TEXT NOT NULL, `sessionId` TEXT NOT NULL, `operationId` TEXT, `entryId` TEXT, `provider` TEXT, `modelId` TEXT, `inputTokens` INTEGER NOT NULL, `outputTokens` INTEGER NOT NULL, `reasoningTokens` INTEGER NOT NULL, `cacheReadTokens` INTEGER NOT NULL, `cacheWriteTokens` INTEGER NOT NULL, `estimatedCostUsd` REAL, `adjustment` INTEGER NOT NULL, `detailsJson` TEXT, `createdAt` INTEGER NOT NULL)""",
        )
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_harness_usage_id` ON `harness_usage` (`id`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_harness_usage_sessionId_sequence` ON `harness_usage` (`sessionId`, `sequence`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_harness_usage_operationId` ON `harness_usage` (`operationId`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_harness_usage_entryId` ON `harness_usage` (`entryId`)")
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS `harness_lane_results` (`sessionId` TEXT NOT NULL, `laneName` TEXT NOT NULL, `operationId` TEXT NOT NULL, `outcome` TEXT NOT NULL, `finalEntryId` TEXT, `detailsJson` TEXT, `completedAt` INTEGER NOT NULL, PRIMARY KEY(`sessionId`, `laneName`))""",
        )
    }
}

/**
 * 审批请求绑定 harness operation 与参数摘要，并引入过期时间：
 * - operationId：审批所属运行，恢复执行前校验归属，防跨运行重放；
 * - argsHash：argumentsJson 的 SHA-256，防"批准旧参数、执行新参数"；
 * - expiresAt：审批有效期（存量行填 Long.MAX_VALUE 表示永不过期）。
 */
val MIGRATION_30_31 = object : Migration(30, 31) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE agent_approval_requests ADD COLUMN operationId TEXT")
        db.execSQL("ALTER TABLE agent_approval_requests ADD COLUMN argsHash TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE agent_approval_requests ADD COLUMN expiresAt INTEGER NOT NULL DEFAULT 9223372036854775807")
    }
}

/** 为模型档案追加 Responses API 开关列。 */
val MIGRATION_31_32 = object : Migration(31, 32) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE harness_models ADD COLUMN responseApiEnabled INTEGER NOT NULL DEFAULT 0")
    }
}

/** v32→v33 仅有 agent_skills 新增 resourcePath 列（此前迁移缺失，v32 存量库升级会整库销毁）。 */
val MIGRATION_32_33 = object : Migration(32, 33) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE agent_skills ADD COLUMN resourcePath TEXT")
    }
}

/** Privileged Android application inventory used by Settings and the Agent. */
val MIGRATION_33_34 = object : Migration(33, 34) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("""CREATE TABLE IF NOT EXISTS android_apps (packageName TEXT NOT NULL PRIMARY KEY, label TEXT NOT NULL, uid INTEGER NOT NULL, apkPath TEXT NOT NULL, isSystemApp INTEGER NOT NULL, isEnabled INTEGER NOT NULL, isSuspended INTEGER NOT NULL, isNetworkRestricted INTEGER NOT NULL, lastSyncedAt INTEGER NOT NULL)""")
    }
}

/** Reusable workshop scripts and explicit per-project script selection. */
val MIGRATION_34_35 = object : Migration(34, 35) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("""CREATE TABLE IF NOT EXISTS build_scripts (id TEXT NOT NULL PRIMARY KEY, name TEXT NOT NULL, description TEXT NOT NULL DEFAULT '', projectType TEXT NOT NULL, content TEXT NOT NULL, isBuiltin INTEGER NOT NULL DEFAULT 0, createdAt INTEGER NOT NULL, updatedAt INTEGER NOT NULL)""")
        db.execSQL("""CREATE TABLE IF NOT EXISTS project_build_script_bindings (projectName TEXT NOT NULL PRIMARY KEY, scriptId TEXT NOT NULL, updatedAt INTEGER NOT NULL)""")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_project_build_script_bindings_scriptId ON project_build_script_bindings(scriptId)")
    }
}

/**
 * 修正 mcp_codegraph 内置预设的错误默认启用状态。
 * 上一次提交以 isEnabled=1 写入，但设备上尚无 /opt/taixu/scripts/codegraph_mcp_server.py，
 * 导致 discoverTools() 120s 超时，阻塞 Agent 首次启动。
 */
val MIGRATION_35_36 = object : Migration(35, 36) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("UPDATE mcp_servers SET isEnabled = 0 WHERE id = 'mcp_codegraph' AND isBuiltin = 1")
    }
}

val MIGRATION_36_37 = object : Migration(36, 37) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS agent_tasks (id TEXT NOT NULL PRIMARY KEY, title TEXT NOT NULL, description TEXT NOT NULL, status TEXT NOT NULL, createdAt INTEGER NOT NULL, updatedAt INTEGER NOT NULL, errorMessage TEXT, progress REAL NOT NULL)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_agent_tasks_status ON agent_tasks(status)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_agent_tasks_updatedAt ON agent_tasks(updatedAt)")
    }
}

/** Give project/session memories an explicit owner so they cannot leak across contexts. */
val MIGRATION_37_38 = object : Migration(37, 38) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE agent_memories ADD COLUMN ownerId TEXT NOT NULL DEFAULT ''")
        // Existing non-global rows have no trustworthy owner. Keep them for manual recovery,
        // but exclude them from every live project/session context.
        db.execSQL("UPDATE agent_memories SET ownerId = 'legacy-unscoped' WHERE scope != 'global'")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_agent_memories_scope_ownerId_key ON agent_memories(scope, ownerId, `key`)")
    }
}

/** Bind a concrete provider model variant to each chat session. */
val MIGRATION_38_39 = object : Migration(38, 39) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE harness_sessions ADD COLUMN modelVariant TEXT")
    }
}

/** Replace the legacy flat built-in roles with a versioned, department-aware catalog. */
val MIGRATION_39_40 = object : Migration(39, 40) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE agent_subagents ADD COLUMN departmentId TEXT NOT NULL DEFAULT 'custom'")
        db.execSQL("ALTER TABLE agent_subagent_settings ADD COLUMN catalogRevision TEXT NOT NULL DEFAULT ''")
    }
}

/** Connect legacy task rows to Harness sessions and persist restart-safe lifecycle checkpoints. */
val MIGRATION_40_41 = object : Migration(40, 41) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE agent_tasks ADD COLUMN sessionId TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE agent_tasks ADD COLUMN operationId TEXT")
        db.execSQL("ALTER TABLE agent_tasks ADD COLUMN startedAt INTEGER")
        db.execSQL("ALTER TABLE agent_tasks ADD COLUMN completedAt INTEGER")
        db.execSQL("ALTER TABLE agent_tasks ADD COLUMN nextRunAt INTEGER")
        db.execSQL("ALTER TABLE agent_tasks ADD COLUMN attemptCount INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE agent_tasks ADD COLUMN maxAttempts INTEGER NOT NULL DEFAULT 2")
        db.execSQL("ALTER TABLE agent_tasks ADD COLUMN autoResume INTEGER NOT NULL DEFAULT 1")
        db.execSQL("ALTER TABLE agent_tasks ADD COLUMN lastRound INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE agent_tasks ADD COLUMN maxRounds INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE agent_tasks ADD COLUMN statusDetail TEXT")
        // Legacy RUNNING rows intentionally stay RUNNING here. Their empty sessionId makes the
        // recovery pass classify them as exhausted and move them to SUSPENDED with an explanation.
        db.execSQL("UPDATE agent_tasks SET status = 'SUSPENDED', statusDetail = '旧任务未绑定会话，需手动重新发起' WHERE status IN ('IDLE', 'SUSPENDED')")
        db.execSQL("UPDATE agent_tasks SET status = 'FAILED' WHERE status = 'ERROR'")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_agent_tasks_sessionId ON agent_tasks(sessionId)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_agent_tasks_nextRunAt ON agent_tasks(nextRunAt)")
    }
}

/** Explicit image-generation capability; existing model profiles remain disabled after upgrade. */
val MIGRATION_41_42 = object : Migration(41, 42) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE harness_models ADD COLUMN imageGenerationEnabled INTEGER NOT NULL DEFAULT 0")
    }
}

/** 跟踪内置 MCP 是否被用户手动切换过启停：0 = 跟随预设默认值，1 = 尊重用户选择。 */
val MIGRATION_42_43 = object : Migration(42, 43) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE mcp_servers ADD COLUMN userToggled INTEGER NOT NULL DEFAULT 0")
    }
}

/**
 * 记忆语义扩展（Reasonix Context Engine v2）：主题冲突去重 / revision / pinned / 新鲜度。
 * 存量记忆打 subjectKey=原 key、revision=1、fresh，保证无损升级且幂等。
 */
val MIGRATION_43_44 = object : Migration(43, 44) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE agent_memories ADD COLUMN subjectKey TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE agent_memories ADD COLUMN revision INTEGER NOT NULL DEFAULT 1")
        db.execSQL("ALTER TABLE agent_memories ADD COLUMN pinned INTEGER NOT NULL DEFAULT 0")
        // 注意：DEFAULT null 必须小写——Room 校验 default 值时按 PRAGMA 返回的 DDL 字面量逐字比较（大小写敏感），
        // 须与 44.json createSql（由实体 @ColumnInfo(defaultValue = "null") 生成）完全一致。
        db.execSQL("ALTER TABLE agent_memories ADD COLUMN expiresAt INTEGER DEFAULT null")
        db.execSQL("ALTER TABLE agent_memories ADD COLUMN lastVerifiedAt INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE agent_memories ADD COLUMN volatility TEXT NOT NULL DEFAULT 'reference'")
        // 存量记忆以 key 作为主题键（幂等：重复执行时新库已是空表或已回填）。
        db.execSQL("UPDATE agent_memories SET subjectKey = `key` WHERE subjectKey = ''")
        // 索引名必须与 Room 生成的 schema（44.json）完全一致，否则迁移后校验失败触发破坏性回退
        db.execSQL("CREATE INDEX IF NOT EXISTS index_agent_memories_scope_ownerId_subjectKey ON agent_memories(scope, ownerId, subjectKey)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_agent_memories_pinned ON agent_memories(pinned)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_agent_memories_expiresAt ON agent_memories(expiresAt)")
    }
}

/** Allow each subagent role to bind a default saved model profile. Null keeps parent-model inheritance. */
val MIGRATION_44_45 = object : Migration(44, 45) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE agent_subagents ADD COLUMN defaultModelId TEXT DEFAULT null")
        db.execSQL("ALTER TABLE agent_subagents ADD COLUMN defaultModelVariant TEXT DEFAULT null")
    }
}

/**
 * 修复 v45 schema hash 不一致问题：
 * 先前 session 安装的 APK 携带了一个未记录的中间态 agent_tasks schema
 * （identityHash: 2e093ad32ea2eac8760797ede44e8110）。
 *
 * 此迁移重建 agent_tasks 表（保留数据），并补齐 agent_subagents 可能缺失的
 * defaultModelId / defaultModelVariant 列（幂等，已存在则跳过）。
 */
val MIGRATION_45_46 = object : Migration(45, 46) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // ① 重建 agent_tasks，保证 schema 与 v46 entity 完全一致
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS agent_tasks_new (
                `id` TEXT NOT NULL,
                `sessionId` TEXT NOT NULL DEFAULT '',
                `title` TEXT NOT NULL,
                `description` TEXT NOT NULL,
                `status` TEXT NOT NULL,
                `createdAt` INTEGER NOT NULL,
                `updatedAt` INTEGER NOT NULL,
                `errorMessage` TEXT,
                `progress` REAL NOT NULL,
                `operationId` TEXT,
                `startedAt` INTEGER,
                `completedAt` INTEGER,
                `nextRunAt` INTEGER,
                `attemptCount` INTEGER NOT NULL DEFAULT 0,
                `maxAttempts` INTEGER NOT NULL DEFAULT 2,
                `autoResume` INTEGER NOT NULL DEFAULT 1,
                `lastRound` INTEGER NOT NULL DEFAULT 0,
                `maxRounds` INTEGER NOT NULL DEFAULT 0,
                `statusDetail` TEXT,
                PRIMARY KEY(`id`)
            )
            """.trimIndent(),
        )
        // 迁移已有行（仅迁移公共列，新增列使用 DEFAULT）
        db.execSQL(
            """
            INSERT OR IGNORE INTO agent_tasks_new
                (id, sessionId, title, description, status, createdAt, updatedAt,
                 errorMessage, progress, operationId, startedAt, completedAt,
                 nextRunAt, attemptCount, maxAttempts, autoResume, lastRound, maxRounds, statusDetail)
            SELECT
                id,
                COALESCE(sessionId, '') AS sessionId,
                title, description, status, createdAt, updatedAt,
                errorMessage, progress, operationId, startedAt, completedAt,
                nextRunAt,
                COALESCE(attemptCount, 0), COALESCE(maxAttempts, 2),
                COALESCE(autoResume, 1), COALESCE(lastRound, 0), COALESCE(maxRounds, 0),
                statusDetail
            FROM agent_tasks
            """.trimIndent(),
        )
        db.execSQL("DROP TABLE agent_tasks")
        db.execSQL("ALTER TABLE agent_tasks_new RENAME TO agent_tasks")
        // 重建索引
        db.execSQL("CREATE INDEX IF NOT EXISTS index_agent_tasks_status ON agent_tasks(status)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_agent_tasks_updatedAt ON agent_tasks(updatedAt)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_agent_tasks_sessionId ON agent_tasks(sessionId)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_agent_tasks_nextRunAt ON agent_tasks(nextRunAt)")

        // ② 补齐 agent_subagents 列（已存在时 SQLite 会报错，用 runCatching 保护）
        runCatching {
            db.execSQL("ALTER TABLE agent_subagents ADD COLUMN defaultModelId TEXT DEFAULT null")
        }
        runCatching {
            db.execSQL("ALTER TABLE agent_subagents ADD COLUMN defaultModelVariant TEXT DEFAULT null")
        }
    }
}

/** Adds durable workflow definitions and restart-visible execution history. */
val MIGRATION_46_47 = object : Migration(46, 47) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS `workflows` (`id` TEXT NOT NULL, `name` TEXT NOT NULL, `description` TEXT NOT NULL, `category` TEXT NOT NULL, `isBuiltin` INTEGER NOT NULL, `slashCommand` TEXT, `jsonContent` TEXT NOT NULL, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`id`))""",
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_workflows_slashCommand` ON `workflows` (`slashCommand`)")
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS `workflow_execution_logs` (`executionId` TEXT NOT NULL, `workflowId` TEXT NOT NULL, `startTime` INTEGER NOT NULL, `endTime` INTEGER, `status` TEXT NOT NULL, `finalContextJson` TEXT NOT NULL, PRIMARY KEY(`executionId`), FOREIGN KEY(`workflowId`) REFERENCES `workflows`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )""",
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_workflow_execution_logs_workflowId` ON `workflow_execution_logs` (`workflowId`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_workflow_execution_logs_startTime` ON `workflow_execution_logs` (`startTime`)")
    }
}

/** Adds per-model compaction budget overrides (pi-style compaction.modelOverrides). */
val MIGRATION_47_48 = object : Migration(47, 48) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "ALTER TABLE `harness_models` ADD COLUMN `compactionKeepRecentTokens` INTEGER",
        )
        db.execSQL(
            "ALTER TABLE `harness_models` ADD COLUMN `compactionReserveTokens` INTEGER",
        )
    }
}

/**
 * 新增「单次输入上限」列：每轮请求主动裁切到的目标水位（裁切基准）。
 *
 * 与 `contextTokens`（窗口能力声明）语义不同：此前裁切基准直接取窗口值，
 * 用户把窗口填成 1_000_000 后折叠触发线升到 ~98.7 万，历史堆到 38 万也不折叠 → HTTP 413。
 * null = 未显式配置，由 ContextBudgetDefaults.resolveInputLimit 按窗口推导。
 */
val MIGRATION_48_49 = object : Migration(48, 49) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "ALTER TABLE `harness_models` ADD COLUMN `inputTokenLimit` INTEGER",
        )
    }
}

/**
 * 技能名唯一约束（第三波，本单唯一不可逆项）。
 *
 * 背景：`agent_skills` 只有 id 主键，name 无唯一约束。update 进化落库曾以 LLM 提案名覆盖
 * 目标技能名（第一波已修），此前被改过名的设备上可能已存在两个同名启用技能——
 * `load_skill` 同名精确匹配多条即报「匹配到多个技能」，斜杠命令也会被 distinctBy 静默吞掉。
 *
 * 因此这一步是**先去重、再建唯一索引**：直接 CREATE UNIQUE INDEX 在含重复行的表上会失败，
 * 而失败会被 fallbackToDestructiveMigration 接住 → 整库销毁。去重保留 updatedAt 最新的一行
 * （技能内容的"最后写入者"），同刻则保留 id 字典序较小者以保证结果确定。
 */
val MIGRATION_49_50 = object : Migration(49, 50) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // 重名消歧：每组同名技能里保留 id 最小的一行原名，其余追加 "-" + id 末 4 位。
        //
        // 为什么是"改名"而不是"删行"：agent_skills 没有 updatedAt 之类的时光标，
        // 无法判断哪一行是用户更想要的内容；删错就是不可恢复的数据丢失。追加后缀则两边都保住，
        // 用户可在设置里自行合并或删除。后缀取 id 末 4 位（id 形如 custom_1a2b3c4d / builtin_xxx），
        // 足够区分且不依赖任何排序窗口函数。
        db.execSQL(
            """
            UPDATE `agent_skills` SET `name` = `name` || '-' || replace(substr(`id`, -4), '_', '')
            WHERE `id` IN (
                SELECT a.`id` FROM `agent_skills` a
                WHERE (SELECT COUNT(*) FROM `agent_skills` b WHERE b.`name` = a.`name`) > 1
                  AND EXISTS (
                      SELECT 1 FROM `agent_skills` b
                      WHERE b.`name` = a.`name` AND b.`id` < a.`id`
                  )
            )
            """.trimIndent(),
        )
        // 唯一索引：此后重名写入直接被 DB 挡下（update 进化落库与 create 去重都依赖这一点）。
        db.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_agent_skills_name` ON `agent_skills` (`name`)",
        )
    }
}
