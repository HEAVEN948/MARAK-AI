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
import android.media.AudioManager
import android.media.ToneGenerator
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
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

    private val MODELS = listOf("gemini-3.1-flash-lite", "gemini-flash-latest")

    private val main = Handler(Looper.getMainLooper())
    private var tts: TextToSpeech? = null
    private var btn: TextView? = null
    private var rec: SpeechRecognizer? = null
    private val tone by lazy { ToneGenerator(AudioManager.STREAM_MUSIC, 70) }
    @Volatile private var gen = 0
    @Volatile private var lastEvt = 0L
    @Volatile private var conn: HttpURLConnection? = null

    private fun alive(g: Int) = g == gen

    private val SYS = """You are MARAK, a fast, polite phone assistant. You operate an Android phone for the user the way a skilled person would. Work step by step toward the goal.
Each turn you see the current app, the installed apps (label:package) and the screen elements (index|text|desc|id|flags where c=clickable e=editable).
Reply ONLY with one JSON object: {"action":"","index":0,"text":"","package":"","key":"","direction":"","risky":false,"message":""}
Actions: open_app(package) | tap(index) | type(text) | key(back|home|recents|enter) | swipe(direction up|down|left|right) | wait | ask_user(message) | done(message).
Rules:
- Take the shortest path. Do not reopen an app that is already open. Prefer search boxes over scrolling.
- Tap a search field before typing, then use key enter.
- swipe up scrolls the page down.
- Do exactly what the user asked and nothing extra. If the request is unclear, use ask_user with one short question.
- Set risky=true for anything that buys, pays, sends, posts, deletes or changes settings, and put a short natural yes/no question in message, like: Should I send it to Rahul?
- Never enter passwords or OTPs: use ask_user.
- If the screen is empty or blocked, use done and say so.
- message is spoken aloud: one or two short, warm, natural sentences in first person, no technical words, no emojis, no lists. Example: Here are the cheapest car mirrors on Amazon.
- When finished, use done."""

    override fun onServiceConnected() {
        instance = this
        tts = TextToSpeech(this, this)
        addButton()
    }

    override fun onInit(status: Int) {
        tts?.language = Locale.getDefault()
        tts?.setSpeechRate(1.1f)
    }

    override fun onAccessibilityEvent(e: AccessibilityEvent?) { lastEvt = SystemClock.uptimeMillis() }
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
            val g = interrupt()
            listenThenRun(g)
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

    private fun paint(s: Int) {
        val c = when (s) {
            1 -> "#CC188038"
            2 -> "#CCE8710A"
            else -> "#CC1A73E8"
        }
        main.post { (btn?.background as? GradientDrawable)?.setColor(Color.parseColor(c)) }
    }

    private fun beep() {
        try { tone.startTone(ToneGenerator.TONE_PROP_BEEP, 90) } catch (e: Exception) {}
    }

    fun say(t: String) {
        main.post { Toast.makeText(this, t, Toast.LENGTH_SHORT).show() }
        tts?.speak(t, TextToSpeech.QUEUE_FLUSH, null, "m")
    }

    private fun interrupt(): Int {
        val g = ++gen
        tts?.stop()
        main.post { try { rec?.cancel() } catch (e: Exception) {} }
        val c = conn
        if (c != null) Thread { try { c.disconnect() } catch (e: Exception) {} }.start()
        return g
    }

    private fun listenOnce(g: Int, afterSpeech: Boolean): String {
        if (afterSpeech) {
            Thread.sleep(250)
            var n = 0
            while (tts?.isSpeaking == true && n < 60 && alive(g)) { Thread.sleep(100); n++ }
        }
        if (!alive(g)) return ""
        paint(1)
        beep()
        Thread.sleep(220)
        if (!alive(g)) return ""
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
                i.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 900L)
                i.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 900L)
                r.startListening(i)
            } catch (e: Exception) { latch.countDown() }
        }
        val end = SystemClock.uptimeMillis() + 15000
        while (alive(g) && SystemClock.uptimeMillis() < end) {
            if (latch.await(100, TimeUnit.MILLISECONDS)) break
        }
        if (!alive(g)) return ""
        return out
    }

    private fun listenThenRun(g: Int) {
        Thread {
            val c = listenOnce(g, false)
            if (!alive(g)) return@Thread
            if (c.isBlank()) {
                paint(0)
                say("Sorry, I didn't catch that.")
                return@Thread
            }
            runTask(c, g)
        }.start()
    }

    fun startTask(goal: String) {
        if (goal.isBlank()) return
        val g = interrupt()
        Thread { runTask(goal.trim(), g) }.start()
    }

    private fun runTask(goal: String, g: Int) {
        paint(2)
        try {
            if (quick(goal)) return
            agent(goal, g)
        } catch (e: Exception) {
            if (alive(g)) say(friendly(e))
        } finally {
            if (alive(g)) paint(0)
        }
    }

    private fun friendly(e: Exception): String {
        val m = e.message ?: ""
        return when {
            m.contains("429") -> "I've hit my limit for now. Give me a minute."
            m.contains("400") || m.contains("401") || m.contains("403") ->
                "Something is wrong with my key. Please check it in the app."
            e is java.net.UnknownHostException || e is java.net.SocketTimeoutException ->
                "I can't reach the internet right now."
            else -> "Sorry, I hit a snag. Could you try again?"
        }
    }

    private fun norm(s: String) = s.lowercase().filter { it.isLetterOrDigit() }

    private fun findApp(name: String): String? {
        val want = norm(name.lowercase().trim().removeSuffix(" app"))
        if (want.isEmpty()) return null
        val i = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = packageManager.queryIntentActivities(i, 0)
            .map { norm(it.loadLabel(packageManager).toString()) to it.activityInfo.packageName }
        return apps.firstOrNull { it.first == want }?.second
            ?: apps.firstOrNull { want.length >= 3 && it.first.startsWith(want) }?.second
    }

    private fun launch(pkg: String) {
        val i = packageManager.getLaunchIntentForPackage(pkg)
        if (i != null) {
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(i)
        }
    }

    private fun quick(goal: String): Boolean {
        val t = goal.trim().trimEnd('.', '!', '?')
        val l = t.lowercase()
        if (Regex("(stop|cancel|never mind|nevermind|forget it)").matches(l)) { say("Okay."); return true }
        if (Regex("(go )?back").matches(l)) { performGlobalAction(GLOBAL_ACTION_BACK); return true }
        if (Regex("(go )?home|go to (the )?home( screen)?").matches(l)) { performGlobalAction(GLOBAL_ACTION_HOME); return true }
        if (Regex("(show )?(recent apps|recents)").matches(l)) { performGlobalAction(GLOBAL_ACTION_RECENTS); return true }
        if (l == "scroll down") { swipe("up"); return true }
        if (l == "scroll up") { swipe("down"); return true }
        val multi = Regex("\\b(and|then)\\b")
        val o = Regex("(?:please )?(?:open|launch|start) (.+)", RegexOption.IGNORE_CASE).matchEntire(t)
        if (o != null && !multi.containsMatchIn(l)) {
            val name = o.groupValues[1].trim()
            val pkg = findApp(name)
            if (pkg != null) {
                say("Opening $name.")
                launch(pkg)
                return true
            }
        }
        val s = Regex("(?:search|google|find|look up|look for)(?: for)?\\s+(.+?)(?:\\s+on\\s+(amazon|flipkart|youtube|google))?",
            RegexOption.IGNORE_CASE).matchEntire(t)
        if (s != null && !Regex("\\b(and|then|on|in|from|sort|filter)\\b").containsMatchIn(s.groupValues[1].lowercase())) {
            val q = Uri.encode(s.groupValues[1].trim())
            val site = s.groupValues[2].lowercase()
            val url = when (site) {
                "amazon" -> "https://www.amazon.in/s?k=$q"
                "flipkart" -> "https://www.flipkart.com/search?q=$q"
                "youtube" -> "https://www.youtube.com/results?search_query=$q"
                else -> "https://www.google.com/search?q=$q"
            }
            say("Searching " + (if (site.isEmpty()) "Google" else site.replaceFirstChar { it.uppercase() }) + ".")
            val i = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(i)
            return true
        }
        return false
    }

    private fun agent(goal: String, g: Int) {
        val hist = mutableListOf<String>()
        val apps = appList()
        say("Okay.")
        for (step in 1..25) {
            if (!alive(g)) return
            val (pkg, els) = readScreen()
            val a = think(goal, pkg, els, hist, apps, g)
            if (!alive(g)) return
            val k = a.optString("action")
            if (k == "done") { say(a.optString("message", "All done.")); return }
            if (k == "ask_user") {
                say(a.optString("message", "Could you tell me a bit more?"))
                val r = listenOnce(g, true)
                if (!alive(g)) return
                if (r.isBlank()) { say("I didn't hear anything, so I'll stop here."); return }
                hist.add("asked the user, who said: $r")
                paint(2)
                continue
            }
            if (a.optBoolean("risky")) {
                say(a.optString("message", "Should I go ahead?"))
                val r = listenOnce(g, true)
                if (!alive(g)) return
                if (!Regex("\\b(yes|yeah|yep|sure|ok|okay|go ahead|do it|haan|ha)\\b")
                        .containsMatchIn(r.lowercase())) {
                    say("Okay, I won't.")
                    return
                }
                paint(2)
            }
            hist.add(act(a, els))
            if (hist.size >= 4 && hist.takeLast(4).distinct().size == 1) {
                say("I'm a bit stuck. Could you take it from here?")
                return
            }
        }
        say("That's taking longer than expected, so I'll stop here.")
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
            if (n == null || out.size >= 70) return
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

    private fun call(model: String, key: String, body: JSONObject): String {
        for (attempt in 0..1) {
            val b = JSONObject(body.toString())
            if (attempt == 0) {
                b.getJSONObject("generationConfig")
                    .put("thinkingConfig", JSONObject().put("thinkingBudget", 0))
            }
            val c = URL("https://generativelanguage.googleapis.com/v1beta/models/" + model + ":generateContent")
                .openConnection() as HttpURLConnection
            conn = c
            c.requestMethod = "POST"
            c.setRequestProperty("Content-Type", "application/json")
            c.setRequestProperty("x-goog-api-key", key)
            c.connectTimeout = 15000
            c.readTimeout = 30000
            c.doOutput = true
            c.outputStream.use { it.write(b.toString().toByteArray()) }
            val code = c.responseCode
            if (code in 200..299) return c.inputStream.bufferedReader().readText()
            if (code == 400 && attempt == 0) continue
            throw Exception("Gemini error $code")
        }
        throw Exception("Gemini error 400")
    }

    private fun think(goal: String, pkg: String, els: List<El>, hist: List<String>,
                      apps: String, g: Int): JSONObject {
        val key = getSharedPreferences("m", MODE_PRIVATE).getString("key", "") ?: ""
        val ui = els.joinToString("\n") {
            "${it.i}|${it.text}|${it.desc}|${it.id.take(24)}|${if (it.click) "c" else ""}${if (it.edit) "e" else ""}"
        }
        val prompt = "GOAL: $goal\nCURRENT APP: $pkg\nAPPS: $apps\nDONE SO FAR: ${hist.takeLast(8)}\nSCREEN:\n$ui"
        val body = JSONObject()
            .put("system_instruction", JSONObject().put("parts",
                JSONArray().put(JSONObject().put("text", SYS))))
            .put("contents", JSONArray().put(JSONObject().put("role", "user").put("parts",
                JSONArray().put(JSONObject().put("text", prompt)))))
            .put("generationConfig", JSONObject()
                .put("responseMimeType", "application/json").put("temperature", 0.2))
        var txt: String? = null
        var last: Exception? = null
        loop@ for (m in MODELS) {
            for (t in 1..2) {
                try {
                    txt = call(m, key, body)
                    break@loop
                } catch (e: Exception) {
                    if (!alive(g)) throw e
                    last = e
                    val msg = e.message ?: ""
                    if (msg.contains("503") || msg.contains("500")) Thread.sleep(500) else break
                }
            }
        }
        val raw = txt ?: throw (last ?: Exception("Gemini failed"))
        val t = JSONObject(raw).getJSONArray("candidates").getJSONObject(0)
            .getJSONObject("content").getJSONArray("parts").getJSONObject(0).getString("text")
        return JSONObject(t)
    }

    private fun settle(maxMs: Long) {
        val start = SystemClock.uptimeMillis()
        Thread.sleep(120)
        while (SystemClock.uptimeMillis() - start < maxMs) {
            if (SystemClock.uptimeMillis() - lastEvt >= 280) return
            Thread.sleep(40)
        }
    }

    private fun act(a: JSONObject, els: List<El>): String {
        return when (a.optString("action")) {
            "open_app" -> {
                launch(a.optString("package"))
                settle(2800)
                "opened " + a.optString("package")
            }
            "tap" -> {
                val e = els.getOrNull(a.optInt("index", -1))
                if (e != null) {
                    tap(e.x, e.y)
                    settle(1600)
                    "tapped " + e.text.ifEmpty { e.desc }.ifEmpty { e.id }
                } else "tap failed: bad index"
            }
            "type" -> {
                typeText(a.optString("text"))
                settle(600)
                "typed " + a.optString("text")
            }
            "key" -> {
                when (a.optString("key")) {
                    "home" -> performGlobalAction(GLOBAL_ACTION_HOME)
                    "recents" -> performGlobalAction(GLOBAL_ACTION_RECENTS)
                    "enter" -> pressEnter()
                    else -> performGlobalAction(GLOBAL_ACTION_BACK)
                }
                settle(1600)
                "pressed " + a.optString("key")
            }
            "swipe" -> {
                swipe(a.optString("direction"))
                settle(1200)
                "swiped " + a.optString("direction")
            }
            else -> {
                Thread.sleep(500)
                "waited"
            }
        }
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
        p.moveTo((x + (-3..3).random()).toFloat(), (y + (-3..3).random()).toFloat())
        gesture(p, (45..85).random().toLong())
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
        gesture(p, (240..340).random().toLong())
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
