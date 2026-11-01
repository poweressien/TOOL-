package com.toolkit.privacy

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.net.Uri
import com.toolkit.privacy.R

/** One built-in notification sound. All files live in res/raw and are used only for this app's notifications. */
class NSound(val id: String, val name: String, val category: String, val res: Int)

object SoundCatalog {
    val all: List<NSound> = listOf(
    NSound("ns_001", "Crystal Bell", "Chimes", R.raw.ns_001),
    NSound("ns_002", "Soft Bell", "Chimes", R.raw.ns_002),
    NSound("ns_003", "Church Bell", "Chimes", R.raw.ns_003),
    NSound("ns_004", "Temple Bell", "Chimes", R.raw.ns_004),
    NSound("ns_005", "Silver Bell", "Chimes", R.raw.ns_005),
    NSound("ns_006", "Door Bell", "Chimes", R.raw.ns_006),
    NSound("ns_007", "Hotel Bell", "Chimes", R.raw.ns_007),
    NSound("ns_008", "Fairy Bell", "Chimes", R.raw.ns_008),
    NSound("ns_009", "Rising Chime", "Chimes", R.raw.ns_009),
    NSound("ns_010", "Falling Chime", "Chimes", R.raw.ns_010),
    NSound("ns_011", "Triple Chime", "Chimes", R.raw.ns_011),
    NSound("ns_012", "Wind Chimes", "Chimes", R.raw.ns_012),
    NSound("ns_013", "Morning Chime", "Chimes", R.raw.ns_013),
    NSound("ns_014", "Evening Chime", "Chimes", R.raw.ns_014),
    NSound("ns_015", "Glass Tap", "Glass", R.raw.ns_015),
    NSound("ns_016", "Glass Ping", "Glass", R.raw.ns_016),
    NSound("ns_017", "Wine Glass", "Glass", R.raw.ns_017),
    NSound("ns_018", "Ice Ting", "Glass", R.raw.ns_018),
    NSound("ns_019", "Crystal Drop", "Glass", R.raw.ns_019),
    NSound("ns_020", "Prism", "Glass", R.raw.ns_020),
    NSound("ns_021", "Quick Ping", "Pings", R.raw.ns_021),
    NSound("ns_022", "Soft Ping", "Pings", R.raw.ns_022),
    NSound("ns_023", "Deep Ping", "Pings", R.raw.ns_023),
    NSound("ns_024", "High Ping", "Pings", R.raw.ns_024),
    NSound("ns_025", "Sonar Ping", "Pings", R.raw.ns_025),
    NSound("ns_026", "Radar Ping", "Pings", R.raw.ns_026),
    NSound("ns_027", "Bubble Pop", "Bubbles", R.raw.ns_027),
    NSound("ns_028", "Water Bloop", "Bubbles", R.raw.ns_028),
    NSound("ns_029", "Big Bloop", "Bubbles", R.raw.ns_029),
    NSound("ns_030", "Tiny Bloop", "Bubbles", R.raw.ns_030),
    NSound("ns_031", "Pop", "Bubbles", R.raw.ns_031),
    NSound("ns_032", "Double Pop", "Bubbles", R.raw.ns_032),
    NSound("ns_033", "Water Drop", "Bubbles", R.raw.ns_033),
    NSound("ns_034", "Rain Drop", "Bubbles", R.raw.ns_034),
    NSound("ns_035", "Deep Drop", "Bubbles", R.raw.ns_035),
    NSound("ns_036", "Echo Drop", "Bubbles", R.raw.ns_036),
    NSound("ns_037", "Marimba Hello", "Marimba", R.raw.ns_037),
    NSound("ns_038", "Marimba Up", "Marimba", R.raw.ns_038),
    NSound("ns_039", "Marimba Down", "Marimba", R.raw.ns_039),
    NSound("ns_040", "Marimba Skip", "Marimba", R.raw.ns_040),
    NSound("ns_041", "Marimba Pair", "Marimba", R.raw.ns_041),
    NSound("ns_042", "Marimba Triple", "Marimba", R.raw.ns_042),
    NSound("ns_043", "Kalimba Dream", "Kalimba", R.raw.ns_043),
    NSound("ns_044", "Kalimba Hop", "Kalimba", R.raw.ns_044),
    NSound("ns_045", "Kalimba Rain", "Kalimba", R.raw.ns_045),
    NSound("ns_046", "Kalimba Single", "Kalimba", R.raw.ns_046),
    NSound("ns_047", "Kalimba Duo", "Kalimba", R.raw.ns_047),
    NSound("ns_048", "Kalimba Lift", "Kalimba", R.raw.ns_048),
    NSound("ns_049", "Harp Glide", "Strings", R.raw.ns_049),
    NSound("ns_050", "Harp Fall", "Strings", R.raw.ns_050),
    NSound("ns_051", "Guitar Pluck", "Strings", R.raw.ns_051),
    NSound("ns_052", "Guitar Strum", "Strings", R.raw.ns_052),
    NSound("ns_053", "Banjo Pick", "Strings", R.raw.ns_053),
    NSound("ns_054", "Harp Sparkle", "Strings", R.raw.ns_054),
    NSound("ns_055", "Blip", "Synth", R.raw.ns_055),
    NSound("ns_056", "Double Blip", "Synth", R.raw.ns_056),
    NSound("ns_057", "Triple Blip", "Synth", R.raw.ns_057),
    NSound("ns_058", "Retro Up", "Synth", R.raw.ns_058),
    NSound("ns_059", "Retro Down", "Synth", R.raw.ns_059),
    NSound("ns_060", "Tri Beep", "Synth", R.raw.ns_060),
    NSound("ns_061", "Soft Beep", "Synth", R.raw.ns_061),
    NSound("ns_062", "Pulse Duo", "Synth", R.raw.ns_062),
    NSound("ns_063", "Saw Hit", "Synth", R.raw.ns_063),
    NSound("ns_064", "Digital Tick", "Synth", R.raw.ns_064),
    NSound("ns_065", "Coin", "Game", R.raw.ns_065),
    NSound("ns_066", "Power Up", "Game", R.raw.ns_066),
    NSound("ns_067", "Level Up", "Game", R.raw.ns_067),
    NSound("ns_068", "Bonus", "Game", R.raw.ns_068),
    NSound("ns_069", "Item Get", "Game", R.raw.ns_069),
    NSound("ns_070", "Jump", "Game", R.raw.ns_070),
    NSound("ns_071", "Gem", "Game", R.raw.ns_071),
    NSound("ns_072", "Laser", "Game", R.raw.ns_072),
    NSound("ns_073", "Zap", "Game", R.raw.ns_073),
    NSound("ns_074", "Warp", "Game", R.raw.ns_074),
    NSound("ns_075", "Power Down", "Game", R.raw.ns_075),
    NSound("ns_076", "Scan Up", "Sci-Fi", R.raw.ns_076),
    NSound("ns_077", "Scan Down", "Sci-Fi", R.raw.ns_077),
    NSound("ns_078", "Data Beam", "Sci-Fi", R.raw.ns_078),
    NSound("ns_079", "Hyper", "Sci-Fi", R.raw.ns_079),
    NSound("ns_080", "Beam Me", "Sci-Fi", R.raw.ns_080),
    NSound("ns_081", "Computer", "Sci-Fi", R.raw.ns_081),
    NSound("ns_082", "Transmission", "Sci-Fi", R.raw.ns_082),
    NSound("ns_083", "Dial Up", "Sci-Fi", R.raw.ns_083),
    NSound("ns_084", "Droid", "Sci-Fi", R.raw.ns_084),
    NSound("ns_085", "Soft Click", "Clicks", R.raw.ns_085),
    NSound("ns_086", "Hard Click", "Clicks", R.raw.ns_086),
    NSound("ns_087", "Tick", "Clicks", R.raw.ns_087),
    NSound("ns_088", "Tock", "Clicks", R.raw.ns_088),
    NSound("ns_089", "Double Click", "Clicks", R.raw.ns_089),
    NSound("ns_090", "Wood Block", "Clicks", R.raw.ns_090),
    NSound("ns_091", "Hollow Block", "Clicks", R.raw.ns_091),
    NSound("ns_092", "Clave", "Clicks", R.raw.ns_092),
    NSound("ns_093", "Tap", "Clicks", R.raw.ns_093),
    NSound("ns_094", "Snap", "Clicks", R.raw.ns_094),
    NSound("ns_095", "Pen Tap", "Clicks", R.raw.ns_095),
    NSound("ns_096", "Gong", "Gongs", R.raw.ns_096),
    NSound("ns_097", "Soft Gong", "Gongs", R.raw.ns_097),
    NSound("ns_098", "Singing Bowl", "Gongs", R.raw.ns_098),
    NSound("ns_099", "Tam", "Gongs", R.raw.ns_099),
    NSound("ns_100", "Bowl Strike", "Gongs", R.raw.ns_100),
    NSound("ns_101", "Alert", "Alerts", R.raw.ns_101),
    NSound("ns_102", "Urgent", "Alerts", R.raw.ns_102),
    NSound("ns_103", "Warning", "Alerts", R.raw.ns_103),
    NSound("ns_104", "Caution", "Alerts", R.raw.ns_104),
    NSound("ns_105", "Alarm Short", "Alerts", R.raw.ns_105),
    NSound("ns_106", "Siren Short", "Alerts", R.raw.ns_106),
    NSound("ns_107", "Buzz", "Alerts", R.raw.ns_107),
    NSound("ns_108", "Low Buzz", "Alerts", R.raw.ns_108),
    NSound("ns_109", "Hello", "Melodies", R.raw.ns_109),
    NSound("ns_110", "Good News", "Melodies", R.raw.ns_110),
    NSound("ns_111", "Gentle Wake", "Melodies", R.raw.ns_111),
    NSound("ns_112", "Sunrise", "Melodies", R.raw.ns_112),
    NSound("ns_113", "Cheer", "Melodies", R.raw.ns_113),
    NSound("ns_114", "Step Up", "Melodies", R.raw.ns_114),
    NSound("ns_115", "Step Down", "Melodies", R.raw.ns_115),
    NSound("ns_116", "Sparkle", "Melodies", R.raw.ns_116),
    NSound("ns_117", "Whisper", "Melodies", R.raw.ns_117),
    NSound("ns_118", "Bounce", "Melodies", R.raw.ns_118),
    NSound("ns_119", "Joy", "Melodies", R.raw.ns_119),
    NSound("ns_120", "Calm", "Melodies", R.raw.ns_120),
    NSound("ns_121", "Magic", "Melodies", R.raw.ns_121),
    NSound("ns_122", "Dawn", "Melodies", R.raw.ns_122),
    NSound("ns_123", "Okay", "Melodies", R.raw.ns_123),
    NSound("ns_124", "Done", "Melodies", R.raw.ns_124),
    NSound("ns_125", "Welcome", "Melodies", R.raw.ns_125),
    NSound("ns_126", "Ripple", "Melodies", R.raw.ns_126),
    NSound("ns_127", "Lullaby", "Melodies", R.raw.ns_127),
    NSound("ns_128", "Fanfare", "Melodies", R.raw.ns_128),
    NSound("ns_129", "Message", "Soft", R.raw.ns_129),
    NSound("ns_130", "Pop Message", "Soft", R.raw.ns_130),
    NSound("ns_131", "Soft Note", "Soft", R.raw.ns_131),
    NSound("ns_132", "Gentle Tap", "Soft", R.raw.ns_132),
    NSound("ns_133", "Bright Note", "Soft", R.raw.ns_133),
    NSound("ns_134", "Warm Note", "Soft", R.raw.ns_134),
    NSound("ns_135", "Cloud", "Soft", R.raw.ns_135),
    NSound("ns_136", "Feather", "Soft", R.raw.ns_136),
    NSound("ns_137", "Whoosh Note", "Soft", R.raw.ns_137),
    NSound("ns_138", "Hum", "Soft", R.raw.ns_138),
    NSound("ns_139", "Swoosh", "Soft", R.raw.ns_139),
    NSound("ns_140", "Air", "Soft", R.raw.ns_140),
    NSound("ns_141", "Breeze", "Soft", R.raw.ns_141),
    NSound("ns_142", "Flutter", "Soft", R.raw.ns_142)
    )

