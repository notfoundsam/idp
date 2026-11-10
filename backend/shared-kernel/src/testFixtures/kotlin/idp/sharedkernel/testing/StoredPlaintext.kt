package idp.sharedkernel.testing

import java.util.Base64
import java.util.HexFormat

private const val SHORTEST = 8
private val ASCII_UPPER = 'A'.code..'Z'.code
private val ASCII_CASE = 'a'.code - 'A'.code

/**
 * A value a test gave the application in clear, which no store may keep as it is. A finding names its [label], never the value.
 *
 * A text is found in any case, as UTF-8, base64 or hex. Raw bytes are found as they are, in base64 or hex.
 */
class Plaintext private constructor(
    val label: String,
    private val raw: ByteArray,
    private val text: String?,
) {
    private val lowered: ByteArray? = text?.lowercase()?.toByteArray()
    private val encodings: List<String> =
        listOf(Base64.getEncoder().withoutPadding().encodeToString(raw), Base64.getUrlEncoder().withoutPadding().encodeToString(raw))
    private val hex: String = HexFormat.of().formatHex(raw)

    init {
        require(raw.size >= SHORTEST) { "$label is too short to tell a leak from chance" }
    }

    /** A byte array is searched as bytes, anything else as its text. */
    fun foundIn(value: Any): Boolean =
        when (value) {
            is ByteArray -> inBytes(value)
            else -> inText(value.toString())
        }

    private fun inText(value: String): Boolean =
        (text != null && value.contains(text, ignoreCase = true)) ||
            encodings.any { it in value } ||
            value.contains(hex, ignoreCase = true)

    private fun inBytes(value: ByteArray): Boolean =
        value.holds(raw) ||
            (lowered != null && value.asciiLowercase().holds(lowered)) ||
            inText(String(value, Charsets.ISO_8859_1))

    override fun toString() = label

    companion object {
        fun text(
            label: String,
            value: String,
        ) = Plaintext(label, value.toByteArray(), value)

        fun bytes(
            label: String,
            value: ByteArray,
        ) = Plaintext(label, value.copyOf(), null)
    }
}

/** A [Plaintext] in a column, named `schema.table.column`. */
data class Finding(
    val column: String,
    val label: String,
)

class StoredRows(
    val findings: Set<Finding>,
    /** Rows by `schema.table`, so a test can show that the scan saw the tables it meant to. */
    val rows: Map<String, Int>,
)

/** Reads every row of every table in every schema, as the superuser, and looks for each of [secrets] in every value. */
fun TestDatabase.scan(secrets: Collection<Plaintext>): StoredRows {
    val findings = mutableSetOf<Finding>()
    val rows = mutableMapOf<String, Int>()
    superuser().connection.use { connection ->
        val tables =
            connection.createStatement().use { statement ->
                statement
                    .executeQuery(
                        """
                        SELECT table_schema, table_name FROM information_schema.tables
                        WHERE table_type = 'BASE TABLE' AND table_schema NOT IN ('pg_catalog', 'information_schema')
                        """.trimIndent(),
                    ).use { result -> buildList { while (result.next()) add(result.getString(1) to result.getString(2)) } }
            }
        tables.forEach { (schema, table) ->
            connection.createStatement().use { statement ->
                statement.executeQuery("""SELECT * FROM "$schema"."$table"""").use { result ->
                    val columns = (1..result.metaData.columnCount).associateWith { result.metaData.getColumnName(it) }
                    var count = 0
                    while (result.next()) {
                        count++
                        columns.forEach { (index, column) ->
                            values(result.getObject(index)).forEach { value ->
                                secrets.filter { it.foundIn(value) }.forEach { findings += Finding("$schema.$table.$column", it.label) }
                            }
                        }
                    }
                    rows["$schema.$table"] = count
                }
            }
        }
    }
    return StoredRows(findings, rows)
}

private fun values(value: Any?): List<Any> =
    when (value) {
        null -> emptyList()
        is java.sql.Array -> (value.array as Array<*>).flatMap(::values)
        else -> listOf(value)
    }

private fun ByteArray.asciiLowercase(): ByteArray = map { if (it in ASCII_UPPER) (it + ASCII_CASE).toByte() else it }.toByteArray()

private fun ByteArray.holds(needle: ByteArray): Boolean =
    needle.isNotEmpty() && (0..size - needle.size).any { start -> needle.indices.all { this[start + it] == needle[it] } }
