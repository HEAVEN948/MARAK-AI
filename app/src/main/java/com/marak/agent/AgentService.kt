package com.marak.agent

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.view.Gravity
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.TextView
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class AgentService : AccessibilityService(), TextToSpeech.OnInitListener {
    companion object { var instance: AgentService? = null }

    class El(val i: Int, val text: String, val desc: String, val id: String,
             val click: Boolean, val edit: Boolean, val x: Int, val y: Int)

    private val MODEL = "gemini-flash-latest"

    private val main = Handler(Looper.getMainLooper())
    private var tts: TextToSpeech? = null
    private var btn: TextView? = null
    private var rec: SpeechRecognizer? = null
    @Volatile private var running = false

    private val SYS = """You are MARAK, an agent that controls an Android phone through accessibility. Achieve the user's goal step by step.
Each turn you get the current app, installed apps (label:package) and the visible screen elements (index|text|desc|id|click|edit).
Reply ONLY with one JSON object: {"thought":"short","action":"","index":0,"text":"","package":"","key":"","direction":"","risky":false,"message":""}
Actions: open_app(package) | tap(index) | type(text) | key(back|home|recents|enter) | swipe(direction up|down|left|right) | wait | ask_user(message) | done(message).
Rules: tap a search field before typing. After typing a search, use key enter. swipe up scrolls the page down. Set risky=true for anything that buys, pays, sends, posts, deletes or changes account settings. Never enter passwords or OTPs: use ask_user. If the screen list is empty the app may block reading: use done and tell the user. Use done when finished and summarize in message."""

    override fun onServiceConnected() {
        instance = this
        tts = TextToSpeech(this, this)
        addButton()
    }

    override fun onInit(status: Int) { tts?.language = Locale.getDefault() }
    override fun onAccessibilityEvent(e: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    override fun onDestroy() {
        instance = null
        try {
            btn?.let { (getSystemService(Context.WINDOW_SERVICE) as WindowManager).removeView(it) }
        } catch (e: Exception) {}
        super.onDestroy()
    }

    private fun addButton() {
        if (btn != null) return
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val b = TextView(this)
        b.text = "\uD83C\uDFA4"
        b.textSize = 24f
        b.gravity = Gravity.CENTER
        val bg = GradientDrawable()
        bg.shape = GradientDrawable.OVAL
        bg.setColor(Color.parseColor("#CC1A73E8"))
        b.background = bg
        b.setOnClickListener {
            if (running) { running = false; say("Stopped") } else listenThenRun()
        }
        val lp = WindowManager.LayoutParams(
            150, 150,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT)
        lp.gravity = Gravity.END or Gravity.CENTER_VERTICAL
        wm.addView(b, lp)
        btn = b
    }

    private fun paint() {
        main.post {
            (btn?.background as? GradientDrawable)
                ?.setColor(Color.parseColor(if (running) "#CCD93025" else "#CC1A73E8"))
        }
    }

    fun say(t: String) {
        main.post { Toast.makeText(this, t, Toast.LENGTH_LONG).show() }
        tts?.speak(t, TextToSpeech.QUEUE_FLUSH, null, "m")
    }

    private fun listenOnce(): String {
        Thread.sleep(500)
        var n = 0
        while (tts?.isSpeaking == true && n < 50) { Thread.sleep(200); n++ }
        val latch = CountDownLatch(1)
        var out = ""
        main.post {
            try {
                rec?.destroy()
                val r = SpeechRecognizer.createSpeechRecognizer(this)
                rec = r
                r.setRecognitionListener(object : RecognitionListener {
                    override fun onResults(b: Bundle?) {
                        out = b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                            ?.firstOrNull() ?: ""
                        latch.countDown()
                    }
                    override fun onError(e: Int) { latch.countDown() }
                    override fun onReadyForSpeech(p: Bundle?) {}
                    override fun onBeginningOfSpeech() {}
                    override fun onRmsChanged(v: Float) {}
                    override fun onBufferReceived(b: ByteArray?) {}
                    override fun onEndOfSpeech() {}
                    override fun onPartialResults(b: Bundle?) {}
                    override fun onEvent(t: Int, b: Bundle?) {}
                })
                val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                    RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                r.startListening(i)
            } catch (e: Exception) { latch.countDown() }
        }
        latch.await(20, TimeUnit.SECONDS)
        return out
    }

    private fun listenThenRun() {
        if (running) return
        running = true
        paint()
        say("Listening")
        Thread {
            val c = listenOnce()
            if (c.isBlank() || !running) {
                running = false
                paint()
                say("Didn't catch that")
            } else runAgent(c)
        }.start()
    }

    fun startTask(goal: String) {
        if (running || goal.isBlank()) return
        running = true
        paint()
        Thread { runAgent(goal) }.start()
    }

    private fun runAgent(goal: String) {
        val hist = mutableListOf<String>()
        try {
            for (step in 1..25) {
                if (!running) return
                val (pkg, els) = readScreen()
                val a = think(goal, pkg, els, hist)
                val k = a.optString("action")
                if (k == "done") { say(a.optString("message", "Done")); return }
                if (k == "ask_user") {
                    say(a.optString("message", "I need your input"))
                    hist.add("asked: " + a.optString("message") + " | user said: " + listenOnce())
                    continue
                }
                if (a.optBoolean("risky")) {
                    say("Confirm: " + a.optString("thought") + ". Say yes or no.")
                    if (!listenOnce().lowercase().contains("yes")) { say("Cancelled"); return }
                }
                act(a, els)
                hist.add(k + " " + a.optString("text") + a.optString("package") +
                    a.optString("index") + a.optString("key"))
            }
            say("Step limit reached")
        } catch (e: Exception) {
            say(if (e.message?.contains("429") == true)
                "Gemini limit reached. Wait a minute." else "Error: " + e.message)
        } finally {
            running = false
            paint()
        }
    }

    private fun appList(): String {
        val i = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return packageManager.queryIntentActivities(i, 0).joinToString(", ") {
            it.loadLabel(packageManager).toString() + ":" + it.activityInfo.packageName
        }
    }

    private fun readScreen(): Pair<String, List<El>> {
        val out = mutableListOf<El>()
        val root = rootInActiveWindow ?: return Pair("unknown", out)
        fun walk(n: AccessibilityNodeInfo?) {
            if (n == null || out.size >= 80) return
            val t = n.text?.toString() ?: ""
            val d = n.contentDescription?.toString() ?: ""
            if ((t.isNotEmpty() || d.isNotEmpty() || n.isClickable || n.isEditable) && n.isVisibleToUser) {
                val r = Rect()
                n.getBoundsInScreen(r)
                if (r.width() > 0 && r.height() > 0) {
                    out.add(El(out.size, t.take(60), d.take(60),
                        (n.viewIdResourceName ?: "").substringAfterLast('/'),
                        n.isClickable, n.isEditable, r.centerX(), r.centerY()))
                }
            }
            for (i in 0 until n.childCount) walk(n.getChild(i))
        }
        walk(root)
        return Pair(root.packageName?.toString() ?: "unknown", out)
    }

    private fun think(goal: String, pkg: String, els: List<El>, hist: List<String>): JSONObject {
        val key = getSharedPreferences("m", MODE_PRIVATE).getString("key", "") ?: ""
        val ui = els.joinToString("\n") {
            "${it.i}|${it.text}|${it.desc}|${it.id}|click=${it.click}|edit=${it.edit}"
        }
        val prompt = "GOAL: $goal\nCURRENT APP: $pkg\nAPPS: ${appList()}\nHISTORY: ${hist.takeLast(8)}\nSCREEN:\n$ui"
        val body = JSONObject()
            .put("system_instruction", JSONObject().put("parts",
                JSONArray().put(JSONObject().put("text", SYS))))
            .put("contents", JSONArray().put(JSONObject().put("role", "user").put("parts",
                JSONArray().put(JSONObject().put("text", prompt)))))
            .put("generationConfig", JSONObject()
                .put("responseMimeType", "application/json").put("temperature", 0.2))
        val c = URL("https://generativelanguage.googleapis.com/v1beta/models/" + MODEL + ":generateContent")
            .openConnection() as HttpURLConnection
        c.requestMethod = "POST"
        c.setRequestProperty("Content-Type", "application/json")
        c.setRequestProperty("x-goog-api-key", key)
        c.connectTimeout = 20000
        c.readTimeout = 60000
        c.doOutput = true
        c.outputStream.use { it.write(body.toString().toByteArray()) }
        val code = c.responseCode
        if (code !in 200..299) throw Exception("Gemini error $code")
        val txt = c.inputStream.bufferedReader().readText()
        val t = JSONObject(txt).getJSONArray("candidates").getJSONObject(0)
            .getJSONObject("content").getJSONArray("parts").getJSONObject(0).getString("text")
        return JSONObject(t)
    }

    private fun act(a: JSONObject, els: List<El>) {
        when (a.optString("action")) {
            "open_app" -> {
                val i = packageManager.getLaunchIntentForPackage(a.optString("package"))
                if (i != null) {
                    i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    startActivity(i)
                }
            }
            "tap" -> {
                val e = els.getOrNull(a.optInt("index", -1))
                if (e != null) tap(e.x, e.y)
            }
            "type" -> typeText(a.optString("text"))
            "key" -> when (a.optString("key")) {
                "home" -> performGlobalAction(GLOBAL_ACTION_HOME)
                "recents" -> performGlobalAction(GLOBAL_ACTION_RECENTS)
                "enter" -> pressEnter()
                else -> performGlobalAction(GLOBAL_ACTION_BACK)
            }
            "swipe" -> swipe(a.optString("direction"))
        }
        Thread.sleep(1500)
    }

    private fun gesture(p: Path, dur: Long) {
        val latch = CountDownLatch(1)
        val g = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(p, 0, dur)).build()
        dispatchGesture(g, object : GestureResultCallback() {
            override fun onCompleted(d: GestureDescription?) { latch.countDown() }
            override fun onCancelled(d: GestureDescription?) { latch.countDown() }
        }, null)
        latch.await(3, TimeUnit.SECONDS)
    }

    private fun tap(x: Int, y: Int) {
        val p = Path()
        p.moveTo(x.toFloat(), y.toFloat())
        gesture(p, 50)
    }

    private fun swipe(d: String) {
        val m = resources.displayMetrics
        val w = m.widthPixels.toFloat()
        val h = m.heightPixels.toFloat()
        val p = Path()
        when (d) {
            "down" -> { p.moveTo(w / 2, h * 0.3f); p.lineTo(w / 2, h * 0.75f) }
            "left" -> { p.moveTo(w * 0.8f, h / 2); p.lineTo(w * 0.2f, h / 2) }
            "right" -> { p.moveTo(w * 0.2f, h / 2); p.lineTo(w * 0.8f, h / 2) }
            else -> { p.moveTo(w / 2, h * 0.75f); p.lineTo(w / 2, h * 0.3f) }
        }
        gesture(p, 300)
    }

    private fun firstEditable(n: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (n == null) return null
        if (n.isEditable) return n
        for (i in 0 until n.childCount) {
            val r = firstEditable(n.getChild(i))
            if (r != null) return r
        }
        return null
    }

    private fun typeText(t: String) {
        val root = rootInActiveWindow ?: return
        val n = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: firstEditable(root) ?: return
        val b = Bundle()
        b.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, t)
        n.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, b)
    }

    private fun pressEnter() {
        val n = rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return
        if (Build.VERSION.SDK_INT >= 30) {
            n.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
        }
    }
}
