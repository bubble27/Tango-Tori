package com.tangotori.app.data.compound

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DeviceIdProvider @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    val deviceId: String by lazy {
        val prefs = context.getSharedPreferences("tango_tori", Context.MODE_PRIVATE)
        prefs.getString(KEY, null) ?: UUID.randomUUID().toString().also { id ->
            prefs.edit().putString(KEY, id).apply()
        }
    }

    private companion object {
        const val KEY = "device_id"
    }
}
