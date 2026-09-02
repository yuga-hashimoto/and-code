package com.yugahashimoto.andcode.core.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Test

class CrashReportSanitizerTest {
    @Test
    fun `sanitizer collapses whitespace and caps log size`() {
        val input = "  first\n\tsecond  " + "x".repeat(1_100)

        val sanitized = CrashReportSanitizer.message(input)

        assertEquals(("first second " + "x".repeat(1_100)).take(1_000), sanitized)
    }

    @Test
    fun `sanitizer makes custom keys safe and caps values`() {
        assertEquals("runtime_error_code", CrashReportSanitizer.key("runtime.error-code"))
        assertEquals("value", CrashReportSanitizer.value(" value "))
        assertEquals(100, CrashReportSanitizer.value("x".repeat(200)).length)
    }
}
