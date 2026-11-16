package com.example.voiceassistant

import android.Manifest
import android.app.SearchManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.os.BatteryManager
import android.provider.AlarmClock
import android.provider.ContactsContract
import android.provider.Settings
import android.widget.Toast
import java.util.Calendar
import java.util.Locale
import java.util.Random
import kotlin.math.min
import kotlin.math.roundToInt

class Reply(
    val tanglish: String,
    val tamil: String = tanglish,
    val action: ((Context) -> Unit)? = null,
    val stopListening: Boolean = false,
    val understood: Boolean = true
)

object Brain {

    private val rnd = Random()
    private var torchOn = false
    private var lastJoke = -1

    // ---------------- helpers ----------------

    private fun has(t: String, vararg k: String): Boolean {
        for (x in k) if (t.contains(x)) return true
        return false
    }

    private val offRegex = Regex("\\b(off|cancel|delete|remove|dismiss|stop|close)\\b")

    private fun wantsOff(t: String): Boolean =
        offRegex.containsMatchIn(t) ||
            has(t, "நிறுத்து", "நிறுத்த", "ரத்து", "அணை", "வேண்டாம்", "நீக்கு", "அழி", "மூடு",
                "ஆஃப்", "ஆப்", "anai", "nirutthu", "vendam", "neekku", "muudu")

    private val numWords = listOf(
        "நூறு" to 100, "தொண்ணூறு" to 90, "தொன்னூறு" to 90, "எண்பது" to 80, "எழுபது" to 70,
        "அறுபது" to 60, "ஐம்பது" to 50, "நாற்பது" to 40, "முப்பது" to 30, "இருபது" to 20, "பத்து" to 10,
        "thonnooru" to 90, "nooru" to 100, "embathu" to 80, "ezhubathu" to 70, "arubathu" to 60,
        "aimbathu" to 50, "naarpathu" to 40, "muppathu" to 30, "irupathu" to 20, "irubathu" to 20,
        "pathu" to 10
    )

    private fun findNumber(t: String): Int? {
        val m = Regex("\\d+").find(t)
        if (m != null) return m.value.toIntOrNull()
        for ((w, v) in numWords) if (t.contains(w)) return v
        return null
    }

    private fun launchOr(ctx: Context, a: Intent, b: Intent?) {
        try {
            a.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ctx.startActivity(a)
            return
        } catch (e: Exception) {
        }
        if (b != null) {
            try {
                b.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                ctx.startActivity(b)
                return
            } catch (e: Exception) {
            }
        }
        Toast.makeText(ctx, "Idhai open panna mudiyala", Toast.LENGTH_SHORT).show()
    }

    private fun launch(ctx: Context, a: Intent) = launchOr(ctx, a, null)

    private fun cleanQuery(t: String, fillers: List<String>): String {
        val out = ArrayList<String>()
        for (tok in t.split(" ", ",", ".", "?")) {
            if (tok.isBlank()) continue
            var skip = false
            for (f in fillers) {
                if (tok == f || (f.length >= 5 && tok.startsWith(f))) {
                    skip = true
                    break
                }
            }
            if (!skip) out.add(tok)
        }
        return out.joinToString(" ").trim()
    }

    // ---------------- main entry ----------------

    fun reply(ctx: Context, commandRaw: String): Reply {
        val t = commandRaw.lowercase(Locale.ROOT).trim()
        if (t.isEmpty()) return fallback()

        torch(t)?.let { return it }
        alarm(t)?.let { return it }
        timer(t)?.let { return it }
        volume(ctx, t)?.let { return it }
        battery(ctx, t)?.let { return it }
        closeRule(t)?.let { return it }
        calculator(t)?.let { return it }
        call(ctx, t)?.let { return it }
        play(t)?.let { return it }
        wifiBt(t)?.let { return it }
        openApp(ctx, t)?.let { return it }
        timeDate(t)?.let { return it }
        navigate(t)?.let { return it }
        search(t)?.let { return it }
        fun2(t)?.let { return it }
        smallTalk(t)?.let { return it }
        return fallback()
    }

    private fun fallback() = Reply(
        "Puriyala, marupadi sollunga. 'help' nu sonna enna panna mudiyum nu solren.",
        "புரியல, மறுபடி சொல்லுங்க. 'உதவி' ன்னு சொன்னா என்ன பண்ண முடியும்னு சொல்றேன்.",
        null, false, false
    )

    // ---------------- torch ----------------