    val categories: List<String> = all.map { it.category }.distinct()

    fun byId(id: String?): NSound? = all.firstOrNull { it.id == id }

    fun uri(ctx: Context, s: NSound): Uri = Uri.parse("android.resource://" + ctx.packageName + "/" + s.res)

    // ---- saved choice (plain SharedPreferences, nothing leaves the phone) ----
    private const val K_SOUND = "notif_sound"
    private const val K_ON = "notif_sound_on"
    const val DEFAULT_ID = "ns_001"

    fun selectedId(ctx: Context): String = Prefs.sp(ctx).getString(K_SOUND, DEFAULT_ID) ?: DEFAULT_ID
    fun selected(ctx: Context): NSound = byId(selectedId(ctx)) ?: all.first()
    fun select(ctx: Context, id: String) { Prefs.sp(ctx).edit().putString(K_SOUND, id).apply() }
    fun soundOn(ctx: Context): Boolean = Prefs.sp(ctx).getBoolean(K_ON, true)
    fun setSoundOn(ctx: Context, on: Boolean) { Prefs.sp(ctx).edit().putBoolean(K_ON, on).apply() }

    /**
     * Android fixes a channel's sound when it is created, so each (channel, sound) pair gets its own channel id.
     * Picking another sound in Settings makes a new channel and removes the old one. Returns the id to post to.
     */
    fun alertChannel(ctx: Context, base: String, label: String, importance: Int = android.app.NotificationManager.IMPORTANCE_HIGH, vibrate: Boolean = true): String {
        if (android.os.Build.VERSION.SDK_INT < 26) return base
        val sel = if (soundOn(ctx)) selected(ctx).id else "mute"
        val id = base + "__" + sel
        val nm = ctx.getSystemService(android.app.NotificationManager::class.java)
        if (nm.getNotificationChannel(id) == null) {
            nm.notificationChannels.filter { it.id == base || it.id.startsWith(base + "__") }.forEach { nm.deleteNotificationChannel(it.id) }
            val ch = android.app.NotificationChannel(id, label, importance)
            ch.enableVibration(vibrate)
            if (sel == "mute") ch.setSound(null, null)
            else ch.setSound(uri(ctx, selected(ctx)), AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_NOTIFICATION).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
            nm.createNotificationChannel(ch)
        }
        return id
    }

    // ---- preview ----
    private var player: MediaPlayer? = null

    /** Plays a sound once for the picker. Uses the notification stream so it follows the phone's notification volume. */
    fun preview(ctx: Context, s: NSound) {
        stop()
        try {
            val mp = MediaPlayer.create(
                ctx.applicationContext, s.res, AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build(),
                android.media.AudioManager.AUDIO_SESSION_ID_GENERATE
            ) ?: return
            mp.setOnCompletionListener { it.release(); if (player === it) player = null }
            player = mp
            mp.start()
        } catch (e: Exception) { player = null }
    }

    fun stop() {
        try { player?.release() } catch (e: Exception) { }
        player = null
    }
}
