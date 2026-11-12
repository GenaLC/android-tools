package com.callagent.gateway.service

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The API-level decisions, checked against a table of platform versions.
 *
 * These run on the JVM, where Build.VERSION.SDK_INT is always 0 — which is
 * exactly why the decisions take the API level as an argument.
 */
class CompatTest {

    @Test
    fun foregroundServiceIsStartedFromAndroid8On() {
        assertEquals(Compat.StartMethod.SERVICE, Compat.startMethod(23))
        assertEquals(Compat.StartMethod.SERVICE, Compat.startMethod(24))
        assertEquals(Compat.StartMethod.SERVICE, Compat.startMethod(25))
        assertEquals(Compat.StartMethod.FOREGROUND_SERVICE, Compat.startMethod(26))
        assertEquals(Compat.StartMethod.FOREGROUND_SERVICE, Compat.startMethod(29))
        assertEquals(Compat.StartMethod.FOREGROUND_SERVICE, Compat.startMethod(31))
        assertEquals(Compat.StartMethod.FOREGROUND_SERVICE, Compat.startMethod(35))
    }

    @Test
    fun notificationsUseAChannelFromAndroid8On() {
        assertEquals(Compat.NotificationStyle.LEGACY, Compat.notificationStyle(23))
        assertEquals(Compat.NotificationStyle.LEGACY, Compat.notificationStyle(24))
        assertEquals(Compat.NotificationStyle.LEGACY, Compat.notificationStyle(25))
        assertEquals(Compat.NotificationStyle.CHANNEL, Compat.notificationStyle(26))
        assertEquals(Compat.NotificationStyle.CHANNEL, Compat.notificationStyle(31))
        assertEquals(Compat.NotificationStyle.CHANNEL, Compat.notificationStyle(35))
    }

    /**
     * The two decisions move together, because they are the same platform
     * boundary: Android 8 is where channels and startForegroundService()
     * arrived.
     */
    @Test
    fun bothDecisionsTurnOverAtTheSamePlatformVersion() {
        for (sdk in 21..35) {
            assertEquals(
                "sdk $sdk",
                Compat.startMethod(sdk) == Compat.StartMethod.FOREGROUND_SERVICE,
                Compat.notificationStyle(sdk) == Compat.NotificationStyle.CHANNEL
            )
        }
    }
}
