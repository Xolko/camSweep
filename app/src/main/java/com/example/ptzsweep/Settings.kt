package com.example.ptzsweep

import android.content.Context

data class CamSettings(
    val host: String = "192.168.1.100",
    val port: Int = 80,
    val user: String = "admin",
    val pass: String = "",
    val speed: Float = 0.4f,        // 0.1 .. 1.0
    val legSeconds: Float = 10f,    // seconds to pan one direction
    val pauseSeconds: Float = 0.3f, // pause at each end
)

class SettingsStore(context: Context) {
    private val p = context.getSharedPreferences("ptz_settings", Context.MODE_PRIVATE)
    private val d = CamSettings()

    fun load() = CamSettings(
        host = p.getString("host", d.host) ?: d.host,
        port = p.getInt("port", d.port),
        user = p.getString("user", d.user) ?: d.user,
        pass = p.getString("pass", d.pass) ?: d.pass,
        speed = p.getFloat("speed", d.speed),
        legSeconds = p.getFloat("leg", d.legSeconds),
        pauseSeconds = p.getFloat("pause", d.pauseSeconds),
    )

    fun save(s: CamSettings) = p.edit()
        .putString("host", s.host).putInt("port", s.port)
        .putString("user", s.user).putString("pass", s.pass)
        .putFloat("speed", s.speed).putFloat("leg", s.legSeconds)
        .putFloat("pause", s.pauseSeconds).apply()
}