    private fun torch(t: String): Reply? {
        if (!has(t, "torch", "tourch", "flash", "டார்ச்", "ஃபிளாஷ்", "பிளாஷ்", "ஃப்ளாஷ்", "டாச்", "tochu")) return null
        val off = wantsOff(t)
        return if (off) {
            Reply("Torch-a off pannitten", "டார்ச் அணைச்சிட்டேன்", { c -> setTorch(c, false) })
        } else {
            Reply("Torch-a on pannitten", "டார்ச் போட்டுட்டேன்", { c -> setTorch(c, true) })
        }
    }

    private fun setTorch(ctx: Context, on: Boolean) {
        try {
            val cm = ctx.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            var id: String? = null
            for (cid in cm.cameraIdList) {
                val flash = cm.getCameraCharacteristics(cid).get(CameraCharacteristics.FLASH_INFO_AVAILABLE)
                if (flash == true) {
                    id = cid
                    break
                }
            }
            if (id != null) {
                cm.setTorchMode(id, on)
                torchOn = on
            }
        } catch (e: Exception) {
        }
    }

    // ---------------- alarm ----------------

    private fun parseClock(t: String): Pair<Int, Int>? {
        var h: Int
        var m = 0
        val r1 = Regex("(\\d{1,2})\\s*[:.]\\s*(\\d{2})").find(t)
        if (r1 != null) {
            h = r1.groupValues[1].toInt()
            m = r1.groupValues[2].toInt()
        } else {
            val r2 = Regex("(\\d{1,2})\\s*(மணி|mani|am|pm|a\\.m|p\\.m)").find(t)
            h = if (r2 != null) r2.groupValues[1].toInt()
            else (Regex("\\d{1,2}").find(t)?.value?.toIntOrNull() ?: return null)
            if (r2 != null) {
                val rest = t.substring(r2.range.last + 1)
                val rm = Regex("(\\d{1,2})\\s*(நிமிட|நிமிஷ|nimisham|nimida|min)").find(rest)
                if (rm != null) m = rm.groupValues[1].toInt()
                else if (has(rest, "அரை", "arai", "half")) m = 30
            }
        }
        val pm = Regex("\\bp\\.?m\\b").containsMatchIn(t) ||
            has(t, "மாலை", "இரவு", "மதியம்", "evening", "night", "afternoon", "maalai", "iravu")
        val am = Regex("\\ba\\.?m\\b").containsMatchIn(t) || has(t, "காலை", "morning", "kaalai")
        if (pm && h < 12) h += 12
        if (am && h == 12) h = 0
        if (h > 23 || m > 59) return null
        return Pair(h, m)
    }

    private fun alarm(t: String): Reply? {
        if (!has(t, "alarm", "alaram", "அலாரம்", "அலாரத்", "அலார")) return null
        if (wantsOff(t)) {
            return Reply("Sari, alarm-a nirutthuren", "சரி, அலாரத்தை நிறுத்துறேன்", { c ->
                launchOr(
                    c,
                    Intent(AlarmClock.ACTION_DISMISS_ALARM)
                        .putExtra(AlarmClock.EXTRA_ALARM_SEARCH_MODE, AlarmClock.ALARM_SEARCH_MODE_NEXT),
                    Intent(AlarmClock.ACTION_SHOW_ALARMS)
                )
            })
        }
        val time = parseClock(t)
            ?: return Reply(
                "Enna neram-ku alarm? Example: kaalai 6 manikku alarm",
                "எந்த நேரத்துக்கு அலாரம்? உதாரணம்: காலை 6 மணிக்கு அலாரம்"
            )
        val h = time.first
        val m = time.second
        val label = String.format(Locale.US, "%d:%02d", h, m)
        return Reply("$label-ku alarm vachuten", "$label க்கு அலாரம் வெச்சுட்டேன்", { c ->
            launch(
                c,
                Intent(AlarmClock.ACTION_SET_ALARM)
                    .putExtra(AlarmClock.EXTRA_HOUR, h)
                    .putExtra(AlarmClock.EXTRA_MINUTES, m)
                    .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            )
        })
    }

    // ---------------- timer ----------------

    private fun timer(t: String): Reply? {
        if (!has(t, "timer", "டைமர்", "countdown")) return null
        if (wantsOff(t)) {
            return Reply("Sari, timer-a nirutthuren", "சரி, டைமரை நிறுத்துறேன்", { c ->
                launchOr(c, Intent(AlarmClock.ACTION_DISMISS_TIMER), Intent(AlarmClock.ACTION_SHOW_TIMERS))
            })
        }
        val n = findNumber(t)
            ?: return Reply(
                "Evlo neram-ku timer? Example: 5 nimisham timer",
                "எவ்வளவு நேரத்துக்கு டைமர்? உதாரணம்: 5 நிமிடம் டைமர்"
            )
        val secs: Int = when {
            has(t, "hour", "hr", "மணி நேரம்", "மணிநேரம்") -> n * 3600
            has(t, "sec", "second", "விநாடி", "வினாடி", "vinadi") -> n
            else -> n * 60
        }
        val shown = if (secs % 3600 == 0 && secs >= 3600) "${secs / 3600} mani neram"
        else if (secs % 60 == 0) "${secs / 60} nimisham" else "$secs vinadi"
        val shownTa = if (secs % 3600 == 0 && secs >= 3600) "${secs / 3600} மணி நேரம்"
        else if (secs % 60 == 0) "${secs / 60} நிமிடம்" else "$secs விநாடி"
        return Reply("$shown timer vachuten", "$shownTa டைமர் வெச்சுட்டேன்", { c ->
            launch(
                c,
                Intent(AlarmClock.ACTION_SET_TIMER)
                    .putExtra(AlarmClock.EXTRA_LENGTH, secs)
                    .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            )
        })
    }

