package com.personal.bubuprotect.core.importer

/**
 * An RFC 4180 CSV parser.
 *
 * ### Why not split on commas
 *
 * Because every real export breaks that immediately. Passwords contain commas. Notes contain
 * newlines. Titles contain quotes. A naive split silently shifts every column after the offending
 * one, which does not fail loudly - it produces a vault where some entry's password is half a note
 * and its username is a URL. For a one-shot migration the user will never re-check, a parser that is
 * *quietly* wrong is worse than one that refuses.
 *
 * So this handles the whole grammar: quoted fields, `""` as an escaped quote inside them, embedded
 * commas and newlines, `CRLF` or `LF` line endings, and a leading byte-order mark.
 *
 * ### Deliberately tolerant in one direction only
 *
 * Rows with the wrong number of columns are returned as they are, not padded or rejected here -
 * [CredentialImporter] decides what to do with a short row, because only it knows which columns
 * actually mattered. What this will not do is guess where a field boundary was.
 */
internal object CsvReader {

    private const val BOM = '\uFEFF'

    /**
     * @return one list per record, in file order. Blank lines are dropped; a trailing newline does
     *   not produce a phantom empty record.
     */
    fun parse(text: String): List<List<String>> {
        if (text.isEmpty()) return emptyList()

        val rows = mutableListOf<List<String>>()
        var row = mutableListOf<String>()
        val field = StringBuilder()
        var inQuotes = false
        var index = 0

        // Excel and several exporters prepend a BOM. Left in place it becomes part of the first
        // header name, and every column mapping against that header then misses.
        if (text[0] == BOM) index = 1

        fun endField() {
            row.add(field.toString())
            field.setLength(0)
        }

        fun endRow() {
            endField()
            // A record of one empty field is a blank line, not an entry.
            if (row.size > 1 || row.first().isNotEmpty()) rows.add(row)
            row = mutableListOf()
        }

        while (index < text.length) {
            val character = text[index]
            when {
                inQuotes -> when {
                    character != '"' -> field.append(character)

                    // A doubled quote inside a quoted field is one literal quote.
                    index + 1 < text.length && text[index + 1] == '"' -> {
                        field.append('"')
                        index++
                    }

                    else -> inQuotes = false
                }

                character == '"' -> {
                    // Only opens a quoted field at the start of one. Mid-field quotes are literal,
                    // which is what unquoted exports from some tools produce.
                    if (field.isEmpty()) inQuotes = true else field.append(character)
                }

                character == ',' -> endField()

                character == '\r' -> {
                    // Swallow the LF of a CRLF pair rather than treating it as a second row break.
                    if (index + 1 < text.length && text[index + 1] == '\n') index++
                    endRow()
                }

                character == '\n' -> endRow()

                else -> field.append(character)
            }
            index++
        }

        // Whatever is still buffered is the last record, unless the file ended on a line break.
        if (field.isNotEmpty() || row.isNotEmpty()) endRow()

        return rows
    }
}
