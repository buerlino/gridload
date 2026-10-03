package io.github.buerlino.gridload.core

import java.util.Locale
import kotlin.math.abs

/** What prices are shown in: [symbol] for amounts ("0.34 CHF/h"), [small] (a hundredth) per kWh ("22.7 Rp/kWh"). */
data class Currency(val symbol: String, val small: String) {
    /** A price in [symbol] per kWh, shown in [small]: "22.7 Rp/kWh", "−1.2 ct/kWh". */
    fun perKwh(price: Double, locale: Locale = Locale.getDefault()): String = "${signed(price * 100, 1, locale)} $small/kWh"

    /** A cost per hour: "0.34 CHF/h", "0.34 €/h". */
    fun perHour(cost: Double, locale: Locale = Locale.getDefault()): String = "${signed(cost, 2, locale)} $symbol/h"

    companion object {
        val CHF = Currency("CHF", "Rp")
        val EUR = Currency("€", "ct")
    }
}

/**
 * What a household in a spot region pays per kWh, in its [country]'s currency: ([spot] +
 * [addOn]) × (1 + VAT). [addOn] is in the small unit (ct) excl. VAT: supplier markup, grid fee
 * per kWh and levies. VAT is [vat] in %, or the country's when it's null (blank). Not a factor
 * on the spot price, since the add-on is often larger than it and the spot price can be negative.
 */
fun ownPrice(spot: Double, addOn: Double, vat: Double?, country: Country): Double =
    (spot + addOn / 100) * (1 + (vat ?: country.vat ?: 0.0) / 100)

/** [value] with [decimals], a real minus sign when it's below 0 as shown, and no "−0.0". */
private fun signed(value: Double, decimals: Int, locale: Locale): String {
    val format = "%.${decimals}f"
    val negative = String.format(Locale.ROOT, format, value).toDouble() < 0
    return (if (negative) "−" else "") + String.format(locale, format, abs(value))
}
