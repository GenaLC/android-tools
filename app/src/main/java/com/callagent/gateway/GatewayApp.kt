package com.callagent.gateway

import android.app.Application
import com.callagent.gateway.BuildConfig
import android.os.StrictMode
import android.util.Log

class GatewayApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // The SIP transport needs the app's private directory to find the
        // certificate authorities the user imported, and it is created in
        // places that have no context of their own.
        AppContext.install(this)
        // Allow network on main thread — this is a headless gateway service,
        // not a UI app.  GSM callbacks arrive on the main thread and need to
        // trigger short UDP sends (SIP INVITE / BYE).
        StrictMode.setThreadPolicy(
            StrictMode.ThreadPolicy.Builder().permitAll().build()
        )
        Log.i("GatewayApp", "SIP-GSM Gateway v${BuildConfig.VERSION_NAME} started")
    }
}
