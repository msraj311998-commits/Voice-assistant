package com.example.voiceassistant

import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.os.BatteryManager
import android.provider.AlarmClock
import android.provider.Settings
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import kotlin.math.floor
import kotlin.random.Random

/** tanglish = for English voice, tamil = for Tamil voice, action = phone action to run. */
class Reply(
    val tanglish: String,
    val tamil: String = tanglish,
    val action: ((Context) -> Unit)? = null,
    val stopListening: Boolean = false
)

object Brain {

    private val jokes = listOf(
        Pair(
            "Computer-ku enna pidikkum? Cookies! Aana adhu browser-la thaan irukkum.",
            "கம்ப்யூட்டருக்கு என்ன புடிக்கும்? குக்கீஸ்! ஆனா அது பிரவுசர்ல தான் இருக்கும்."
        ),
        Pair(
            "Phone-ku fever vandhuchu. Doctor sonnaar: Charge pannunga, appuram restart pannunga.",
            "போனுக்கு காய்ச்சல் வந்துச்சு. டாக்டர் சொன்னார், சார்ஜ் பண்ணுங்க, அப்புறம் ரீஸ்டார்ட் பண்ணுங்க."
        ),
        Pair(
            "Oru paamba kitta ketaanga, nee en pesaama irukka? Adhu sonnadhu, naan silent mode-la irukken.",
            "ஒரு பாம்பு கிட்ட கேட்டாங்க, நீ ஏன் பேசாம இருக்க? அது சொன்னுச்சு, நான் சைலன்ட் மோடுல இருக்கேன்."
        )
    )

    private val tamilDays = arrayOf(
        "ஞாயிற்றுக்கிழமை", "திங்கட்கிழமை", "செவ்வாய்க்கிழமை", "புதன்கிழமை",
        "வியாழக்கிழமை", "வெள்ளிக்கிழமை", "சனிக்கிழமை"
    )
    private val tamilMonths = arrayOf(
        "ஜனவரி", "பிப்ரவரி", "மார்ச்", "ஏப்ரல்", "மே", "ஜூன்",
        "ஜூலை", "ஆகஸ்ட்", "செப்டம்பர்", "அக்டோபர்", "நவம்பர்", "டிசம்பர்"
    )

    private val appAliases = listOf(
        Pair("போன்பே", "phonepe"), Pair("phone pe", "phonepe"),
        Pair("வாட்ஸ்அப்", "whatsapp"), Pair("வாட்சப்", "whatsapp"), Pair("வாட்ஸப்", "whatsapp"),
        Pair("watsapp", "whatsapp"), Pair("whats app", "whatsapp"),
        Pair("யூடியூப்", "youtube"), Pair("யூ டியூப்", "youtube"), Pair("you tube", "youtube"),
        Pair("கேமரா", "camera"),
        Pair("கூகுள் மேப்ஸ்", "maps"), Pair("மேப்ஸ்", "maps"), Pair("மேப்", "maps"),
        Pair("குரோம்", "chrome"),
        Pair("செட்டிங்ஸ்", "settings"), Pair("செட்டிங்", "settings"),
        Pair("கேலரி", "gallery"), Pair("கால்குலேட்டர்", "calculator"),
        Pair("க்ளாக்", "clock"), Pair("கடிகாரம்", "clock"),
        Pair("மெசேஜ்", "messages"),
        Pair("இன்ஸ்டாகிராம்", "instagram"), Pair("இன்ஸ்டா", "instagram"),
        Pair("பேஸ்புக்", "facebook"), Pair("டெலிகிராம்", "telegram"), Pair("ஜிமெயில்", "gmail"),
        Pair("ஜிபே", "pay"), Pair("கூகுள் பே", "pay"),
        Pair("போன்", "phone")
    )

