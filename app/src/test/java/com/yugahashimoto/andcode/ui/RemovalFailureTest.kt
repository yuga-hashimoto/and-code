package com.yugahashimoto.andcode.ui

import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class RemovalFailureTest {
    @Test
    fun `delete succeeds when at least one runtime returns true`() {
        assertNull(removalFailure(RemovalKind.DELETE, listOf(Result.success(true), Result.success(false))))
        assertNull(removalFailure(RemovalKind.DELETE, listOf(Result.failure(IllegalStateException("no")), Result.success(true))))
    }

    @Test
    fun `delete fails when every runtime returns false`() {
        val failure = removalFailure(RemovalKind.DELETE, listOf(Result.success(false), Result.success(false)))

        assertTrue(failure is IllegalStateException)
    }

    @Test
    fun `delete returns the first exception when every runtime fails`() {
        val first = IllegalStateException("first")
        val second = IllegalArgumentException("second")

        assertSame(
            first,
            removalFailure(
                RemovalKind.DELETE,
                listOf(Result.failure(first), Result.failure(second)),
            ),
        )
    }

    @Test
    fun `archive succeeds when at least one runtime succeeds`() {
        assertNull(
            removalFailure(
                RemovalKind.ARCHIVE,
                listOf(Result.success(true), Result.failure(IllegalStateException("no"))),
            ),
        )
    }

    @Test
    fun `archive returns the first exception when every runtime fails`() {
        val first = IllegalStateException("first")
        val second = IllegalArgumentException("second")

        assertSame(
            first,
            removalFailure(
                RemovalKind.ARCHIVE,
                listOf(Result.failure(first), Result.failure(second)),
            ),
        )
    }

    @Test
    fun `empty results are not a failure`() {
        assertNull(removalFailure(RemovalKind.DELETE, emptyList()))
        assertNull(removalFailure(RemovalKind.ARCHIVE, emptyList()))
    }
}
