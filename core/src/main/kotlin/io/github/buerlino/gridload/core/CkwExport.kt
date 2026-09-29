package io.github.buerlino.gridload.core

import kotlinx.serialization.Serializable
import org.w3c.dom.Element
import java.io.InputStream
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.zip.ZipInputStream
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Energy used in one month. [hours] is the part of the month the data covers: the whole month,
 * or less for the month an export was made in.
 */
@Serializable
data class MonthUsage(val year: Int, val month: Int, val kwh: Double, val hours: Double) {
    val yearMonth: YearMonth get() = YearMonth.of(year, month)
    val averageKw: Double get() = kwh / hours
}

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

/** What one CKW export contained: monthly totals or hourly values per day. */
data class CkwImport(val months: List<MonthUsage> = emptyList(), val days: List<DayUsage> = emptyList())

/**
 * Parses a CKW portal export ("Aktueller Zeitraum (Excel) - ....xlsx", format in CLAUDE.md). The
 * header's unit says what it holds: one row per month (`Monat`) or per hour (`Stunde`).
 */
fun parseCkwExport(xlsx: InputStream): CkwImport {
    val rows = readFirstSheet(xlsx)
    fun header(name: String) = rows.firstOrNull { it.getOrNull(0) == name }?.getOrNull(1)
    val header = rows.indexOfFirst { it.getOrNull(0) == "Zeitraum" }
    require(header >= 0) { "No Zeitraum table in the file" }
    val table = rows.drop(header + 1)
    return when (val unit = header("Einheit")) {
        "Monat" -> CkwImport(months = monthRows(table, header("Auswertungszeitraum:") ?: error("No Auswertungszeitraum in the file")))
        "Stunde" -> CkwImport(days = hourRows(table))
        else -> error("Unknown unit: $unit")
    }
}

/** Only the monthly totals of a CKW export; for tests. */
fun parseCkwMonthlyExport(xlsx: InputStream): List<MonthUsage> = parseCkwExport(xlsx).months

/**
 * One row per month. The period ("01.01.2025 - 31.12.2025") gives the hours covered; months
 * without data (`-`) are skipped.
 */
private fun monthRows(table: List<List<String>>, period: String): List<MonthUsage> {
    val dates = DateTimeFormatter.ofPattern("dd.MM.yyyy")
    val (from, to) = period.split(" - ").map { LocalDate.parse(it.trim(), dates) }
    return table.mapNotNull { row ->
        val kwh = row.getOrNull(1)?.toDoubleOrNull() ?: return@mapNotNull null
        val month = parseMonthLabel(row[0]) ?: return@mapNotNull null
        val start = maxOf(month.atDay(1), from).atStartOfDay(TARIFF_ZONE)
        val end = minOf(month.atEndOfMonth(), to).plusDays(1).atStartOfDay(TARIFF_ZONE)
        MonthUsage(month.year, month.monthValue, kwh, Duration.between(start, end).toMinutes() / 60.0)
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

private val MONTHS = listOf("jan", "feb", "mär", "apr", "mai", "jun", "jul", "aug", "sep", "okt", "nov", "dez")

/** "Jan.-24", "März-24", "Sept.-24" to a month; null for anything else ("Total"). */
private fun parseMonthLabel(label: String): YearMonth? {
    val name = label.substringBefore('-').trimEnd('.').lowercase()
    val month = MONTHS.indexOfFirst { name.startsWith(it) }
    val year = label.substringAfterLast('-', "").toIntOrNull()
    if (month < 0 || year == null) return null
    return YearMonth.of(if (year < 100) 2000 + year else year, month + 1)
}

/**
 * The first worksheet of an `.xlsx` (a zip of XML files) as rows of cell texts, with "" for
 * empty cells. Just enough for the portal exports: shared strings, inline strings and numbers.
 */
internal fun readFirstSheet(xlsx: InputStream): List<List<String>> {
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
