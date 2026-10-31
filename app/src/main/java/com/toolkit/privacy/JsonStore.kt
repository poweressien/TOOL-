package com.toolkit.privacy

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Small on-device JSON files for histories (health, prices, trips...). Nothing leaves the phone. */
object JsonStore {
    private val lock = Any()

    private fun file(ctx: Context, name: String): File {
        val dir = File(ctx.applicationContext.filesDir, "data").apply { mkdirs() }
        return File(dir, "$name.json")
    }

    fun readArray(ctx: Context, name: String): JSONArray = synchronized(lock) {
        try { val f = file(ctx, name); if (f.exists()) JSONArray(f.readText()) else JSONArray() } catch (e: Exception) { JSONArray() }
    }

    fun writeArray(ctx: Context, name: String, arr: JSONArray) = synchronized(lock) {
        try { file(ctx, name).writeText(arr.toString()) } catch (e: Exception) { }
    }

    fun append(ctx: Context, name: String, o: JSONObject, max: Int = 3000) = synchronized(lock) {
        val a = readArray(ctx, name)
        a.put(o)
        val out = if (a.length() > max) JSONArray().also { n -> for (i in a.length() - max until a.length()) n.put(a.get(i)) } else a
        writeArray(ctx, name, out)
    }

    fun readObject(ctx: Context, name: String): JSONObject = synchronized(lock) {
        try { val f = file(ctx, name); if (f.exists()) JSONObject(f.readText()) else JSONObject() } catch (e: Exception) { JSONObject() }
    }

    fun writeObject(ctx: Context, name: String, o: JSONObject) = synchronized(lock) {
        try { file(ctx, name).writeText(o.toString()) } catch (e: Exception) { }
    }

    fun list(ctx: Context, name: String): List<JSONObject> {
        val a = readArray(ctx, name)
        return (0 until a.length()).mapNotNull { a.optJSONObject(it) }
    }

    fun saveList(ctx: Context, name: String, items: List<JSONObject>) {
        val a = JSONArray(); items.forEach { a.put(it) }; writeArray(ctx, name, a)
    }
}
