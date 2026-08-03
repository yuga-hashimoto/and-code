package com.yugahashimoto.andcode.feature.wakeword

import java.io.DataInputStream
import java.io.File
import java.io.InputStream

/**
 * The words a downloaded speech model is able to recognise at all.
 *
 * A recogniser constrained to a grammar can only ever return words from the model's dictionary, and
 * Vosk drops any grammar entry that is not in it - with a warning that goes to native stderr, which
 * Android throws away. A phrase built out of dropped words leaves a recogniser that never fires,
 * which from the outside is indistinguishable from a microphone that is not working: the service
 * runs, the microphone indicator is lit, and nothing ever happens. So the phrase is checked against
 * the dictionary before it is handed over, both in settings and again in the service.
 *
 * Where the dictionary lives depends on the model. The Japanese small model ships
 * `graph/words.txt`; the English one ships no word list at all and keeps its word symbols only
 * inside the OpenFst header of `graph/Gr.fst`. Both are read here so neither language skips the
 * check.
 */
object VoskVocabulary {
    /**
     * The words of [phrase] the model at [modelDirectory] does not know, or null when the
     * dictionary could not be read.
     *
     * Null is not "no unknown words": refusing to listen because the check itself failed would be a
     * worse failure than the one it exists to catch, so callers treat it as "cannot tell".
     */
    fun unknownWords(
        modelDirectory: File,
        phrase: String,
    ): List<String>? {
        val wanted = WakeWordGrammar.words(phrase)
        if (wanted.isEmpty()) return emptyList()
        val known = knownAmong(modelDirectory, wanted.toSet()) ?: return null
        return wanted.filterNot { it in known }.distinct()
    }

    /** Which of [wanted] the model knows. Only the asked-for words are held, never the dictionary. */
    private fun knownAmong(
        modelDirectory: File,
        wanted: Set<String>,
    ): Set<String>? {
        val wordList = File(modelDirectory, WORD_LIST_PATH)
        if (wordList.isFile) {
            return runCatching { readWordList(wordList, wanted) }.getOrNull()
        }
        val grammar = File(modelDirectory, GRAMMAR_PATH)
        if (grammar.isFile) {
            return runCatching {
                grammar.inputStream().buffered().use { readSymbolTable(it, wanted) }
            }.getOrNull()
        }
        return null
    }

    private fun readWordList(
        file: File,
        wanted: Set<String>,
    ): Set<String> {
        val found = mutableSetOf<String>()
        file.bufferedReader().useLines { lines ->
            for (line in lines) {
                // Each line is "<word> <id>", and the id is nobody's business here.
                val word = line.substringBefore(' ')
                if (word in wanted) found += word
                if (found.size == wanted.size) break
            }
        }
        return found
    }

    /**
     * Walks the word symbols out of an OpenFst archive.
     *
     * The header is fixed-shape: magic, two length-prefixed type names, version, flags, then five
     * fixed-width fields nothing here needs, and only then the symbol tables the flags announced.
     * Everything is little-endian, which is the opposite of what [DataInputStream] reads.
     */
    private fun readSymbolTable(
        input: InputStream,
        wanted: Set<String>,
    ): Set<String> {
        val data = DataInputStream(input)
        require(data.readLittleEndianInt() == FST_MAGIC) { "Not an OpenFst archive" }
        data.readLengthPrefixed()
        data.readLengthPrefixed()
        data.readLittleEndianInt()
        val flags = data.readLittleEndianInt()
        require(flags and FLAG_HAS_INPUT_SYMBOLS != 0) { "The archive carries no word symbols" }
        data.readFully(ByteArray(HEADER_TAIL_BYTES))

        require(data.readLittleEndianInt() == SYMBOL_TABLE_MAGIC) { "Not an OpenFst symbol table" }
        data.readLengthPrefixed()
        data.readLittleEndianLong()
        val size = data.readLittleEndianLong()
        require(size in 0..MAX_SYMBOLS) { "Implausible symbol count $size" }

        val found = mutableSetOf<String>()
        for (index in 0 until size) {
            if (found.size == wanted.size) break
            val symbol = data.readLengthPrefixed()
            data.readLittleEndianLong()
            if (symbol in wanted) found += symbol
        }
        return found
    }

    private fun DataInputStream.readLittleEndianInt(): Int = Integer.reverseBytes(readInt())

    private fun DataInputStream.readLittleEndianLong(): Long = java.lang.Long.reverseBytes(readLong())

    private fun DataInputStream.readLengthPrefixed(): String {
        val length = readLittleEndianInt()
        require(length in 0..MAX_SYMBOL_BYTES) { "Implausible symbol length $length" }
        val bytes = ByteArray(length)
        readFully(bytes)
        return String(bytes, Charsets.UTF_8)
    }

    private const val WORD_LIST_PATH = "graph/words.txt"
    private const val GRAMMAR_PATH = "graph/Gr.fst"
    private const val FST_MAGIC = 0x7EB2FDD6.toInt()
    private const val SYMBOL_TABLE_MAGIC = 0x7EB2FB74
    private const val FLAG_HAS_INPUT_SYMBOLS = 0x1

    /** Properties, start state, state count and arc count: read past, never read. */
    private const val HEADER_TAIL_BYTES = 32

    // Bounds on what a truncated or mismatched file may claim, so a bad read fails rather than
    // trying to allocate whatever the bytes happened to spell.
    private const val MAX_SYMBOL_BYTES = 4096
    private const val MAX_SYMBOLS = 10_000_000L
}
