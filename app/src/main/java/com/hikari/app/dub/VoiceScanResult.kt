package com.hikari.app.dub

/**
 * What listening to the ORIGINAL voices of a video found, one entry per
 * subtitle line (same order as the cue list that was scanned).
 *
 *  - [voice]  which person speaks the line: the same number = the same voice
 *             all through the video. -1 = this line could not be told.
 *  - [gender] man or woman, worked out from the pitch of the real voice.
 *             [Gender.UNKNOWN] = could not be told.
 */
class VoiceScanResult(
    val voice: IntArray,
    val gender: Array<Gender>,
) {
    val size: Int get() = voice.size

    fun voiceOf(i: Int): Int = if (i >= 0 && i < voice.size) voice[i] else -1

    fun genderOf(i: Int): Gender = if (i >= 0 && i < gender.size) gender[i] else Gender.UNKNOWN

    /** Lines whose speaker (or at least sex) is known. */
    fun known(): Int {
        var n = 0
        for (i in 0 until size) if (voice[i] >= 0 || gender[i] != Gender.UNKNOWN) n++
        return n
    }

    /** "4 voices: 2 men, 2 women" (voices that have a sex), for the status message. */
    fun summary(): String {
        val sexOf = HashMap<Int, Gender>()
        for (i in 0 until size) {
            val v = voice[i]
            if (v >= 0 && !sexOf.containsKey(v)) sexOf[v] = gender[i]
        }
        val men = sexOf.values.count { it == Gender.MALE }
        val women = sexOf.values.count { it == Gender.FEMALE }
        var looseMen = 0
        var looseWomen = 0
        for (i in 0 until size) {
            if (voice[i] < 0) {
                if (gender[i] == Gender.MALE) looseMen++
                if (gender[i] == Gender.FEMALE) looseWomen++
            }
        }
        return sexOf.size.toString() + " voices (" + men + " men, " + women + " women), " +
            known() + " of " + size + " lines told" +
            (if (looseMen + looseWomen > 0) " (" + looseMen + " + " + looseWomen + " by pitch only)" else "")
    }

    /** Text form, so the helper process can hand the answer over through a file. */
    fun serialize(): String {
        val sb = StringBuilder()
        sb.append("v1 ").append(size).append('\n')
        for (i in 0 until size) {
            val g = when (gender[i]) {
                Gender.MALE -> 'M'
                Gender.FEMALE -> 'F'
                else -> 'U'
            }
            sb.append(voice[i]).append(' ').append(g).append('\n')
        }
        return sb.toString()
    }

    companion object {
        /** Null when the text is not a valid answer or has a different line count than [expected]. */
        fun parse(text: String, expected: Int): VoiceScanResult? {
            return try {
                val lines = text.split('\n')
                val head = lines.firstOrNull()?.trim().orEmpty()
                if (!head.startsWith("v1 ")) return null
                val n = head.substring(3).trim().toInt()
                if (n != expected || n < 0) return null
                val voice = IntArray(n) { -1 }
                val gender = Array(n) { Gender.UNKNOWN }
                for (i in 0 until n) {
                    val parts = lines.getOrNull(i + 1)?.trim()?.split(' ') ?: return null
                    if (parts.size < 2) return null
                    voice[i] = parts[0].toInt()
                    gender[i] = when (parts[1]) {
                        "M" -> Gender.MALE
                        "F" -> Gender.FEMALE
                        else -> Gender.UNKNOWN
                    }
                }
                VoiceScanResult(voice, gender)
            } catch (t: Throwable) {
                null
            }
        }
    }
}