    // ---------------- volume ----------------

    private fun volume(ctx: Context, t: String): Reply? {
        if (!has(t, "volume", "வால்யூம்", "வால்யும்", "சத்தம்", "sound", "சவுண்ட்", "ஒலி", "mute", "மியூட்", "sattham")) return null
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val maxV = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val cur = am.getStreamVolume(AudioManager.STREAM_MUSIC)
        val curPct = (cur * 100.0 / maxV).roundToInt()
        val n = findNumber(t)
        val up = has(t, "up", "increase", "louder", "kootu", "கூட்டு", "அதிகம்", "அதிகப்படுத்து", "ஏத்து", "ஏற்று", "உயர்த்து", "jaasthi", "ஜாஸ்தி")
        val down = has(t, "down", "decrease", "lower", "kuraikka", "kurai", "குறை", "கம்மி", "இறக்கு", "குறைத்து")
        val full = has(t, "full", "max", "முழு", "மேக்ஸ்")
        val mute = has(t, "mute", "மியூட்", "silent", "சைலன்ட்")
        val raw: Int = when {
            mute -> 0
            full -> 100
            n != null && up -> curPct + n
            n != null && down -> curPct - n
            n != null -> n
            up -> curPct + 20
            down -> curPct - 20
            else -> return Reply(
                "Volume evlo percent? Example: volume 50 percent",
                "வால்யூம் எவ்வளவு சதவீதம்? உதாரணம்: வால்யூம் 50 சதவீதம்"
            )
        }
        val target = raw.coerceIn(0, 100)
        return Reply("Volume $target% aakkiten", "வால்யூம் $target சதவீதம் ஆக்கிட்டேன்", { c ->
            try {
                val a = c.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                val mx = a.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                a.setStreamVolume(AudioManager.STREAM_MUSIC, (target * mx / 100.0).roundToInt(), AudioManager.FLAG_SHOW_UI)
            } catch (e: Exception) {
            }
        })
    }

    // ---------------- battery ----------------

    private fun battery(ctx: Context, t: String): Reply? {
        if (!has(t, "battery", "பேட்டரி", "சார்ஜ்", "charge", "bettery")) return null
        val i = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = i?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = i?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        val status = i?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        if (level < 0) return Reply("Battery nilaimai theriyala", "பேட்டரி நிலைமை தெரியல")
        val pct = (level * 100.0 / scale).roundToInt()
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
        return Reply(
            "Battery $pct% irukku" + if (charging) ", charge aagudhu" else "",
            "பேட்டரி $pct சதவீதம் இருக்கு" + if (charging) ", சார்ஜ் ஆகுது" else ""
        )
    }

    // ---------------- close / home ----------------

