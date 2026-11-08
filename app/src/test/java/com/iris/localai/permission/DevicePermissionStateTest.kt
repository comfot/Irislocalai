package com.iris.localai.permission

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DevicePermissionStateTest {

    private val connected = DevicePermissionState(
        accessibilityEnabled = true,
        accessibilityConnected = true,
        overlayGranted = true,
        microphoneGranted = true,
        shizukuInstalled = true,
        shizukuBinderConnected = true,
        shizukuAuthorized = true,
    )

    @Test
    fun backgroundModeRequiresShizuku() {
        assertEquals(OperationChannel.BACKGROUND, connected.channel(backgroundMode = true, preferShizuku = false))
        assertEquals(
            OperationChannel.NONE,
            connected.copy(shizukuAuthorized = false).channel(backgroundMode = true, preferShizuku = true),
        )
    }

    @Test
    fun preferredShizukuFallsBackToAccessibilityWhenUnavailable() {
        assertEquals(OperationChannel.SHIZUKU, connected.channel(backgroundMode = false, preferShizuku = true))
        assertEquals(
            OperationChannel.ACCESSIBILITY,
            connected.copy(shizukuBinderConnected = false)
                .channel(backgroundMode = false, preferShizuku = true),
        )
        assertEquals(
            OperationChannel.ACCESSIBILITY,
            connected.channel(backgroundMode = false, preferShizuku = false),
        )
    }

    @Test
    fun noChannelWithoutPermissions() {
        val empty = DevicePermissionState()
        assertEquals(OperationChannel.NONE, empty.channel(backgroundMode = false, preferShizuku = false))
        assertFalse(empty.ready(backgroundMode = false, preferShizuku = false))
        assertTrue(connected.ready(backgroundMode = true, preferShizuku = false))
        assertTrue(connected.ready(backgroundMode = false, preferShizuku = false))
    }
}
