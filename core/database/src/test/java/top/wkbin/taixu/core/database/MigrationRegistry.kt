package top.wkbin.taixu.core.database

/**
 * 迁移注册表的只读探针，供 [DatabaseMigrationChainTest] 做护栏校验。
 *
 * 为什么读源码文本而不是反射：
 * 1. 迁移列表写在 [top.wkbin.taixu.di.AppModule.provideDatabase] 的 `addMigrations(...)`
 *    调用里，而 app 模块不在 core/database 单测的类路径上，无法反射取得；
 * 2. `@Database(version = ...)` 的保留策略不足以稳定支持运行时反射读取。
 *
 * 读源码恰好还能覆盖"定义了迁移对象却忘记注册"这一最易发生的疏漏——
 * 这正是 2026-09 审查中发现的真实缺陷（MIGRATION_29_30 / MIGRATION_32_33 未注册）。
 */
internal object MigrationRegistry {

    private const val MIGRATIONS_FILE =
        "core/database/src/main/java/top/wkbin/taixu/core/database/DatabaseMigrations.kt"
    private const val APP_MODULE_FILE =
        "app/src/main/java/top/wkbin/taixu/di/AppModule.kt"
    private const val APP_DATABASE_FILE =
        "core/database/src/main/java/top/wkbin/taixu/core/database/AppDatabase.kt"

    /**
     * 定位源码根目录：从当前工作目录逐级向上回溯，直到找到数据库迁移源码。
     *
     * Gradle 单测的工作目录通常是模块目录（如 core/database），故需向上回溯若干层。
     * 找不到时返回 null，由调用方决定是跳过还是失败。
     */
    private fun sourceRoot(): java.io.File? {
        var dir: java.io.File? = java.io.File("").absoluteFile
        repeat(10) {
            val current = dir ?: return null
            if (java.io.File(current, MIGRATIONS_FILE).exists()) return current
            dir = current.parentFile
        }
        return null
    }

    /** 源码是否可定位（供测试用 assumeTrue 判定，避免环境差异导致误报失败）。 */
    fun isSourceAvailable(): Boolean = sourceRoot() != null

    private fun readOrEmpty(relativePath: String): String {
        val root = sourceRoot() ?: return ""
        val file = java.io.File(root, relativePath)
        if (!file.exists()) return ""
        return runCatching { file.readText() }.getOrDefault("")
    }

    /**
     * DatabaseMigrations.kt 中所有 `val MIGRATION_a_b = ...` 形式的迁移常量名。
     */
    fun declaredMigrations(): List<String> {
        val text = readOrEmpty(MIGRATIONS_FILE)
        if (text.isEmpty()) return emptyList()
        return Regex("val\\s+(MIGRATION_\\d+_\\d+)\\s*=")
            .findAll(text)
            .map { it.groupValues[1] }
            .distinct()
            .sorted()
            .toList()
    }

    /** AppModule.kt 中 `addMigrations(...)` 调用里实际列出的迁移常量名。 */
    fun registeredMigrationNames(): Set<String> {
        val text = readOrEmpty(APP_MODULE_FILE)
        if (text.isEmpty()) return emptySet()
        val call = Regex("addMigrations\\(([\\s\\S]*?)\\)").find(text) ?: return emptySet()
        return Regex("MIGRATION_\\d+_\\d+")
            .findAll(call.groupValues[1])
            .map { it.value }
            .toSet()
    }

    /** AppDatabase.kt 中 `@Database(... version = N ...)` 声明的版本号；解析失败返回 null。 */
    fun declaredDatabaseVersion(): Int? {
        val text = readOrEmpty(APP_DATABASE_FILE)
        if (text.isEmpty()) return null
        return Regex("version\\s*=\\s*(\\d+)")
            .find(text)
            ?.groupValues
            ?.get(1)
            ?.toIntOrNull()
    }
}