    private val openFillers = setOf(
        "open", "launch", "pannu", "pannunga", "panni", "app", "apps", "please",
        "thira", "thirandhu", "thirakka", "ஓபன்", "திற", "திறந்து", "பண்ணு", "பண்ணுங்க",
        "பண்ணி", "ஆப்", "ஆப்ஸ்", "enakku", "எனக்கு", "the", "my", "a"
    )
    private val playFillers = setOf(
        "play", "paattu", "paadal", "song", "songs", "music", "podu", "pottu", "poodu",
        "pannu", "pannunga", "youtube", "on", "la", "please", "enakku",
        "பாட்டு", "பாடல்", "போடு", "ப்ளே", "யூடியூப்", "பண்ணு", "எனக்கு", "ஒரு"
    )
    private val searchFillers = setOf(
        "search", "google", "thedu", "thedunga", "pannu", "pannunga", "sollu", "for", "about",
        "on", "please", "தேடு", "தேடுங்க", "கூகுள்", "பண்ணு", "சொல்லு"
    )
    private val navFillers = setOf(
        "navigate", "navigation", "route", "direction", "directions", "to", "vazhi", "vali",
        "kaattu", "kaatu", "podu", "pannu", "sollu", "please",
        "நேவிகேட்", "வழி", "காட்டு", "போடு", "பண்ணு", "சொல்லு"
    )

    private fun fmt(d: Double): String =
        if (d == floor(d) && d > -1e15 && d < 1e15) d.toLong().toString() else "%.2f".format(d)

    private fun cleanQuery(c: String, fillers: Set<String>): String =
        c.split(Regex("[^\\p{L}\\p{M}\\p{N}]+"))
            .filter { it.isNotEmpty() && it !in fillers }
            .joinToString(" ")

    private fun launch(ctx: Context, intent: Intent): Boolean {
        return try {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ctx.startActivity(intent)
            true
        } catch (e: Exception) {
            false
        }
    }

    private fun setTorch(ctx: Context, on: Boolean) {
        try {
            val cm = ctx.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            for (id in cm.cameraIdList) {
                val chars = cm.getCameraCharacteristics(id)
                if (chars.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true) {
                    cm.setTorchMode(id, on)
                    return
                }
            }
        } catch (e: Exception) {
        }
    }

    /** mode: 100 = max, 0 = mute, 1 = a bit louder, -1 = a bit quieter */
    private fun adjustVolume(ctx: Context, mode: Int) {
        try {
            val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            when {
                mode == 100 -> am.setStreamVolume(AudioManager.STREAM_MUSIC, max, 0)
                mode == 0 -> am.setStreamVolume(AudioManager.STREAM_MUSIC, 0, 0)
                else -> {
                    val dir = if (mode > 0) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER
                    for (i in 1..3) am.adjustStreamVolume(AudioManager.STREAM_MUSIC, dir, 0)
                }
            }
        } catch (e: Exception) {
        }
    }

    /** Finds an installed app by name. Returns label and package name. */
    private fun findApp(ctx: Context, name: String): Pair<String, String>? {
        val pm = ctx.packageManager
        val main = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = pm.queryIntentActivities(main, 0)
        for (a in apps) {
            val label = a.loadLabel(pm).toString()
            if (label.lowercase(Locale.ROOT) == name) return Pair(label, a.activityInfo.packageName)
        }
        for (a in apps) {
            val label = a.loadLabel(pm).toString()
            if (label.lowercase(Locale.ROOT).contains(name)) return Pair(label, a.activityInfo.packageName)
        }
        for (a in apps) {
            val label = a.loadLabel(pm).toString()
            if (label.length >= 4 && name.contains(label.lowercase(Locale.ROOT))) {
                return Pair(label, a.activityInfo.packageName)
            }
        }
        return null
    }

