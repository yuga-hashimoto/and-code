package com.yugahashimoto.andcode.feature.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownLiteTest {
    @Test
    fun `plain paragraph becomes a single paragraph block`() {
        val blocks = MarkdownLite.parse("Just plain text.")
        assertEquals(1, blocks.size)
        val paragraph = blocks.single() as MarkdownBlock.Paragraph
        assertEquals(listOf(MarkdownInline.Plain("Just plain text.")), paragraph.inlines)
    }

    @Test
    fun `fenced code block is parsed with language`() {
        val blocks =
            MarkdownLite.parse(
                """
                ```kotlin
                val x = 1
                println(x)
                ```
                """.trimIndent(),
            )
        val code = blocks.single() as MarkdownBlock.CodeBlock
        assertEquals("kotlin", code.language)
        assertEquals("val x = 1\nprintln(x)", code.code)
    }

    @Test
    fun `fenced code block without language`() {
        val blocks = MarkdownLite.parse("```\necho hi\n```")
        val code = blocks.single() as MarkdownBlock.CodeBlock
        assertEquals(null, code.language)
        assertEquals("echo hi", code.code)
    }

    @Test
    fun `inline code is extracted`() {
        val blocks = MarkdownLite.parse("Run `ls -la` to list files")
        val paragraph = blocks.single() as MarkdownBlock.Paragraph
        assertEquals(
            listOf(
                MarkdownInline.Plain("Run "),
                MarkdownInline.Code("ls -la"),
                MarkdownInline.Plain(" to list files"),
            ),
            paragraph.inlines,
        )
    }

    @Test
    fun `bare https url becomes a link`() {
        val paragraph = MarkdownLite.parse("Open https://example.com/docs?q=1.").single() as MarkdownBlock.Paragraph
        assertEquals(
            listOf(
                MarkdownInline.Plain("Open "),
                MarkdownInline.Link("https://example.com/docs?q=1", "https://example.com/docs?q=1"),
                MarkdownInline.Plain("."),
            ),
            paragraph.inlines,
        )
    }

    @Test
    fun `bold text is extracted`() {
        val blocks = MarkdownLite.parse("This is **important** text")
        val paragraph = blocks.single() as MarkdownBlock.Paragraph
        assertEquals(
            listOf(
                MarkdownInline.Plain("This is "),
                MarkdownInline.Bold("important"),
                MarkdownInline.Plain(" text"),
            ),
            paragraph.inlines,
        )
    }

    @Test
    fun `headings at different levels are parsed`() {
        val blocks = MarkdownLite.parse("# Title\n## Subtitle\n### Detail")
        assertEquals(3, blocks.size)
        assertEquals(1, (blocks[0] as MarkdownBlock.Heading).level)
        assertEquals(2, (blocks[1] as MarkdownBlock.Heading).level)
        assertEquals(3, (blocks[2] as MarkdownBlock.Heading).level)
        assertEquals(
            listOf(MarkdownInline.Plain("Title")),
            (blocks[0] as MarkdownBlock.Heading).inlines,
        )
    }

    @Test
    fun `bullet list with dash and star markers`() {
        val blocks = MarkdownLite.parse("- first\n- second\n* third")
        val list = blocks.single() as MarkdownBlock.BulletList
        assertEquals(3, list.items.size)
        assertEquals(listOf(MarkdownInline.Plain("first")), list.items[0])
        assertEquals(listOf(MarkdownInline.Plain("second")), list.items[1])
        assertEquals(listOf(MarkdownInline.Plain("third")), list.items[2])
    }

    @Test
    fun `mixed content keeps block order`() {
        val blocks =
            MarkdownLite.parse(
                """
                # Heading
                Some paragraph text.
                - item one
                - item two
                ```
                code here
                ```
                Trailing paragraph.
                """.trimIndent(),
            )
        assertTrue(blocks[0] is MarkdownBlock.Heading)
        assertTrue(blocks[1] is MarkdownBlock.Paragraph)
        assertTrue(blocks[2] is MarkdownBlock.BulletList)
        assertTrue(blocks[3] is MarkdownBlock.CodeBlock)
        assertTrue(blocks[4] is MarkdownBlock.Paragraph)
    }

    @Test
    fun `bare url with balanced parentheses is preserved`() {
        val paragraph =
            MarkdownLite.parse("See https://en.wikipedia.org/wiki/Foo_(bar) for details")
                .single() as MarkdownBlock.Paragraph
        assertEquals(
            listOf(
                MarkdownInline.Plain("See "),
                MarkdownInline.Link(
                    "https://en.wikipedia.org/wiki/Foo_(bar)",
                    "https://en.wikipedia.org/wiki/Foo_(bar)",
                ),
                MarkdownInline.Plain(" for details"),
            ),
            paragraph.inlines,
        )
    }

    @Test
    fun `bare url wrapped in parentheses trims closing paren`() {
        val paragraph =
            MarkdownLite.parse("(https://example.com)")
                .single() as MarkdownBlock.Paragraph
        assertEquals(
            listOf(
                MarkdownInline.Plain("("),
                MarkdownInline.Link("https://example.com", "https://example.com"),
                MarkdownInline.Plain(")"),
            ),
            paragraph.inlines,
        )
    }

    @Test
    fun `bare url with trailing comma is trimmed`() {
        val paragraph =
            MarkdownLite.parse("Visit https://example.com, then continue")
                .single() as MarkdownBlock.Paragraph
        assertEquals(
            listOf(
                MarkdownInline.Plain("Visit "),
                MarkdownInline.Link("https://example.com", "https://example.com"),
                MarkdownInline.Plain(","),
                MarkdownInline.Plain(" then continue"),
            ),
            paragraph.inlines,
        )
    }

    @Test
    fun `markdown link is parsed`() {
        val paragraph =
            MarkdownLite.parse("See [docs](https://example.com/docs) for info")
                .single() as MarkdownBlock.Paragraph
        assertEquals(
            listOf(
                MarkdownInline.Plain("See "),
                MarkdownInline.Link("docs", "https://example.com/docs"),
                MarkdownInline.Plain(" for info"),
            ),
            paragraph.inlines,
        )
    }

    @Test
    fun `multiple bare urls are all linked`() {
        val paragraph =
            MarkdownLite.parse("Go to https://a.com and https://b.com today")
                .single() as MarkdownBlock.Paragraph
        assertEquals(
            listOf(
                MarkdownInline.Plain("Go to "),
                MarkdownInline.Link("https://a.com", "https://a.com"),
                MarkdownInline.Plain(" and "),
                MarkdownInline.Link("https://b.com", "https://b.com"),
                MarkdownInline.Plain(" today"),
            ),
            paragraph.inlines,
        )
    }

    @Test
    fun `dotted numbers stay plain text`() {
        val paragraph =
            MarkdownLite.parse("versionName 1.2.2, 19.5 MB, v1.2.2")
                .single() as MarkdownBlock.Paragraph
        assertEquals(
            listOf(MarkdownInline.Plain("versionName 1.2.2, 19.5 MB, v1.2.2")),
            paragraph.inlines,
        )
    }

    @Test
    fun `markdown image is parsed`() {
        val paragraph =
            MarkdownLite.parse("Look at ![rabbit](/path/to/rabbit.png) image")
                .single() as MarkdownBlock.Paragraph
        assertEquals(
            listOf(
                MarkdownInline.Plain("Look at "),
                MarkdownInline.Image("rabbit", "/path/to/rabbit.png"),
                MarkdownInline.Plain(" image"),
            ),
            paragraph.inlines,
        )
    }
}
