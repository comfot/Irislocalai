package com.iris.localai.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupFormatTest {

    @Test
    fun acceptsNestedEntryPaths() {
        assertEquals(
            listOf("characters", "xiaoman", "card.json"),
            BackupFormat.segments("characters/xiaoman/card.json"),
        )
        assertEquals(listOf("manifest.json"), BackupFormat.segments("manifest.json"))
    }

    @Test
    fun rejectsTraversalAndAbsolutePaths() {
        assertNull(BackupFormat.segments("../evil"))
        assertNull(BackupFormat.segments("characters/../../evil"))
        assertNull(BackupFormat.segments("/absolute"))
        assertNull(BackupFormat.segments("characters\\evil"))
        assertNull(BackupFormat.segments("characters//card.json"))
        assertNull(BackupFormat.segments("characters/./card.json"))
        assertNull(BackupFormat.segments("characters/\u0000/card.json"))
    }

    @Test
    fun validatesCharacterIds() {
        assertTrue(BackupFormat.isSafeId("小满-1"))
        assertTrue(BackupFormat.isSafeId("card_01"))
        assertFalse(BackupFormat.isSafeId(""))
        assertFalse(BackupFormat.isSafeId("../x"))
        assertFalse(BackupFormat.isSafeId("a/b"))
        assertFalse(BackupFormat.isSafeId("a".repeat(65)))
    }
}
