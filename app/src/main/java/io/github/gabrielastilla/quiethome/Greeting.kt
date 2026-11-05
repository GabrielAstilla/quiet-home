package io.github.gabrielastilla.quiethome

import java.util.Calendar

/** Time-of-day greeting using the TV's local time. */
object Greeting {

    enum class Period(val phrase: String) {
        MORNING("Good morning"),
        NOON("Good noon"),
        AFTERNOON("Good afternoon"),
        EVENING("Good evening"),
    }

    /**
     * 5:00–11:59 morning · 12:00–12:59 noon · 13:00–17:59 afternoon · 18:00–4:59 evening.
     */
    fun period(hour: Int): Period = when (hour) {
        in 5..11 -> Period.MORNING
        12 -> Period.NOON
        in 13..17 -> Period.AFTERNOON
        else -> Period.EVENING
    }

    fun period(cal: Calendar = Calendar.getInstance()) = period(cal.get(Calendar.HOUR_OF_DAY))

    /** "Good morning, Gabriel" — or just "Good morning" (no trailing comma) when the name is blank or hidden. */
    fun text(p: Prefs, cal: Calendar = Calendar.getInstance()): String =
        compose(period(cal), p.displayName, p.greetingName)

    fun compose(period: Period, name: String, showName: Boolean): String {
        val n = name.trim()
        return if (showName && n.isNotEmpty()) "${period.phrase}, $n" else period.phrase
    }
}
