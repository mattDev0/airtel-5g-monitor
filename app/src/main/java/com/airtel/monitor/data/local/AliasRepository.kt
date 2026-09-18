package com.airtel.monitor.data.local

import android.content.Context
import com.airtel.monitor.data.remote.AliasLookup
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap

class AliasRepository(private val context: Context) : AliasLookup {
    private val aliasesFile: File
        get() = File(context.filesDir, "device_aliases.json")

    private val aliasesCache = ConcurrentHashMap<String, String>()

    init {
        loadAliases()
    }

    fun getAliases(): Map<String, String> {
        return aliasesCache.toMap()
    }

    override fun getAlias(identifier: String, fallback: String): String {
        val key = identifier.lowercase().trim()
        return aliasesCache[key] ?: fallback
    }

    @Synchronized
    fun saveAlias(identifier: String, alias: String) {
        val key = identifier.lowercase().trim()
        if (alias.isBlank()) {
            aliasesCache.remove(key)
        } else {
            aliasesCache[key] = alias.trim()
        }
        persist()
    }

    private fun loadAliases() {
        try {
            if (aliasesFile.exists()) {
                val content = aliasesFile.readText(Charsets.UTF_8)
                val json = JSONObject(content)
                val keys = json.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    aliasesCache[k.lowercase().trim()] = json.optString(k, "")
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun persist() {
        try {
            val json = JSONObject()
            aliasesCache.forEach { (k, v) ->
                json.put(k, v)
            }
            aliasesFile.writeText(json.toString(2), Charsets.UTF_8)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
