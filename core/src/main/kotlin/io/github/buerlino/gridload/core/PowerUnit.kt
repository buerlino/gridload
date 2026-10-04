package io.github.buerlino.gridload.core

import java.util.Locale

/**
 * How power is shown: kW with one decimal, or W in steps of 10 (appliances are labelled in W).
 * Values are kept in kW either way. [id] is what the setting saves.
 */
enum class PowerUnit(val id: String) {
    KW("kW"),
    W("W");

    /** The number alone, as shown: "1.3" or "1340". */
    fun number(kw: Double, locale: Locale = Locale.getDefault()): String = when (this) {
        KW -> String.format(locale, "%.1f", kw)
        W -> String.format(locale, "%d", Math.round(kw * 100) * 10)
    }

    /** "1.3 kW" or "1340 W". */
    fun format(kw: Double): String = "${number(kw)} $id"

    /** A small value, like the base load, with two decimals in kW: "0.08 kW" or "80 W". */
    fun formatSmall(kw: Double, locale: Locale = Locale.getDefault()): String =
        "${if (this == KW) String.format(locale, "%.2f", kw) else number(kw, locale)} $id"

    /** [kw] rounded as it's shown, so differences of shown values add up on screen. */
    fun round(kw: Double): Double = number(kw, Locale.ROOT).toDouble() / if (this == W) 1000 else 1

    /** A value typed in this unit, in kW; null unless it's a number above 0. */
    fun parse(text: String): Double? = parsePositive(text)?.let { if (this == W) it / 1000 else it }

    /** [kw] as the goal field shows it, ready to edit. */
    fun field(kw: Double): String = number(kw, Locale.ROOT)

    companion object {
        /** The saved setting; kW when there is none. */
        fun of(id: String?): PowerUnit = entries.find { it.id == id } ?: KW
    }
}
