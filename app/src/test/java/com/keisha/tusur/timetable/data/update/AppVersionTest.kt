package com.keisha.tusur.timetable.data.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppVersionTest {

    @Test
    fun `parse drops leading v`() {
        assertEquals(listOf(1, 1, 2), AppVersion.parse("v1.1.2"))
        assertEquals(listOf(1, 1, 2), AppVersion.parse("V1.1.2"))
        assertEquals(listOf(1, 1, 2), AppVersion.parse("1.1.2"))
    }

    @Test
    fun `parse ignores build suffix`() {
        assertEquals(listOf(1, 1, 2), AppVersion.parse("1.1.2-debug"))
        assertEquals(listOf(1, 1, 2), AppVersion.parse("v1.1.2-beta.3"))
    }

    @Test
    fun `parse rejects garbage`() {
        assertNull(AppVersion.parse(""))
        assertNull(AppVersion.parse("v"))
        assertNull(AppVersion.parse("latest"))
        assertNull(AppVersion.parse("1.1.x"))
        assertNull(AppVersion.parse("1..2"))
    }

    @Test
    fun `higher patch is newer`() {
        assertTrue(AppVersion.isNewer("1.1.2", "1.1.3"))
        assertTrue(AppVersion.isNewer("1.1.2", "v1.1.3"))
    }

    @Test
    fun `equal versions are not newer`() {
        assertFalse(AppVersion.isNewer("1.1.2", "1.1.2"))
    }

    @Test
    fun `numeric components compare as numbers`() {
        assertTrue(AppVersion.isNewer("1.1.9", "1.1.10"))
        assertFalse(AppVersion.isNewer("1.1.10", "1.1.9"))
    }

    @Test
    fun `missing components count as zero`() {
        assertFalse(AppVersion.isNewer("1.2.0", "1.2"))
        assertFalse(AppVersion.isNewer("1.2", "1.2.0"))
        assertTrue(AppVersion.isNewer("1.2", "1.2.1"))
    }

    @Test
    fun `debug suffix does not affect comparison`() {
        assertTrue(AppVersion.isNewer("1.1.2-debug", "1.1.3"))
        assertFalse(AppVersion.isNewer("1.1.2-debug", "1.1.2"))
    }

    @Test
    fun `invalid versions are never newer`() {
        assertFalse(AppVersion.isNewer("1.1.2", "garbage"))
        assertFalse(AppVersion.isNewer("garbage", "1.1.3"))
    }
}
