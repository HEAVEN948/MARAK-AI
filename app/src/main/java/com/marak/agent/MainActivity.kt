package com.marak.agent

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.widget.*

class MainActivity : Activity() {
    private fun toast(t: String) = Toast.makeText(this, t, Toast.LENGTH_SHORT).show()

    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        val p = getSharedPreferences("m", MODE_PRIVATE)
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(40, 80, 40, 40)
        fun btn(t: String, f: () -> Unit) {
            val b = Button(this)
            b.text = t
            b.setOnClickListener { f() }
            col.addView(b)
        }
        val title = TextView(this)
        title.text = "MARAK"
        title.textSize = 28f
        col.addView(title)
        val key = EditText(this)
        key.hint = "Gemini API key"
        key.setText(p.getString("key", ""))
        key.setSingleLine()
        col.addView(key)
        btn("1. Save key") {
            p.edit().putString("key", key.text.toString().trim()).apply()
            toast("Saved")
        }
        btn("2. Allow microphone") {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1)
        }
        btn("3. Open Accessibility settings") {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        val cmd = EditText(this)
        cmd.hint = "Or type a command here"
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
