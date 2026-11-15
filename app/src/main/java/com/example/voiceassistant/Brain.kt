package com.example.voiceassistant

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object Brain {
    fun reply(commandRaw: String): String {
        val c = commandRaw.lowercase(Locale.ROOT)

        return when {
            has(c, "neram", "time", "நேரம்") ->
                "Ippo neram " + SimpleDateFormat("hh:mm a", Locale.ENGLISH).format(Date())

            has(c, "date", "indru", "inniku", "இன்று", "தேதி") ->
                "Indru " + SimpleDateFormat("EEEE, dd MMMM yyyy", Locale.ENGLISH).format(Date())

            has(c, "hello", "hi", "vanakkam", "வணக்கம்") ->
                "Vanakkam! Naan MS. Enna venum?"

            has(c, "yaar", "who are you", "peyar", "name") ->
                "Naan MS, ungalukku mattum pesura voice assistant."

            has(c, "nandri", "thanks", "thank you", "நன்றி") ->
                "Mikka nandri!"

            else -> "Mannikkavum, \"$commandRaw\" nu purinjuthu, aana adhukku enakku pathil theriyala."
        }
    }

    private fun has(text: String, vararg words: String) = words.any { text.contains(it) }
}
