package com.toolkit.privacy

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.text.format.Formatter
import androidx.core.content.pm.PackageInfoCompat
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** An installed app plus everything the organizer layers on top of it (local name, tags, pin/hide flags, size, install date). */
data class OrgApp(
    val base: AppEntry,
    val label: String,
    val category: String,
    val tags: List<String>,
    val pinned: Boolean,
    val hidden: Boolean,
    val installed: Long,
    val sizeBytes: Long
) {
    val pkg: String get() = base.pkg
    val system: Boolean get() = base.system
}

/** Everything the organizer remembers, kept in the shared prefs file. Nothing here touches the real apps. */
object OrgStore {
    private const val PINNED = "org_pinned"
    private const val HIDDEN = "org_hidden"

    fun style(ctx: Context): String = Prefs.sp(ctx).getString("org_style", "Grid") ?: "Grid"
    fun setStyle(ctx: Context, s: String) { Prefs.sp(ctx).edit().putString("org_style", s).apply() }
    fun interval(ctx: Context): Int = Prefs.sp(ctx).getInt("org_interval", 4)
    fun setInterval(ctx: Context, sec: Int) { Prefs.sp(ctx).edit().putInt("org_interval", sec).apply() }
    fun sort(ctx: Context): String = Prefs.sp(ctx).getString("org_sort", "Folders") ?: "Folders"
    fun setSort(ctx: Context, s: String) { Prefs.sp(ctx).edit().putString("org_sort", s).apply() }

    private fun set(ctx: Context, key: String): Set<String> = Prefs.sp(ctx).getStringSet(key, emptySet()) ?: emptySet()
    fun pinned(ctx: Context): Set<String> = set(ctx, PINNED)
    fun hidden(ctx: Context): Set<String> = set(ctx, HIDDEN)

    private fun toggle(ctx: Context, key: String, pkg: String) {
        val cur = HashSet(set(ctx, key))
        if (!cur.add(pkg)) cur.remove(pkg)
        Prefs.sp(ctx).edit().putStringSet(key, cur).apply()
    }
    fun togglePin(ctx: Context, pkg: String) = toggle(ctx, PINNED, pkg)
    fun toggleHide(ctx: Context, pkg: String) = toggle(ctx, HIDDEN, pkg)

    fun name(ctx: Context, pkg: String): String? = Prefs.sp(ctx).getString("org_name_$pkg", null)?.takeIf { it.isNotBlank() }
    fun setName(ctx: Context, pkg: String, v: String) {
        val e = Prefs.sp(ctx).edit()
        if (v.isBlank()) e.remove("org_name_$pkg") else e.putString("org_name_$pkg", v.trim())
        e.apply()
    }

    fun tags(ctx: Context, pkg: String): List<String> =
        (Prefs.sp(ctx).getString("org_tags_$pkg", "") ?: "").split(',').map { it.trim() }.filter { it.isNotEmpty() }
    fun setTags(ctx: Context, pkg: String, raw: String) {
        val clean = raw.split(',', ';').map { it.trim() }.filter { it.isNotEmpty() }.distinct().joinToString(",")
        val e = Prefs.sp(ctx).edit()
        if (clean.isEmpty()) e.remove("org_tags_$pkg") else e.putString("org_tags_$pkg", clean)
        e.apply()
    }

    /** Install time + apk size for every app; slow-ish, call off the main thread. */
    @Suppress("DEPRECATION")
    fun loadMeta(ctx: Context, apps: List<AppEntry>): Map<String, Pair<Long, Long>> {
        val pm = ctx.packageManager
        val out = HashMap<String, Pair<Long, Long>>()
        for (a in apps) {
            try {
                val pi = pm.getPackageInfo(a.pkg, 0)
                val size = try { File(pi.applicationInfo?.sourceDir ?: "").length() } catch (e: Exception) { 0L }
                out[a.pkg] = pi.firstInstallTime to size
            } catch (e: Exception) { out[a.pkg] = 0L to 0L }
        }
        return out
    }

    fun decorate(ctx: Context, apps: List<AppEntry>, meta: Map<String, Pair<Long, Long>>): List<OrgApp> {
        val pins = pinned(ctx); val hides = hidden(ctx)
        return apps.map { a ->
            val m = meta[a.pkg]
            OrgApp(a, name(ctx, a.pkg) ?: a.label, AppCategories.categorize(a), tags(ctx, a.pkg),
                a.pkg in pins, a.pkg in hides, m?.first ?: 0L, m?.second ?: 0L)
        }
    }
}

object OrgActions {
    private fun newTask(i: Intent) = i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    fun open(ctx: Context, pkg: String): Boolean {
        val i = ctx.packageManager.getLaunchIntentForPackage(pkg) ?: return false
        return try { ctx.startActivity(newTask(i)); true } catch (e: Exception) { false }
    }
    fun info(ctx: Context, pkg: String) {
        try { ctx.startActivity(newTask(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$pkg")))) } catch (_: Exception) {}
    }
    fun uninstall(ctx: Context, pkg: String) {
        try { ctx.startActivity(newTask(Intent(Intent.ACTION_DELETE, Uri.parse("package:$pkg")))) } catch (_: Exception) {}
    }
    fun copy(ctx: Context, text: String) {
        (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("package", text))
    }

    @Suppress("DEPRECATION")
    fun details(ctx: Context, app: OrgApp): List<Pair<String, String>> {
        val pm = ctx.packageManager
        val out = ArrayList<Pair<String, String>>()
        val df = SimpleDateFormat("d MMM yyyy, HH:mm", Locale.getDefault())
        out += "Package" to app.pkg
        out += "Folder" to app.category + if (app.system) " (system app)" else ""
        try {
            val pi = if (Build.VERSION.SDK_INT >= 33) pm.getPackageInfo(app.pkg, PackageManager.PackageInfoFlags.of(PackageManager.GET_PERMISSIONS.toLong()))
                     else pm.getPackageInfo(app.pkg, PackageManager.GET_PERMISSIONS)
            out += "Version" to "${pi.versionName ?: "?"} (${PackageInfoCompat.getLongVersionCode(pi)})"
            out += "Installed" to df.format(Date(pi.firstInstallTime))
            out += "Last updated" to df.format(Date(pi.lastUpdateTime))
            pi.applicationInfo?.let { ai ->
                out += "Target SDK" to ai.targetSdkVersion.toString()
                if (Build.VERSION.SDK_INT >= 24) out += "Min SDK" to ai.minSdkVersion.toString()
                val f = File(ai.sourceDir ?: "")
                if (f.exists()) out += "APK size" to Formatter.formatFileSize(ctx, f.length())
                val splits = ai.splitSourceDirs
                if (splits != null && splits.isNotEmpty()) out += "Split APKs" to splits.size.toString()
            }
            out += "Permissions asked" to (pi.requestedPermissions?.size ?: 0).toString()
            val installer = try {
                if (Build.VERSION.SDK_INT >= 30) pm.getInstallSourceInfo(app.pkg).installingPackageName else pm.getInstallerPackageName(app.pkg)
            } catch (e: Exception) { null }
            out += "Installed by" to (installer ?: "unknown / sideloaded")
        } catch (e: Exception) { out += "Details" to "not readable" }
        if (app.base.lastUsedMs > 0) out += "Last opened" to df.format(Date(app.base.lastUsedMs))
        if (app.base.usedMs7d > 0) out += "Screen time (7 d)" to "${app.base.usedMs7d / 60000} min"
        if (app.tags.isNotEmpty()) out += "Your tags" to app.tags.joinToString(", ")
        return out
    }
}
