package com.callagent.gateway.service

import android.os.Build

/**
 * The API-level decisions that differ between Android versions.
 *
 * The gateway runs on Android 7 (API 24) and up, so the code paths that
 * only exist on newer platforms have to be chosen at runtime instead of by
 * the compiler.  The decisions live here as pure functions of the API level
 * so they can be tested without a device: the unit tests run on the JVM,
 * where Build.VERSION.SDK_INT is always 0 and every call into the Android
 * framework throws.
 */
object Compat {

    /**
     * How the gateway service should be started.
     */
    enum class StartMethod {
        /** Context.startForegroundService() — Android 8 (API 26) and up. */
        FOREGROUND_SERVICE,

        /**
         * Context.startService() — below Android 8.
         *
         * The service still promotes itself to the foreground as soon as it
         * is up; only the way it is woken differs.
         */
        SERVICE
    }

    /**
     * How a notification should be built.
     */
    enum class NotificationStyle {
        /** A channel, and the channel-taking builder — Android 8 and up. */
        CHANNEL,

        /**
         * The legacy builder with a priority — below Android 8.
         *
         * There are no channels there: the notification carries its own
         * priority instead.
         */
        LEGACY
    }

    /**
     * The start method for this API level.
     *
     * startForegroundService() does not exist below Android 8, and calling
     * it there is a NoSuchMethodError — an Error, so the `catch (e: Exception)`
     * around the call does not catch it and the caller dies with it.
     */
    fun startMethod(sdkInt: Int): StartMethod =
        if (sdkInt >= Build.VERSION_CODES.O) StartMethod.FOREGROUND_SERVICE else StartMethod.SERVICE

    /**
     * The notification style for this API level.
     *
     * NotificationChannel and Notification.Builder(Context, String) are both
     * Android 8.  Below that a notification carries its own priority, and
     * there is no channel to create in the first place.
     */
    fun notificationStyle(sdkInt: Int): NotificationStyle =
        if (sdkInt >= Build.VERSION_CODES.O) NotificationStyle.CHANNEL else NotificationStyle.LEGACY
}