    private fun tamilTime(cal: Calendar): String {
        val h24 = cal.get(Calendar.HOUR_OF_DAY)
        val m = cal.get(Calendar.MINUTE)
        val h12 = if (h24 % 12 == 0) 12 else h24 % 12
        val period = when (h24) {
            in 5..11 -> "காலை"
            in 12..15 -> "மதியம்"
            in 16..18 -> "மாலை"
            else -> "இரவு"
        }
        return if (m == 0) "$period $h12 மணி" else "$period $h12 மணி $m நிமிடம்"
    }

    fun reply(ctx: Context, commandRaw: String): Reply {
        val c = commandRaw.lowercase(Locale.ROOT).trim()
        val tokens = c.split(Regex("[^\\p{L}\\p{M}\\p{N}]+")).filter { it.isNotEmpty() }.toSet()

        // short words must match a whole word, longer words can match anywhere
        fun has(vararg words: String): Boolean =
            words.any { w -> if (w.length <= 3) tokens.contains(w) else c.contains(w) }

        val ints = Regex("\\d+").findAll(c).map { it.value.toIntOrNull() ?: 0 }.toList()
        val nums = Regex("-?\\d+(?:\\.\\d+)?").findAll(c).map { it.value.toDouble() }.toList()

        // ---- alarm ----
        if (has("alarm", "அலாரம்")) {
            if (ints.isEmpty()) {
                return Reply(
                    "Entha neram alarm? 'alarm 6 30' nu sollunga.",
                    "எந்த நேரத்துக்கு அலாரம்? அலாரம் 6 30 ன்னு சொல்லுங்க."
                )
            }
            var hour = ints[0]
            val minute = if (ints.size > 1) ints[1] else 0
            if (has("pm", "evening", "night", "maalai", "iravu", "மாலை", "இரவு", "ராத்திரி", "மதியம்") && hour < 12) {
                hour += 12
            }
            if (hour > 23 || minute > 59) {
                return Reply("Andha neram sariyilla. Marupadi sollunga.", "அந்த நேரம் சரியில்லை. மறுபடி சொல்லுங்க.")
            }
            val finalHour = hour
            val h12 = if (hour % 12 == 0) 12 else hour % 12
            val ap = if (hour < 12) "AM" else "PM"
            val shown = String.format(Locale.ENGLISH, "%d:%02d %s", h12, minute, ap)
            return Reply("Alarm $shown set panren.", "$shown க்கு அலாரம் வைக்கிறேன்.", { x ->
                launch(
                    x,
                    Intent(AlarmClock.ACTION_SET_ALARM)
                        .putExtra(AlarmClock.EXTRA_HOUR, finalHour)
                        .putExtra(AlarmClock.EXTRA_MINUTES, minute)
                        .putExtra(AlarmClock.EXTRA_MESSAGE, "MS")
                        .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                )
            })
        }

        // ---- timer ----
        if (has("timer", "டைமர்")) {
            if (ints.isEmpty()) {
                return Reply(
                    "Evlo neram timer? '5 minute timer' nu sollunga.",
                    "எவ்வளவு நேரம் டைமர்? 5 நிமிட டைமர்ன்னு சொல்லுங்க."
                )
            }
            val inSeconds = has("second", "seconds", "vinadi", "விநாடி", "வினாடி")
            val secsL = if (inSeconds) ints[0].toLong() else ints[0].toLong() * 60
            if (secsL < 1 || secsL > 86400) {
                return Reply("Andha timer sariyilla.", "அந்த டைமர் சரியில்லை.")
            }
            val secs = secsL.toInt()
            val shown = if (secs % 60 == 0) "${secs / 60} minute" else "$secs second"
            val shownTa = if (secs % 60 == 0) "${secs / 60} நிமிடம்" else "$secs விநாடி"
            return Reply("$shown timer start panren.", "$shownTa டைமர் ஆரம்பிக்கிறேன்.", { x ->
                launch(
                    x,
                    Intent(AlarmClock.ACTION_SET_TIMER)
                        .putExtra(AlarmClock.EXTRA_LENGTH, secs)
                        .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                )
            })
        }

        // ---- call (opens dialer, you press call) ----
        if (has("call", "dial", "டயல்", "கால்")) {
            val digits = c.filter { it.isDigit() }
            if (digits.length >= 6) {
                val spaced = digits.toCharArray().joinToString(" ")
                return Reply("$spaced ku dial panren.", "$spaced க்கு டயல் பண்றேன்.", { x ->
                    launch(x, Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + digits)))
                }, true)
            }
            if (has("call", "dial")) {
                return Reply(
                    "Number sollunga. Udhaaranam, call 9 8 7 6 5 4 3 2 1 0.",
                    "நம்பர் சொல்லுங்க."
                )
            }
        }

        // ---- torch ----
        if (has("torch", "flashlight", "flash", "டார்ச்", "பிளாஷ்")) {
            val off = has("off", "aff", "close", "அணை", "அணைத்து", "அணைச்சு", "நிறுத்து")
            if (off) {
                return Reply("Torch off pannitten.", "டார்ச் அணைச்சுட்டேன்.", { x -> setTorch(x, false) })
            }
            return Reply("Torch on pannitten.", "டார்ச் போட்டுட்டேன்.", { x -> setTorch(x, true) })
        }

        // ---- volume ----
        if (has("volume", "sound", "வால்யூம்", "சவுண்ட்", "சத்தம்")) {
            return when {
                has("max", "full", "maximum", "மேக்ஸ்", "புல்") ->
                    Reply("Volume full pannitten.", "வால்யூம் ஃபுல் பண்ணிட்டேன்.", { x -> adjustVolume(x, 100) })
                has("mute", "silent", "மியூட்") ->
                    Reply("Volume mute pannitten.", "வால்யூம் மியூட் பண்ணிட்டேன்.", { x -> adjustVolume(x, 0) })
                has("down", "decrease", "low", "kammi", "kuraiyu", "கம்மி", "குறை", "கீழ") ->
                    Reply("Volume kammi pannitten.", "வால்யூம் குறைச்சுட்டேன்.", { x -> adjustVolume(x, -1) })
                has("up", "increase", "high", "athigam", "koottu", "kootu", "அதிகம்", "கூட்டு") ->
                    Reply("Volume athigam pannitten.", "வால்யூம் அதிகம் பண்ணிட்டேன்.", { x -> adjustVolume(x, 1) })
                else ->
                    Reply("Volume up illa down nu sollunga.", "வால்யூம் அப் இல்ல டவுன்னு சொல்லுங்க.")
            }
        }

        // ---- stop listening ----
        if (has("stop", "sleep", "thoongu", "nillu", "தூங்கு", "நில்லு")) {
            return Reply(
                "Sari, naan nirutthuren. Thirumba Start listening tap pannunga.",
                "சரி, நான் நிறுத்துறேன். மறுபடி ஸ்டார்ட் லிசனிங் அழுத்துங்க.",
                null,
                true
            )
        }

        // ---- battery ----
        if (has("battery", "பேட்டரி", "charge", "சார்ஜ்")) {
            val pct = try {
                val bm = ctx.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
                bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
            } catch (e: Exception) {
                -1
            }
            if (pct in 1..100) {
                return Reply("Battery $pct percent irukku.", "பேட்டரி $pct சதவீதம் இருக்கு.")
            }
            return Reply("Battery theriyala.", "பேட்டரி தெரியல.")
        }

        // ---- wifi / bluetooth settings ----
        if (has("wifi", "wi-fi", "வைஃபை", "வைபை")) {
            return Reply("Wi-Fi settings open panren.", "வைஃபை செட்டிங்ஸ் திறக்கிறேன்.", { x ->
                launch(x, Intent(Settings.ACTION_WIFI_SETTINGS))
            }, true)
        }
        if (has("bluetooth", "ப்ளூடூத்", "புளூடூத்")) {
            return Reply("Bluetooth settings open panren.", "ப்ளூடூத் செட்டிங்ஸ் திறக்கிறேன்.", { x ->
                launch(x, Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
            }, true)
        }

        // ---- navigate ----
        if (has("navigate", "navigation", "route", "direction", "vazhi", "நேவிகேட்", "வழி")) {
            val place = cleanQuery(c, navFillers)
            if (place.isEmpty()) return Reply("Enga pogaNum?", "எங்க போகணும்?")
            return Reply("$place ku vazhi kaattren.", "$place க்கு வழி காட்டுறேன்.", { x ->
                val nav = Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=" + Uri.encode(place)))
                if (!launch(x, nav)) {
                    launch(x, Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=" + Uri.encode(place))))
                }
            }, true)
        }

        // ---- open an app ----
        if (has("open", "launch", "thira", "ஓபன்", "திற", "திறந்து")) {
            var name = cleanQuery(c, openFillers)
            for (p in appAliases) {
                if (name.contains(p.first)) {
                    name = p.second
                    break
                }
            }
            if (name.isEmpty()) return Reply("Entha app?", "எந்த ஆப்?")
            val app = findApp(ctx, name)
                ?: return Reply("$name app kidaikkala.", "$name ஆப் கிடைக்கல.")
            val label = app.first
            val pkg = app.second
            return Reply("$label open panren.", "$label ஓபன் பண்றேன்.", { x ->
                val li = x.packageManager.getLaunchIntentForPackage(pkg)
                if (li != null) launch(x, li)
            }, true)
        }

        // ---- play a song on YouTube ----
        if (has("play", "paattu", "paadal", "song", "music", "பாட்டு", "பாடல்", "ப்ளே")) {
            val q = cleanQuery(c, playFillers)
            if (q.isEmpty()) return Reply("Entha paattu podanum?", "எந்த பாட்டு போடணும்?")
            return Reply("$q YouTube-la thedaren.", "$q யூடியூப்ல தேடுறேன்.", { x ->
                launch(
                    x,
                    Intent(
                        Intent.ACTION_VIEW,
                        Uri.parse("https://www.youtube.com/results?search_query=" + Uri.encode(q))
                    )
                )
            }, true)
        }

        // ---- web search ----
        if (has("search", "google", "thedu", "தேடு", "கூகுள்")) {
            val q = cleanQuery(c, searchFillers)
            if (q.isEmpty()) return Reply("Enna thedanum?", "என்ன தேடணும்?")
            return Reply("$q nu thedaren.", "$q ன்னு தேடுறேன்.", { x ->
                launch(
                    x,
                    Intent(
                        Intent.ACTION_VIEW,
                        Uri.parse("https://www.google.com/search?q=" + Uri.encode(q))
                    )
                )
            }, true)
        }

        // ---- calculator ----
        if (nums.size >= 2) {
            val a = nums[0]
            val b = nums[1]
            when {
                has("plus", "add", "kootu", "kootti", "கூட்டு", "கூட்டி") || c.contains("+") ->
                    return Reply("Answer " + fmt(a + b), "விடை " + fmt(a + b))
                has("minus", "kazhi", "subtract", "கழி", "கழித்து") || c.contains(" - ") ->
                    return Reply("Answer " + fmt(a - b), "விடை " + fmt(a - b))
                has("multiply", "times", "perukku", "பெருக்கு", "பெருக்கி") || c.contains("*") || c.contains("×") ->
                    return Reply("Answer " + fmt(a * b), "விடை " + fmt(a * b))
                has("divide", "divided", "vagu", "வகு", "வகுத்து") || c.contains("/") -> {
                    if (b == 0.0) {
                        return Reply("Zero-vaala vagukka mudiyaadhu", "பூஜ்யத்தால் வகுக்க முடியாது")
                    }
                    return Reply("Answer " + fmt(a / b), "விடை " + fmt(a / b))
                }
            }
        }

        // ---- small talk ----
        if (has("neram", "time", "நேரம்", "டைம்")) {
            val cal = Calendar.getInstance()
            return Reply(
                "Ippo neram " + SimpleDateFormat("hh:mm a", Locale.ENGLISH).format(cal.time),
                "இப்போ நேரம் " + tamilTime(cal)
            )
        }

        if (has("date", "day", "today", "indru", "inniku", "innikku", "naal", "kizhamai",
                "தேதி", "டேட்", "இன்று", "இன்னைக்கு", "கிழமை", "நாள்")) {
            val cal = Calendar.getInstance()
            val en = SimpleDateFormat("EEEE, dd MMMM yyyy", Locale.ENGLISH).format(cal.time)
            val ta = tamilDays[cal.get(Calendar.DAY_OF_WEEK) - 1] + ", " +
                cal.get(Calendar.DAY_OF_MONTH) + " " +
                tamilMonths[cal.get(Calendar.MONTH)] + " " + cal.get(Calendar.YEAR)
            return Reply("Indru $en", "இன்று $ta")
        }

        if (has("epdi", "eppadi", "எப்படி")) {
            return Reply(
                "Naan nalla irukken, nandri! Neenga epdi irukkeenga?",
                "நான் நல்லா இருக்கேன், நன்றி! நீங்க எப்படி இருக்கீங்க?"
            )
        }

        if (has("hello", "hi", "hey", "vanakkam", "வணக்கம்", "ஹலோ", "ஹாய்")) {
            return Reply("Vanakkam! Naan MS. Enna venum?", "வணக்கம்! நான் எம் எஸ். என்ன வேணும்?")
        }

        if (has("name", "peyar", "who", "yaar", "பெயர்", "யார்")) {
            return Reply(
                "Naan MS, ungalukku mattum pesura voice assistant.",
                "நான் எம் எஸ், உங்களுக்கு மட்டும் பேசுற வாய்ஸ் அசிஸ்டென்ட்."
            )
        }

        if (has("nandri", "thanks", "thank", "நன்றி", "தேங்க்ஸ்")) {
            return Reply("Mikka nandri!", "ரொம்ப நன்றி!")
        }

        if (has("joke", "jokes", "sirippu", "ஜோக்", "சிரிப்பு")) {
            val j = jokes[Random.nextInt(jokes.size)]
            return Reply(j.first, j.second)
        }

        if (has("coin", "toss", "காயின்", "டாஸ்")) {
            return if (Random.nextBoolean()) {
                Reply("Heads vandhuchu", "தலை விழுந்தது")
            } else {
                Reply("Tails vandhuchu", "பூ விழுந்தது")
            }
        }

        if (has("dice", "டைஸ்")) {
            val n = Random.nextInt(1, 7)
            return Reply("Dice-la $n vandhuchu", "டைஸ்ல $n வந்துச்சு")
        }

        if (has("bye", "poitu", "போயிட்டு")) {
            return Reply("Sari, bye! Meendum paarkkalaam.", "சரி, பை! மறுபடி பார்க்கலாம்.")
        }

        if (has("help", "udhavi", "உதவி", "seiyalaam")) {
            return Reply(
                "Neram, date, kanakku, joke, app open, battery, torch, volume, alarm, timer, paattu, search, vazhi ellaam kelunga.",
                "நேரம், தேதி, கணக்கு, ஜோக், ஆப் ஓபன், பேட்டரி, டார்ச், வால்யூம், அலாரம், டைமர், பாட்டு, தேடல், வழி எல்லாம் கேளுங்க."
            )
        }

        return Reply(
            "Mannikkavum, adhukku ennaku pathil theriyala. Help nu kelunga.",
            "மன்னிக்கவும், அதுக்கு எனக்கு பதில் தெரியல. உதவின்னு கேளுங்க."
        )
    }
}
