package com.example.voiceassistant

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.floor
import kotlin.random.Random

object Brain {

    private val jokes = listOf(
        "Computer-ku enna pidikkum? Cookies! Aana adhu browser-la thaan irukkum.",
        "Phone-ku fever vandhuchu. Doctor sonnaar: Charge pannunga, appuram restart pannunga.",
        "Oru paamba kitta ketaanga, nee en pesaama irukka? Adhu sonnadhu, naan silent mode-la irukken."
    )

    private fun fmt(d: Double): String =
        if (d == floor(d) && d > -1e15 && d < 1e15) d.toLong().toString() else "%.2f".format(d)

    fun reply(commandRaw: String): String {
        val c = commandRaw.lowercase(Locale.ROOT).trim()
        val tokens = c.split(Regex("[^\\p{L}\\p{M}\\p{N}]+")).filter { it.isNotEmpty() }.toSet()

        // short words must match a whole word, longer words can match anywhere
        fun has(vararg words: String): Boolean =
            words.any { w -> if (w.length <= 3) tokens.contains(w) else c.contains(w) }

        // simple calculator: two numbers and an operator word
        val nums = Regex("-?\\d+(?:\\.\\d+)?").findAll(c).map { it.value.toDouble() }.toList()
        if (nums.size >= 2) {
            val a = nums[0]
            val b = nums[1]
            when {
                has("plus", "add", "kootu", "kootti", "கூட்டு", "கூட்டி") || c.contains("+") ->
                    return "Answer " + fmt(a + b)
                has("minus", "kazhi", "subtract", "கழி", "கழித்து") || c.contains(" - ") ->
                    return "Answer " + fmt(a - b)
                has("multiply", "times", "perukku", "பெருக்கு", "பெருக்கி") || c.contains("*") || c.contains("×") ->
                    return "Answer " + fmt(a * b)
                has("divide", "divided", "vagu", "வகு", "வகுத்து") || c.contains("/") ->
                    return if (b == 0.0) "Zero-vaala vagukka mudiyaadhu" else "Answer " + fmt(a / b)
            }
        }

        return when {
            has("neram", "time", "நேரம்", "டைம்") ->
                "Ippo neram " + SimpleDateFormat("hh:mm a", Locale.ENGLISH).format(Date())

            has("date", "day", "today", "indru", "inniku", "innikku", "naal", "kizhamai",
                "தேதி", "டேட்", "இன்று", "இன்னைக்கு", "கிழமை", "நாள்") ->
                "Indru " + SimpleDateFormat("EEEE, dd MMMM yyyy", Locale.ENGLISH).format(Date())

            has("epdi", "eppadi", "எப்படி") ->
                "Naan nalla irukken, nandri! Neenga epdi irukkeenga?"

            has("hello", "hi", "hey", "vanakkam", "வணக்கம்", "ஹலோ", "ஹாய்") ->
                "Vanakkam! Naan MS. Enna venum?"

            has("name", "peyar", "who", "yaar", "பெயர்", "யார்") ->
                "Naan MS, ungalukku mattum pesura voice assistant."

            has("nandri", "thanks", "thank", "நன்றி", "தேங்க்ஸ்") ->
                "Mikka nandri!"

            has("joke", "jokes", "sirippu", "ஜோக்", "சிரிப்பு") ->
                jokes.random()

            has("coin", "toss", "காயின்", "டாஸ்") ->
                if (Random.nextBoolean()) "Heads vandhuchu" else "Tails vandhuchu"

            has("dice", "டைஸ்") ->
                "Dice-la " + Random.nextInt(1, 7) + " vandhuchu"

            has("bye", "poitu", "போயிட்டு") ->
                "Sari, bye! Meendum paarkkalaam."

            has("help", "udhavi", "உதவி", "seiyalaam") ->
                "Neram, date, kanakku, joke, coin toss, dice ellaam kelunga."

            else -> "Mannikkavum, adhukku ennaku pathil theriyala. Help nu kelunga, naan enna seiyalaam nu solren."
        }
    }
}
