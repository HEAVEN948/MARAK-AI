package com.marak.agent

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import android.widget.*

class MainActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var memory: TextView
    private lateinit var history: TextView

    private fun toast(t: String) = Toast.makeText(this, t, Toast.LENGTH_SHORT).show()

    private fun refresh() {
        val p = getSharedPreferences("m", MODE_PRIVATE)
        val mic = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        val keyOk = !(p.getString("key", "") ?: "").isBlank()
        status.text = (if (AgentService.instance != null) "Marak is running" else "Marak is off. Turn it on in Accessibility.") +
            "\nMicrophone: " + (if (mic) "allowed" else "not allowed") +
            "\nGemini key: " + (if (keyOk) "saved" else "not saved")
        memory.text = (p.getString("facts", "") ?: "").ifBlank { "Nothing saved. Say: remember that..." }
        history.text = (p.getString("log", "") ?: "").split("\n").filter { it.isNotBlank() }
            .takeLast(30).reversed().joinToString("\n").ifBlank { "No history yet." }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        val p = getSharedPreferences("m", MODE_PRIVATE)
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(40, 70, 40, 40)

        fun label(t: String, size: Float): TextView {
            val v = TextView(this)
            v.text = t
            v.textSize = size
            v.setPadding(0, 24, 0, 8)
            col.addView(v)
            return v
        }
        fun btn(t: String, f: () -> Unit) {
            val b = Button(this)
            b.text = t
            b.setOnClickListener { f() }
            col.addView(b)
        }
        fun sw(t: String, k: String) {
            val v = Switch(this)
            v.text = t
            v.isChecked = p.getBoolean(k, false)
            v.setOnCheckedChangeListener { _, c -> p.edit().putBoolean(k, c).apply() }
            col.addView(v)
        }

        label("MARAK", 28f)
        status = label("", 15f)

        val key = EditText(this)
        key.hint = "Gemini API key"
        key.setText(p.getString("key", ""))
        key.setSingleLine()
        col.addView(key)
        btn("Save key") {
            p.edit().putString("key", key.text.toString().trim()).apply()
            toast("Saved")
            refresh()
        }
        btn("Allow microphone") {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1)
        }
        btn("Open Accessibility settings") {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        label("Settings", 18f)
        sw("Voice replies", "voice")
        sw("Only respond when I say Marak", "wake")

        label("Memory", 18f)
        memory = label("", 14f)
        btn("Clear memory") {
            p.edit().remove("facts").apply()
            refresh()
        }

        label("History", 18f)
        history = label("", 13f)
        btn("Clear history") {
            p.edit().remove("log").apply()
            refresh()
        }

        label("Type a command", 18f)
        val cmd = EditText(this)
        cmd.hint = "For example: open YouTube"
        col.addView(cmd)
        btn("Run command") {
            p.edit().putString("key", key.text.toString().trim()).apply()
            val svc = AgentService.instance
            if (svc == null) {
                toast("Turn on Marak in Accessibility first")
            } else {
                svc.startTask(cmd.text.toString())
                moveTaskToBack(true)
            }
        }

        val sv = ScrollView(this)
        sv.addView(col)
        setContentView(sv)
    }
}
