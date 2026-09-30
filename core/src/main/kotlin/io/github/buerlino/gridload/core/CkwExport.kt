package io.github.buerlino.gridload.core

import kotlinx.serialization.Serializable
import org.w3c.dom.Element
import java.io.InputStream
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.util.zip.ZipInputStream
import javax.xml.parsers.DocumentBuilderFactory

/** Hourly energy of one complete local day, in order from midnight (23 or 25 hours on DST days). */
@Serializable
data class DayUsage(val date: String, val kwh: List<Double>) {
    val localDate: LocalDate get() = LocalDate.parse(date)

    /** The start of each hour, so DST days come out right. */
    fun hourStarts(): List<Instant> {
        val midnight = localDate.atStartOfDay(TARIFF_ZONE).toInstant()
        return kwh.indices.map { midnight + Duration.ofHours(it.toLong()) }
    }
}

/**
 * Parses a CKW portal export ("Aktueller Zeitraum (Excel) - ....xlsx", format in CLAUDE.md). Only
 * the day view's export is used: one row per hour (`Einheit` = `Stunde`). The year and month
 * views (one row per month or day) have no hours, so they are refused with a message that says
 * which export to take instead.
 */
fun parseCkwExport(xlsx: InputStream): List<DayUsage> {
    val rows = readFirstSheet(xlsx)
    val header = rows.indexOfFirst { it.getOrNull(0) == "Zeitraum" }
    require(header >= 0) { "This isn't an export from the CKW customer portal." }
    return when (val unit = rows.firstOrNull { it.getOrNull(0) == "Einheit" }?.getOrNull(1)) {
        "Stunde" -> hourRows(rows.drop(header + 1))
        "Monat" -> error("This is a year export (one value per month). GridLoad needs the day view's exports, with one value per hour.")
        "Tag" -> error("This is a month export (one value per day). GridLoad needs the day view's exports, with one value per hour.")
        else -> error("Unknown unit: $unit. GridLoad needs the day view's exports, with one value per hour.")
    }
}

private val HOUR_LABEL = Regex("""(\d{1,2})\.(\d{1,2})\.(\d{4})\s+\d{1,2}:\d{2}""")

/**
 * One row per hour ("1.9.2026  01:00"), usually one day per file. Only complete days are kept,
 * so each value's position gives its hour; a day with gaps (`-`, or today) is left out.
 */
private fun hourRows(table: List<List<String>>): List<DayUsage> {
    val byDay = linkedMapOf<LocalDate, MutableList<Double?>>()
    table.forEach { row ->
        val match = HOUR_LABEL.find(row.getOrNull(0).orEmpty()) ?: return@forEach
        val (day, month, year) = match.destructured
        byDay.getOrPut(LocalDate.of(year.toInt(), month.toInt(), day.toInt())) { mutableListOf() } += row.getOrNull(1)?.toDoubleOrNull()
    }
    return byDay.mapNotNull { (date, values) ->
        val hours = Duration.between(date.atStartOfDay(TARIFF_ZONE), date.plusDays(1).atStartOfDay(TARIFF_ZONE)).toHours()
        if (values.size.toLong() != hours || values.any { it == null }) return@mapNotNull null
        DayUsage(date.toString(), values.map { it!! })
    }
}

/**
 * The first worksheet of an `.xlsx` (a zip of XML files) as rows of cell texts, with "" for
 * empty cells. Just enough for the portal exports: shared strings, inline strings and numbers.
 */
private fun readFirstSheet(xlsx: InputStream): List<List<String>> {
    val parts = mutableMapOf<String, ByteArray>()
    ZipInputStream(xlsx).use { zip ->
        generateSequence { zip.nextEntry }.forEach { entry ->
            if (entry.name == "xl/sharedStrings.xml" || entry.name == "xl/worksheets/sheet1.xml") {
                parts[entry.name] = zip.readBytes()
            }
        }
    }
    val builder = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }.newDocumentBuilder()
    fun parse(name: String) = parts[name]?.inputStream()?.use { builder.parse(it).documentElement }
    val shared = parse("xl/sharedStrings.xml")?.children("si")?.map { it.text() }.orEmpty()
    val sheet = parse("xl/worksheets/sheet1.xml") ?: error("Not an Excel file")
    return sheet.getElementsByTagNameNS("*", "row").let { list -> (0 until list.length).map { list.item(it) as Element } }
        .map { row ->
            val cells = sortedMapOf<Int, String>()
            row.children("c").forEach { c ->
                val column = c.getAttribute("r").takeWhile { it.isLetter() }.fold(0) { n, ch -> n * 26 + (ch - 'A' + 1) } - 1
                val value = c.children("v").firstOrNull()?.textContent.orEmpty()
                cells[column] = when (c.getAttribute("t")) {
                    "s" -> shared[value.toInt()]
                    "inlineStr" -> c.children("is").firstOrNull()?.text().orEmpty()
                    else -> value
                }
            }
            val width = (cells.keys.maxOrNull() ?: -1) + 1
            List(width) { cells[it].orEmpty() }
        }
}

private fun Element.children(name: String): List<Element> =
    (0 until childNodes.length).map { childNodes.item(it) }.filterIsInstance<Element>().filter { it.localName == name }

/** The text of all `<t>` runs, so rich text ("Energieverbrauch\n(kWh)") comes out whole. */
private fun Element.text(): String =
    getElementsByTagNameNS("*", "t").let { list -> (0 until list.length).joinToString("") { list.item(it).textContent } }.trim()
