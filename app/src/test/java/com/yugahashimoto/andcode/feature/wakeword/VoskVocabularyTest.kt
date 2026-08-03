package com.yugahashimoto.andcode.feature.wakeword

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * The dictionary check that stands between a typed phrase and a recogniser that can never fire.
 *
 * The two readers exist because the two shipped models disagree: the Japanese one carries
 * `graph/words.txt`, the English one keeps its words only inside the `graph/Gr.fst` header.
 */
class VoskVocabularyTest {
    @get:Rule val folder = TemporaryFolder()

    @Test
    fun `a phrase made of known words has nothing unknown about it`() {
        val model = modelWithWordList("hey", "and", "code")

        assertEquals(emptyList<String>(), VoskVocabulary.unknownWords(model, "Hey And Code"))
    }

    @Test
    fun `the word the model does not know is named, not just rejected`() {
        // Naming it is the whole point: "andcode" is one keystroke away from "and code", and the
        // failure it causes is otherwise silent.
        val model = modelWithWordList("hey", "and", "code")

        assertEquals(listOf("andcode"), VoskVocabulary.unknownWords(model, "hey andcode"))
    }

    @Test
    fun `every unknown word is reported, not only the first`() {
        val model = modelWithWordList("hey", "and", "code")

        assertEquals(listOf("ok", "andcode"), VoskVocabulary.unknownWords(model, "ok andcode"))
    }

    @Test
    fun `a word list carries an id after each word`() {
        // Vosk writes "<word> <id>" per line, and the id is nobody's business here.
        val model = folder.newFolder("ja")
        File(model, "graph").mkdirs()
        File(model, "graph/words.txt").writeText("<eps> 0\nヘイ 29549\nアンド 190303\nコード 74374\n")

        assertEquals(emptyList<String>(), VoskVocabulary.unknownWords(model, "ヘイ アンド コード"))
        assertEquals(listOf("アンドコード"), VoskVocabulary.unknownWords(model, "ヘイ アンドコード"))
    }

    @Test
    fun `the English model is read out of the grammar archive instead`() {
        // vosk-model-small-en-us-0.15 ships no words.txt at all - its word symbols live in the
        // OpenFst header of Gr.fst, which is the only place left to look.
        val model = folder.newFolder("en")
        File(model, "graph").mkdirs()
        File(model, "graph/Gr.fst").writeBytes(grammarArchive("<eps>", "hey", "and", "code"))

        assertEquals(emptyList<String>(), VoskVocabulary.unknownWords(model, "hey and code"))
        assertEquals(listOf("andcode"), VoskVocabulary.unknownWords(model, "hey andcode"))
    }

    @Test
    fun `an unreadable dictionary is not treated as a bad phrase`() {
        // Refusing to listen because the check itself failed would be worse than the mistake it
        // is there to catch, so "cannot tell" is its own answer.
        val empty = folder.newFolder("empty")

        assertNull(VoskVocabulary.unknownWords(empty, "hey and code"))
    }

    @Test
    fun `a truncated grammar archive reads as unknown rather than as a pass`() {
        val model = folder.newFolder("torn")
        File(model, "graph").mkdirs()
        File(model, "graph/Gr.fst").writeBytes(grammarArchive("hey").copyOfRange(0, 20))

        assertNull(VoskVocabulary.unknownWords(model, "hey"))
    }

    @Test
    fun `a blank phrase is the default phrase, which the model must still know`() {
        val model = modelWithWordList("hey", "and", "code")

        assertEquals(emptyList<String>(), VoskVocabulary.unknownWords(model, "   "))
    }

    private fun modelWithWordList(vararg words: String): File {
        val model = folder.newFolder(words.joinToString("-"))
        File(model, "graph").mkdirs()
        File(model, "graph/words.txt").writeText(words.mapIndexed { index, word -> "$word $index" }.joinToString("\n"))
        return model
    }

    /**
     * An OpenFst archive carrying nothing but the symbol table this reads: magic, the two
     * length-prefixed type names, version and flags, the five fixed-width fields nothing here
     * needs, and then the table itself.
     */
    private fun grammarArchive(vararg symbols: String): ByteArray {
        val out = ByteArrayOutputStream()
        out.writeLittleEndianInt(0x7EB2FDD6.toInt())
        out.writeLengthPrefixed("ngram")
        out.writeLengthPrefixed("standard")
        out.writeLittleEndianInt(4)
        out.writeLittleEndianInt(3)
        repeat(32) { out.write(0) }
        out.writeLittleEndianInt(0x7EB2FB74)
        out.writeLengthPrefixed("words.txt")
        out.writeLittleEndianLong(symbols.size.toLong())
        out.writeLittleEndianLong(symbols.size.toLong())
        symbols.forEachIndexed { index, symbol ->
            out.writeLengthPrefixed(symbol)
            out.writeLittleEndianLong(index.toLong())
        }
        return out.toByteArray()
    }

    private fun ByteArrayOutputStream.writeLittleEndianInt(value: Int) {
        repeat(4) { write((value ushr (it * 8)) and 0xFF) }
    }

    private fun ByteArrayOutputStream.writeLittleEndianLong(value: Long) {
        repeat(8) { write(((value ushr (it * 8)) and 0xFF).toInt()) }
    }

    private fun ByteArrayOutputStream.writeLengthPrefixed(value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        writeLittleEndianInt(bytes.size)
        write(bytes)
    }
}
