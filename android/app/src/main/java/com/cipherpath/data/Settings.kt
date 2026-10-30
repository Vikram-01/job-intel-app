package com.cipherpath.data

import android.content.Context
import com.cipherpath.security.SecretStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object Settings {
    val SUPPORTED_ATS = linkedSetOf("greenhouse", "lever", "ashby", "smartrecruiters")
}

data class AppSettings(
    val theme: String = "system",            // system | light | dark
    val syncMode: String = "direct",          // direct (on-device connectors) | backend
    val backendUrl: String = "",
    val syncIntervalHours: Int = 24,
    val syncWifiOnly: Boolean = false,
    val notifyNewJobs: Boolean = true,
    val notifyHighMatch: Boolean = true,
    val notifyDigest: Boolean = true,
    val notifyReminders: Boolean = true,
    val highMatchThreshold: Int = 70,
    val roleGroups: Set<String> = setOf("cyber", "fde"),
    val preferRemote: Boolean = false,
    val preferIndia: Boolean = false,
    val lastBackendSync: String? = null,
    val onboarded: Boolean = false,
)

/** Non-secret preferences in SharedPreferences; the backend API key lives in SecretStore. */
class SettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val secrets = SecretStore(context)
    private val _state = MutableStateFlow(load())
    val state: StateFlow<AppSettings> = _state.asStateFlow()

    private fun load() = AppSettings(
        theme = prefs.getString("theme", "system") ?: "system",
        syncMode = prefs.getString("syncMode", "direct") ?: "direct",
        backendUrl = prefs.getString("backendUrl", "") ?: "",
        syncIntervalHours = prefs.getInt("syncIntervalHours", 24),
        syncWifiOnly = prefs.getBoolean("syncWifiOnly", false),
        notifyNewJobs = prefs.getBoolean("notifyNewJobs", true),
        notifyHighMatch = prefs.getBoolean("notifyHighMatch", true),
        notifyDigest = prefs.getBoolean("notifyDigest", true),
        notifyReminders = prefs.getBoolean("notifyReminders", true),
        highMatchThreshold = prefs.getInt("highMatchThreshold", 70),
        roleGroups = prefs.getStringSet("roleGroups", setOf("cyber", "fde"))?.toSet() ?: setOf("cyber", "fde"),
        preferRemote = prefs.getBoolean("preferRemote", false),
        preferIndia = prefs.getBoolean("preferIndia", false),
        lastBackendSync = prefs.getString("lastBackendSync", null),
        onboarded = prefs.getBoolean("onboarded", false),
    )

    fun update(f: (AppSettings) -> AppSettings) {
        val s = f(_state.value)
        prefs.edit()
            .putString("theme", s.theme)
            .putString("syncMode", s.syncMode)
            .putString("backendUrl", s.backendUrl)
            .putInt("syncIntervalHours", s.syncIntervalHours.coerceIn(6, 168))
            .putBoolean("syncWifiOnly", s.syncWifiOnly)
            .putBoolean("notifyNewJobs", s.notifyNewJobs)
            .putBoolean("notifyHighMatch", s.notifyHighMatch)
            .putBoolean("notifyDigest", s.notifyDigest)
            .putBoolean("notifyReminders", s.notifyReminders)
            .putInt("highMatchThreshold", s.highMatchThreshold.coerceIn(40, 95))
            .putStringSet("roleGroups", s.roleGroups)
            .putBoolean("preferRemote", s.preferRemote)
            .putBoolean("preferIndia", s.preferIndia)
            .putString("lastBackendSync", s.lastBackendSync)
            .putBoolean("onboarded", s.onboarded)
            .apply()
        _state.value = s.copy(
            syncIntervalHours = s.syncIntervalHours.coerceIn(6, 168),
            highMatchThreshold = s.highMatchThreshold.coerceIn(40, 95),
        )
    }

    var apiKey: String?
        get() = secrets.get("backend_api_key")
        set(v) = secrets.put("backend_api_key", v?.trim()?.take(256))

    fun wipe() {
        prefs.edit().clear().apply()
        secrets.wipe()
        _state.value = AppSettings()
    }
}
