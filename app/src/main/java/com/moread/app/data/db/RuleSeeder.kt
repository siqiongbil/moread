package com.moread.app.data.db

import android.content.Context
import com.moread.app.core.model.RulePattern
import com.moread.app.core.model.TocLevel
import org.json.JSONObject

/** 内置规则种子 + 规则导入导出（与 assets/toc_rules.json 同一 JSON 格式）。 */
object RuleSeeder {

    fun toRulePattern(entity: RuleEntity): RulePattern? {
        val level = if (entity.level == "VOLUME") TocLevel.VOLUME else TocLevel.CHAPTER
        val regex = runCatching { Regex(entity.pattern) }.getOrNull() ?: return null
        return RulePattern(
            id = entity.id,
            name = entity.name,
            regex = regex,
            level = level,
            enabled = entity.enabled,
            sortOrder = entity.sortOrder,
            builtIn = entity.builtIn,
        )
    }

    suspend fun seedIfEmpty(context: Context, dao: RuleDao) {
        if (dao.count() > 0) return
        val json = context.assets.open("toc_rules.json").bufferedReader().use { it.readText() }
        dao.insertAll(parse(json))
    }

    suspend fun resetToDefaults(context: Context, dao: RuleDao) {
        val json = context.assets.open("toc_rules.json").bufferedReader().use { it.readText() }
        dao.deleteAll()
        dao.insertAll(parse(json))
    }

    fun parse(json: String): List<RuleEntity> {
        val root = JSONObject(json)
        val arr = root.optJSONArray("rules") ?: return emptyList()
        val out = ArrayList<RuleEntity>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val pattern = o.optString("pattern")
            if (pattern.isEmpty() || runCatching { Regex(pattern) }.isFailure) continue
            out += RuleEntity(
                name = o.optString("name").ifEmpty { "未命名规则" },
                pattern = pattern,
                level = if (o.optString("level") == "VOLUME") "VOLUME" else "CHAPTER",
                enabled = o.optBoolean("enabled", true),
                sortOrder = o.optInt("sortOrder", 100),
                builtIn = o.optBoolean("builtIn", false),
            )
        }
        return out
    }

    fun export(rules: List<RuleEntity>): String {
        val root = JSONObject()
        val arr = org.json.JSONArray()
        for (r in rules) {
            val o = JSONObject()
            o.put("name", r.name)
            o.put("pattern", r.pattern)
            o.put("level", r.level)
            o.put("enabled", r.enabled)
            o.put("sortOrder", r.sortOrder)
            o.put("builtIn", r.builtIn)
            arr.put(o)
        }
        root.put("rules", arr)
        return root.toString(2)
    }
}
