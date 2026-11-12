package com.callagent.gateway

import android.content.Context

/**
 * The application context, reachable from code that has none of its own.
 *
 * The SIP transport is created by the service and by the settings screen
 * alike, and it needs the app's private directory to find the certificate
 * authorities the user imported.  Handing it a context would mean threading
 * one through the transport's constructor and every caller of it; the
 * application context is a singleton anyway, so it is installed once here.
 */
object AppContext {

    @Volatile
    private var instance: Context? = null

    /** Called once, from [GatewayApp.onCreate]. */
    fun install(context: Context) {
        instance = context.applicationContext
    }

    fun get(): Context = instance
        ?: error("the application context is not installed yet — GatewayApp never started")

    /** Null when the application has not started; for callers that can do without. */
    fun getOrNull(): Context? = instance
}