    private fun closeRule(t: String): Reply? {
        if (!has(t, "close", "மூடு", "மூடி", "exit", "வெளியேறு", "home", "முகப்பு", "go back")) return null
        return Reply("Sari, home screen-ku poren", "சரி, ஹோம் ஸ்கிரீனுக்கு போறேன்", { c ->
            launch(c, Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME))
        })
    }

    // ---------------- calculator ----------------

    private fun calculator(t: String): Reply? {
        val m = Regex(
            "(-?\\d+(?:\\.\\d+)?)\\s*(\\+|-|x|\\*|×|/|÷|plus|minus|times|into|multiplied by|divided by|by|கூட்டல்|கழித்தல்|பெருக்கல்|வகுத்தல்)\\s*(-?\\d+(?:\\.\\d+)?)"
        ).find(t)
        if (m != null) {
            val a = m.groupValues[1].toDouble()
            val b = m.groupValues[3].toDouble()
            val op = m.groupValues[2]
            val res: Double? = when (op) {
                "+", "plus", "கூட்டல்" -> a + b
                "-", "minus", "கழித்தல்" -> a - b
                "x", "*", "×", "times", "into", "multiplied by", "பெருக்கல்" -> a * b
                else -> if (b == 0.0) null else a / b
            }
            if (res == null) return Reply("Zero-la vaguka mudiyaadhu", "பூஜ்யத்தால வகுக்க முடியாது")
            val s = if (res == Math.floor(res) && Math.abs(res) < 1e12) res.toLong().toString()
            else String.format(Locale.US, "%.2f", res)
            return Reply("Vidai $s", "விடை $s")
        }
        if (has(t, "calculator", "கால்குலேட்டர்", "calculater")) {
            return Reply("Calculator open pannuren", "கால்குலேட்டர் திறக்கிறேன்", { c ->
                launch(c, Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_CALCULATOR))
            })
        }
        return null
    }

    // ---------------- call (contacts) ----------------

    private val callFillers = listOf(
        "call", "கால்", "அழை", "அழைங்க", "கூப்பிடு", "கூப்பிடுங்க", "dial", "டயல்", "phone", "போன்",
        "pannu", "பண்ணு", "பண்ணுங்க", "பண்ணி", "பேசு", "பேசணும்", "pesanum", "to", "ku", "க்கு",
        "enakku", "எனக்கு", "please", "பிளீஸ்", "ஒரு", "oru", "செய்", "பண்ணிடு", "பன்னு", "பன்னுங்க"
    )

    private fun call(ctx: Context, t: String): Reply? {
        if (t.contains("calculator") || t.contains("கால்குலேட்டர்")) return null
        if (!has(t, "call", "கால் ", "கால்", "அழை", "கூப்பிடு", "dial", "டயல்", "போன் பண்ணு", "போன் பன்னு", "phone pannu", "பேசு", "pesu")) return null

        val digits = t.filter { it.isDigit() }
        if (digits.length >= 4) {
            return Reply("$digits-ku call panren", "$digits க்கு கால் பண்றேன்", { c -> placeCall(c, digits) })
        }

        val name = cleanQuery(t, callFillers)
        if (name.isEmpty()) {
            return Reply("Dialer open pannuren", "டயலர் திறக்கிறேன்", { c ->
                launch(c, Intent(Intent.ACTION_DIAL))
            })
        }
        if (ctx.checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            return Reply(
                "Contacts permission thevai. MS app-ai open panni Allow pannunga.",
                "காண்டாக்ட்ஸ் பர்மிஷன் வேணும். MS ஆப்பை திறந்து Allow பண்ணுங்க."
            )
        }
        val found = findContact(ctx, name)
            ?: return Reply(
                "'$name' contact kidaikkala. Marupadi sollunga.",
                "'$name' காண்டாக்ட் கிடைக்கல. மறுபடி சொல்லுங்க."
            )
        val cname = found.first
        val num = found.second
        return Reply("$cname-ku call panren", "$cname க்கு கால் பண்றேன்", { c -> placeCall(c, num) })
    }

    private fun placeCall(ctx: Context, number: String) {
        val uri = Uri.parse("tel:" + Uri.encode(number))
        val granted = ctx.checkSelfPermission(Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED
        if (granted) launchOr(ctx, Intent(Intent.ACTION_CALL, uri), Intent(Intent.ACTION_DIAL, uri))
        else launch(ctx, Intent(Intent.ACTION_DIAL, uri))
    }

    private fun letterKey(c: Char): Char? {
        return when (c) {
            'b', 'p', 'f', 'ப' -> 'p'
            'd', 't', 'ட', 'த' -> 't'
            'g', 'k', 'q', 'க' -> 'k'
            'c', 's', 'j', 'z', 'ச', 'ஜ', 'ஷ', 'ஸ' -> 's'
            'm', 'ம' -> 'm'
            'n', 'ன', 'ந', 'ண', 'ங', 'ஞ' -> 'n'
            'l', 'ல', 'ள', 'ழ' -> 'l'
            'r', 'ர', 'ற' -> 'r'
            'v', 'வ' -> 'v'
            'x' -> 'k'
            else -> null
        }
    }

    private fun phoneticKey(s: String): String {
        val sb = StringBuilder()
        for (ch in s.lowercase(Locale.ROOT)) {
            val k = letterKey(ch) ?: continue
            if (sb.isEmpty() || sb[sb.length - 1] != k) sb.append(k)
        }
        return sb.toString()
    }

    private fun findContact(ctx: Context, query: String): Pair<String, String>? {
        val qKey = phoneticKey(query)
        if (qKey.length < 2) return null
        var best: Pair<String, String>? = null
        var bestScore = 0
        val cur = ctx.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Phone.NUMBER
            ),
            null, null, null
        ) ?: return null
        cur.use {
            while (it.moveToNext()) {
                val name = it.getString(0) ?: continue
                val num = it.getString(1) ?: continue
                val nk = phoneticKey(name)
                if (nk.length < 2) continue
                val score = when {
                    nk == qKey -> 100
                    nk.contains(qKey) || qKey.contains(nk) -> 50 + min(nk.length, qKey.length)
                    else -> 0
                }
                if (score > bestScore) {
                    bestScore = score
                    best = Pair(name, num)
                }
            }
        }
        return best
    }

    // ---------------- play / search / navigate ----------------

    private val playFillers = listOf(
        "youtube", "யூடியூப்", "யூடூப்", "play", "ப்ளே", "பிளே", "பாட்டு", "பாடல்", "பாடலை", "song", "songs",
        "music", "மியூசிக்", "போடு", "போட்டு", "podu", "paatu", "patu", "pannu", "பண்ணு", "பண்ணுங்க", "வை",
        "வைக்க", "la", "ல", "video", "வீடியோ", "கேட்க", "kekka", "kelu", "கேளு", "oru", "ஒரு", "enakku",
        "எனக்கு", "please", "plz", "பிளீஸ்", "open", "ஓபன்", "paaru", "பாரு", "காட்டு"
    )

    private fun play(t: String): Reply? {
        if (has(t, "store", "ஸ்டோர்")) return null
        if (!has(t, "play", "ப்ளே", "பிளே", "பாட்டு", "பாடல்", "song", "music", "மியூசிக்", "paatu", "வீடியோ", "video")) return null
        val q = cleanQuery(t, playFillers)
        if (q.isEmpty()) {
            return Reply("YouTube open pannuren", "யூடியூப் திறக்கிறேன்", { c ->
                launchOr(c, Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com")), null)
            })
        }
        return Reply("$q YouTube-la podren", "$q யூடியூப்ல போடுறேன்", { c ->
            val yt = Intent(Intent.ACTION_SEARCH).setPackage("com.google.android.youtube").putExtra("query", q)
            val web = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/results?search_query=" + Uri.encode(q)))
            launchOr(c, yt, web)
        })
    }

    private val navFillers = listOf(
        "navigate", "navigation", "நேவிகேட்", "நேவிகேஷன்", "வழி", "route", "directions", "direction", "map", "maps",
        "மேப்", "மேப்ஸ்", "to", "க்கு", "kku", "போ", "po", "காட்டு", "kaattu", "pannu", "பண்ணு", "enakku",
        "எனக்கு", "எப்படி", "please", "google", "கூகுள்", "open", "ஓபன்", "வேணும்", "venum", "போகணும்", "poganum"
    )

    private fun navigate(t: String): Reply? {
        if (!has(t, "navigate", "navigation", "நேவிகேட்", "நேவிகேஷன்", "வழி", "route", "directions", "map", "மேப்", "போகணும்", "poganum")) return null
        val q = cleanQuery(t, navFillers)
        if (q.isEmpty()) {
            return Reply("Maps open pannuren", "மேப்ஸ் திறக்கிறேன்", { c ->
                launchOr(c, Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0")), null)
            })
        }
        return Reply("$q-ku vazhi kaatturen", "$q க்கு வழி காட்டுறேன்", { c ->
            val nav = Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=" + Uri.encode(q)))
                .setPackage("com.google.android.apps.maps")
            val geo = Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=" + Uri.encode(q)))
            launchOr(c, nav, geo)
        })
    }

    private val searchFillers = listOf(
        "search", "தேடு", "தேடி", "தேடுங்க", "google", "கூகுள்", "pannu", "பண்ணு", "la", "ல", "for", "about",
        "பத்தி", "pathi", "enakku", "எனக்கு", "please", "ஒரு", "oru", "பாரு", "paaru"
    )

    private fun search(t: String): Reply? {
        if (!has(t, "search", "தேடு", "தேடி", "தேடுங்க", "google", "கூகுள்", "who is", "what is", "யார்", "என்றால் என்ன", "paaru", "பாரு")) return null
        val q = cleanQuery(t, searchFillers)
        if (q.isEmpty()) return null
        return Reply("$q-a Google-la thedren", "$q ஐ கூகுள்ல தேடுறேன்", { c ->
            launchOr(
                c,
                Intent(Intent.ACTION_WEB_SEARCH).putExtra(SearchManager.QUERY, q),
                Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/search?q=" + Uri.encode(q)))
            )
        })
    }

    // ---------------- settings and apps ----------------

    private fun wifiBt(t: String): Reply? {
        if (has(t, "wifi", "wi-fi", "வைஃபை", "வைபை", "வை-பை")) {
            return Reply("WiFi settings open pannuren", "வைஃபை செட்டிங்ஸ் திறக்கிறேன்", { c ->
                launch(c, Intent(Settings.ACTION_WIFI_SETTINGS))
            })
        }
        if (has(t, "bluetooth", "ப்ளூடூத்", "புளூடூத்", "பிளூடூத்")) {
            return Reply("Bluetooth settings open pannuren", "ப்ளூடூத் செட்டிங்ஸ் திறக்கிறேன்", { c ->
                launch(c, Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
            })
        }
        return null
    }

    private val aliases = listOf(
        "youtube" to listOf("youtube", "யூடியூப்", "யூடூப்"),
        "whatsapp" to listOf("whatsapp", "வாட்ஸ்அப்", "வாட்சப்", "வாட்ஸ் அப்", "வாட்ஸப்"),
        "camera" to listOf("camera", "கேமரா", "selfie", "செல்ஃபி", "photo", "போட்டோ"),
        "maps" to listOf("maps", "மேப்ஸ்", "மேப்"),
        "chrome" to listOf("chrome", "குரோம்", "browser", "பிரவுசர்"),
        "settings" to listOf("settings", "செட்டிங்ஸ்", "செட்டிங்"),
        "phone" to listOf("dialer", "டயலர்"),
        "gallery" to listOf("gallery", "கேலரி", "photos", "போட்டோஸ்"),
        "calculator" to listOf("calculator", "கால்குலேட்டர்"),
        "gmail" to listOf("gmail", "ஜிமெயில்", "mail", "மெயில்"),
        "instagram" to listOf("instagram", "இன்ஸ்டாகிராம்", "இன்ஸ்டா", "insta"),
        "facebook" to listOf("facebook", "பேஸ்புக்", "ஃபேஸ்புக்"),
        "telegram" to listOf("telegram", "டெலிகிராம்"),
        "spotify" to listOf("spotify", "ஸ்பாட்டிஃபை"),
        "play store" to listOf("play store", "ப்ளே ஸ்டோர்", "பிளே ஸ்டோர்", "playstore"),
        "clock" to listOf("clock", "கடிகாரம்", "கிளாக்"),
        "contacts" to listOf("contacts", "காண்டாக்ட்ஸ்", "கான்டாக்ட்"),
        "messages" to listOf("messages", "மெசேஜஸ்", "sms", "மெசேஜ்"),
        "files" to listOf("files", "ஃபைல்ஸ்", "file manager"),
        "phonepe" to listOf("phonepe", "போன்பே", "phone pe"),
        "paytm" to listOf("paytm", "பேடிஎம்"),
        "gpay" to listOf("gpay", "google pay", "ஜிபே", "கூகுள் பே")
    )

    private val openWords = listOf(
        "open", "ஓபன்", "ஓப்பன்", "திற", "திறந்து", "தொற", "தொறந்து", "launch", "start", "காட்டு", "kaattu", "thira"
    )

    private fun openApp(ctx: Context, t: String): Reply? {
        var key: String? = null
        for ((eng, words) in aliases) {
            for (w in words) if (t.contains(w)) { key = eng; break }
            if (key != null) break
        }
        val openWord = has(t, *openWords.toTypedArray())
        if (key == null && !openWord) return null

        val pm = ctx.packageManager
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = pm.queryIntentActivities(launcher, 0)

        var pkg: String? = null
        var shownName = key ?: ""
        if (key != null) {
            for (a in apps) {
                val label = a.loadLabel(pm).toString().lowercase(Locale.ROOT)
                if (label.contains(key)) { pkg = a.activityInfo.packageName; shownName = a.loadLabel(pm).toString(); break }
            }
        }
        if (pkg == null && openWord) {
            for (a in apps) {
                val label = a.loadLabel(pm).toString().lowercase(Locale.ROOT)
                if (label.length >= 3 && t.contains(label)) { pkg = a.activityInfo.packageName; shownName = a.loadLabel(pm).toString(); break }
            }
        }
        if (pkg == null) {
            if (!openWord) return null
            return Reply("Andha app kidaikkala", "அந்த ஆப் கிடைக்கல")
        }
        val p = pkg
        return Reply("$shownName open pannuren", "$shownName திறக்கிறேன்", { c ->
            val i = c.packageManager.getLaunchIntentForPackage(p)
            if (i != null) launch(c, i)
        })
    }

    // ---------------- time and date ----------------

    private val daysTa = arrayOf("ஞாயிற்றுக்கிழமை", "திங்கட்கிழமை", "செவ்வாய்க்கிழமை", "புதன்கிழமை", "வியாழக்கிழமை", "வெள்ளிக்கிழமை", "சனிக்கிழமை")
    private val daysTang = arrayOf("Gnaayitru kizhamai", "Thingal kizhamai", "Sevvaai kizhamai", "Budhan kizhamai", "Viyaazhan kizhamai", "Velli kizhamai", "Sani kizhamai")
    private val monthsTa = arrayOf("ஜனவரி", "பிப்ரவரி", "மார்ச்", "ஏப்ரல்", "மே", "ஜூன்", "ஜூலை", "ஆகஸ்ட்", "செப்டம்பர்", "அக்டோபர்", "நவம்பர்", "டிசம்பர்")
    private val monthsEn = arrayOf("January", "February", "March", "April", "May", "June", "July", "August", "September", "October", "November", "December")

    private fun partTa(h: Int) = when (h) {
        in 5..11 -> "காலை"
        in 12..15 -> "மதியம்"
        in 16..18 -> "மாலை"
        else -> "இரவு"
    }

    private fun partTang(h: Int) = when (h) {
        in 5..11 -> "kaalai"
        in 12..15 -> "mathiyam"
        in 16..18 -> "maalai"
        else -> "iravu"
    }

    private fun timeDate(t: String): Reply? {
        val wantTime = has(t, "time", "neram", "நேரம்", "மணி", "clock", "what's the time")
        val wantDate = has(t, "date", "தேதி", "today", "இன்று", "இன்னைக்கு", "இன்றைக்கு", "day", "கிழமை", "நாள்", "month", "மாதம்", "year", "வருடம்", "thethi", "naal")
        if (!wantTime && !wantDate) return null

        val cal = Calendar.getInstance()
        val h = cal.get(Calendar.HOUR_OF_DAY)
        val m = cal.get(Calendar.MINUTE)
        val h12 = if (h % 12 == 0) 12 else h % 12
        val tang = StringBuilder()
        val ta = StringBuilder()
        if (wantTime) {
            tang.append("Ippo ${partTang(h)} $h12 mani" + (if (m > 0) " $m nimisham" else "") + " aagudhu. ")
            ta.append("இப்போது ${partTa(h)} $h12 மணி" + (if (m > 0) " $m நிமிடம்" else "") + ". ")
        }
        if (wantDate) {
            val d = cal.get(Calendar.DAY_OF_MONTH)
            val mo = cal.get(Calendar.MONTH)
            val y = cal.get(Calendar.YEAR)
            val wd = cal.get(Calendar.DAY_OF_WEEK) - 1
            tang.append("Innaiku ${daysTang[wd]}, $d ${monthsEn[mo]} $y.")
            ta.append("இன்று ${daysTa[wd]}, $d ${monthsTa[mo]} $y.")
        }
        return Reply(tang.toString().trim(), ta.toString().trim())
    }

    // ---------------- fun: jokes, dice, coin ----------------

    private val jokes = listOf(
        Pair(
            "Teacher: Yen late-a vandha? Student: Illa sir, bell thaan seekiram adichuduchu.",
            "ஆசிரியர்: ஏன் லேட்டா வந்த? மாணவன்: இல்ல சார், பெல் தான் சீக்கிரம் அடிச்சிடுச்சு."
        ),
        Pair(
            "Phone-ku fever vandha enna aagum? Hang aagum.",
            "போனுக்கு காய்ச்சல் வந்தா என்ன ஆகும்? ஹேங் ஆகும்."
        ),
        Pair(
            "Computer-ku pasi vandha enna saapidum? Byte saapidum.",
            "கம்ப்யூட்டருக்கு பசி வந்தா என்ன சாப்பிடும்? பைட் சாப்பிடும்."
        ),
        Pair(
            "Ellarukum free-a kedaikum, aana yaarum edukka maatanga. Adhu enna? Advice.",
            "எல்லாருக்கும் இலவசமா கிடைக்கும், ஆனா யாரும் எடுத்துக்க மாட்டாங்க. அது என்ன? அட்வைஸ்."
        ),
        Pair(
            "Maths teacher: Anju mangaai la rendu eduthaa ethanai irukkum? Student: Rendu sir, neenga eduthadhu thaan en kitta irukku.",
            "கணக்கு வாத்தியார்: ஐந்து மாங்காயில் இரண்டு எடுத்தா எத்தனை இருக்கும்? மாணவன்: இரண்டு சார், நீங்க எடுத்தது தான் என்கிட்ட இருக்கு."
        ),
        Pair(
            "Alarm vachu ezhundhu, innaiku Sunday nu theriyum bodhu varra sandhosham irukke... adhu thani.",
            "அலாரம் வெச்சு எழுந்து, இன்னைக்கு ஞாயிற்றுக்கிழமைன்னு தெரியும்போது வர்ற சந்தோஷம் இருக்கே... அது தனி."
        ),
        Pair(
            "Doctor: Ungalukku oru nalla seithi, oru ketta seithi. Patient: Nalla seithi sollunga. Doctor: Neenga 100 vayasu vara vaazhveenga. Patient: Ketta seithi? Doctor: Ippo ungalukku 99.",
            "டாக்டர்: ஒரு நல்ல செய்தி, ஒரு கெட்ட செய்தி. நோயாளி: நல்ல செய்தி சொல்லுங்க. டாக்டர்: நீங்க 100 வயசு வரை வாழ்வீங்க. நோயாளி: கெட்ட செய்தி? டாக்டர்: இப்ப உங்களுக்கு 99."
        ),
        Pair(
            "Wifi password kettaa mudhal question: Ungalukku evlo battery irukku?",
            "வைஃபை பாஸ்வேர்டு கேட்டா முதல் கேள்வி: உங்களுக்கு எவ்வளவு பேட்டரி இருக்கு?"
        ),
        Pair(
            "Nee enakku oru kodi kudutha naan unakku 90 laksham tharen. Appuram? 10 laksham commission.",
            "நீ எனக்கு ஒரு கோடி கொடுத்தா நான் உனக்கு 90 லட்சம் தரேன். அப்புறம்? 10 லட்சம் கமிஷன்."
        )
    )

    private fun fun2(t: String): Reply? {
        if (has(t, "joke", "ஜோக்", "சிரிப்பு", "காமெடி", "comedy", "funny", "கடி", "சிரிக்க")) {
            var i = rnd.nextInt(jokes.size)
            if (i == lastJoke) i = (i + 1) % jokes.size
            lastJoke = i
            return Reply(jokes[i].first, jokes[i].second)
        }
        if (Regex("\\b(dice|dies|dyce|die|nice|roll)\\b").containsMatchIn(t) || has(t, "டைஸ்", "டைசு", "தாயம்", "டைஸ")) {
            val n = rnd.nextInt(6) + 1
            return Reply("Dice-la $n vandhuchu", "டைஸ்ல $n வந்துச்சு")
        }
        if (has(t, "coin", "காயின்", "காய்ன்", "toss", "டாஸ்", "நாணயம்", "சுண்டு", "heads", "tails")) {
            val head = rnd.nextBoolean()
            return if (head) Reply("Head vandhuchu", "தலை விழுந்தது") else Reply("Tail vandhuchu", "பூ விழுந்தது")
        }
        return null
    }

    // ---------------- small talk ----------------

    private fun smallTalk(t: String): Reply? {
        if (has(t, "help", "உதவி", "what can you do", "என்ன செய்வ", "features", "என்ன பண்ணுவ")) {
            return Reply(
                "Naan alarm, timer, call, torch, volume, battery, neram, thethi, paatu, app open, search, navigate, joke, dice, coin ellam seiven.",
                "நான் அலாரம், டைமர், கால், டார்ச், வால்யூம், பேட்டரி, நேரம், தேதி, பாட்டு, ஆப் திறக்க, தேடல், வழி, ஜோக், டைஸ், காயின் எல்லாம் செய்வேன்."
            )
        }
        if (has(t, "how are you", "எப்படி இருக்க", "எப்படி இருக்கீங்க", "நலமா", "eppadi irukka", "சௌக்கியமா")) {
            return Reply("Naan nalla irukken, neenga eppadi irukeenga?", "நான் நல்லா இருக்கேன், நீங்க எப்படி இருக்கீங்க?")
        }
        if (has(t, "your name", "பேர் என்ன", "உன் பெயர்", "யார் நீ", "who are you", "peyar enna", "பெயர் என்ன")) {
            return Reply("En peyar MS. Ungal voice assistant.", "என் பெயர் MS. உங்கள் குரல் உதவியாளர்.")
        }
        if (has(t, "thanks", "thank", "நன்றி", "நன்னி", "thx")) {
            return Reply("Paravaillai, eppo venumnaalum kelunga", "பரவாயில்லை, எப்போ வேணும்னாலும் கேளுங்க")
        }
        if (Regex("\\b(hi|hello|hey)\\b").containsMatchIn(t) || has(t, "வணக்கம்", "ஹலோ", "ஹாய்", "vanakkam")) {
            return Reply("Vanakkam! Enna venum?", "வணக்கம்! என்ன வேணும்?")
        }
        if (has(t, "bye", "போதும்", "நிறுத்து", "stop", "sleep", "தூங்கு", "வரேன்", "போய்ட்டு வரேன்", "pothum")) {
            return Reply("Sari, naan amaidhiya irukken. Bubble-a long-press panni thirumba ezhuppunga.", "சரி, நான் அமைதியா இருக்கேன். பப்பிளை லாங்-பிரஸ் பண்ணி திரும்ப எழுப்புங்க.", null, true)
        }
        return null
    }
}
