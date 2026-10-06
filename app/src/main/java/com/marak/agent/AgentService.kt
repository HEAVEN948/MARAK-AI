package com.marak.agent

import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.AlarmClock
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.util.Base64
import android.view.Display
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class AgentService : AccessibilityService(), TextToSpeech.OnInitListener {
    companion object { var instance: AgentService? = null }

    class El(val i: Int, val text: String, val desc: String, val id: String,
             val click: Boolean, val edit: Boolean, val x: Int, val y: Int)

    private val MODELS = listOf("gemini-3.1-flash-lite", "gemini-flash-latest")

    private val STARTERS = setOf(
        "open", "launch", "start", "search", "find", "look", "google", "play", "watch", "send",
        "write", "compose", "message", "text", "email", "mail", "call", "go", "show", "turn",
        "set", "tap", "click", "press", "type", "scroll", "swipe", "like", "subscribe", "share",
        "download", "close", "read", "reply", "forward", "skip", "pause", "resume", "stop",
        "wait", "hold", "volume", "mute", "unmute", "louder", "quieter", "increase", "decrease",
        "raise", "lower", "take", "lock", "next", "previous", "rewind", "fast", "keep", "auto",
        "cancel", "never", "forget", "enough", "back", "home", "recent", "recents", "notification",
        "notifications", "voice", "talk", "speak", "be", "sleep", "mic", "unpause", "continue",
        "add", "create", "delete", "remove", "switch", "enable", "disable", "post", "follow",
        "unfollow", "save", "select", "check", "book", "order", "buy", "make", "navigate",
        "record", "upload", "fill", "enter", "scan", "pay", "no", "what", "what's", "whats",
        "who", "who's", "when", "where", "why", "how", "which", "tell", "explain", "define",
        "weather", "news", "remember", "remind", "directions", "route", "alarm", "timer",
        "summarize", "summarise", "drive", "wifi", "bluetooth", "normal", "double", "faster")

    private val SENSITIVE = Regex(
        "\\b(send|pay|pay now|buy now|place order|order now|confirm order|transfer|delete|remove|" +
            "post|publish|call|checkout|purchase|donate|subscribe|submit|uninstall)\\b")

    private val VIDEO_APPS = listOf("youtube", "instagram", "facebook", "netflix", "hotstar",
        "mxtech", "vlc", "snapchat", "tiktok", "primevideo", "sonyliv", "zee5")

    private val main = Handler(Looper.getMainLooper())
    private var tts: TextToSpeech? = null
    private var btn: TextView? = null
    private var bar: TextView? = null
    private var rec: SpeechRecognizer? = null

    @Volatile private var gen = 0
    @Volatile private var lastEvt = 0L
    @Volatile private var conn: HttpURLConnection? = null
    @Volatile private var paused = false
    @Volatile private var waiting = false
    @Volatile private var answer = ""
    @Volatile private var answerLatch: CountDownLatch? = null
    @Volatile private var speakUntil = 0L
    @Volatile private var convUntil = 0L
    private var pending = ""
    private var onDevice = true
    private var fg = false

    private fun alive(g: Int) = g == gen

    private val SYS = """You are MARAK, a fast, polite phone assistant. You operate an Android phone for the user the way a skilled person would. Work step by step toward the goal.
Each turn you see the goal, MEMORY (facts about the user), the current app, the installed apps (label:package) and the screen elements (index|text|desc|id|flags where c=clickable e=editable). Sometimes you also get a screenshot.
Reply ONLY with one JSON object: {"action":"","index":0,"text":"","package":"","key":"","direction":"","risky":false,"message":""}
Actions: open_app(package) | tap(index) | type(text) | key(back|home|recents|enter) | swipe(direction up|down|left|right) | wait | ask_user(message) | done(message).
Rules:
- Take the shortest path. Do not reopen an app that is already open. Prefer search boxes over scrolling.
- Tap a search field before typing, then use key enter.
- swipe up scrolls the page down.
- Use MEMORY when relevant, for example names and relationships.
- Check the new screen after each action before deciding the next one. Only finish when the goal is truly done.
- To play a video on YouTube: search, then tap the first real video result, not an ad and not a Short.
- To change YouTube playback speed: open the player settings (gear or three dots), then Playback speed.
- Do exactly what the user asked and nothing extra. If the request is unclear, use ask_user with one short question.
- Set risky=true for anything that buys, pays, sends, posts, calls, deletes or changes settings, and put a short natural yes/no question in message, like: Should I send it to Rahul?
- Never enter passwords or OTPs: use ask_user.
- If the screen is empty or blocked, use done and say so.
- message is shown to the user: one or two short, warm, natural sentences in first person, no technical words, no emojis, no lists.
- When finished, use done."""

    private val ASK_SYS = """You are Marak, a friendly phone assistant. Answer in one to three short, natural, spoken sentences. No markdown, no lists, no emojis. If a screenshot is given, answer about what is on the screen. Be accurate and say so if you are unsure."""

    // ---------- storage helpers ----------

    private fun getP() = getSharedPreferences("m", MODE_PRIVATE)

    private fun log(s: String) {
        val line = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date()) + "  " + s
        val all = (getP().getString("log", "") ?: "").split("\n").filter { it.isNotBlank() }.toMutableList()
        all.add(line)
        getP().edit().putString("log", all.takeLast(60).joinToString("\n")).apply()
    }

    private fun facts(): List<String> =
        (getP().getString("facts", "") ?: "").split("\n").filter { it.isNotBlank() }

    private fun setFacts(l: List<String>) =
        getP().edit().putString("facts", l.takeLast(100).joinToString("\n")).apply()

    private fun voiceOn() = getP().getBoolean("voice", false)
    private fun setVoice(v: Boolean) = getP().edit().putBoolean("voice", v).apply()

    // ---------- lifecycle ----------

    override fun onServiceConnected() {
        instance = this
        tts = TextToSpeech(this, this)
        addButton()
        addBar()
        goForeground()
        paint(1)
        restart(300)
    }

    override fun onInit(status: Int) {
        val t = tts ?: return
        t.language = Locale.forLanguageTag("en-IN")
        try {
            val best = t.voices?.filter { it.locale.language == "en" }
                ?.maxByOrNull {
                    it.quality + (if (it.locale.country == "IN") 100 else 0) -
                        (if (it.features?.contains("notInstalled") == true) 1000 else 0)
                }
            if (best != null) t.voice = best
        } catch (e: Exception) {}
        t.setSpeechRate(1.05f)
    }

    override fun onAccessibilityEvent(e: AccessibilityEvent?) { lastEvt = SystemClock.uptimeMillis() }
    override fun onInterrupt() {}

    override fun onDestroy() {
        instance = null
        main.removeCallbacksAndMessages(null)
        try { rec?.destroy() } catch (e: Exception) {}
        try {
            val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
            btn?.let { wm.removeView(it) }
            bar?.let { wm.removeView(it) }
        } catch (e: Exception) {}
        super.onDestroy()
    }

    private fun goForeground() {
        if (fg) return
        try {
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(
                NotificationChannel("marak", "Marak", NotificationManager.IMPORTANCE_LOW))
            val n = Notification.Builder(this, "marak")
                .setContentTitle("Marak is listening")
                .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .build()
            if (Build.VERSION.SDK_INT >= 30) {
                startForeground(7, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            } else {
                startForeground(7, n)
            }
            fg = true
        } catch (e: Exception) {}
    }

    // ---------- overlay ----------

    private fun addButton() {
        if (btn != null) return
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val b = TextView(this)
        b.text = "\uD83C\uDFA4"
        b.textSize = 24f
        b.gravity = Gravity.CENTER
        val bg = GradientDrawable()
        bg.shape = GradientDrawable.OVAL
        bg.setColor(Color.parseColor("#CC188038"))
        b.background = bg
        b.setOnClickListener { togglePause() }
        val lp = WindowManager.LayoutParams(
            150, 150,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT)
        lp.gravity = Gravity.END or Gravity.CENTER_VERTICAL
        wm.addView(b, lp)
        btn = b
    }

    private fun addBar() {
        if (bar != null) return
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val t = TextView(this)
        t.setTextColor(Color.WHITE)
        t.textSize = 14f
        t.setPadding(40, 22, 40, 22)
        t.maxWidth = (resources.displayMetrics.widthPixels * 0.9f).toInt()
        val bg = GradientDrawable()
        bg.cornerRadius = 48f
        bg.setColor(Color.parseColor("#E6202124"))
        t.background = bg
        t.visibility = View.GONE
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT)
        lp.gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
        lp.y = 260
        wm.addView(t, lp)
        bar = t
    }

    private val hideBar = Runnable { bar?.visibility = View.GONE }

    private fun card(t: String, ms: Long = 5000) {
        main.post {
            val b = bar ?: return@post
            b.text = t
            b.visibility = View.VISIBLE
            main.removeCallbacks(hideBar)
            main.postDelayed(hideBar, ms)
        }
    }

    private fun paint(s: Int) {
        val c = when (s) {
            2 -> "#CCE8710A"
            3 -> "#CC5F6368"
            else -> "#CC188038"
        }
        main.post { (btn?.background as? GradientDrawable)?.setColor(Color.parseColor(c)) }
    }

    private fun tick(n: Int = 1) {
        try {
            val v = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            if (n == 1) {
                v.vibrate(VibrationEffect.createOneShot(18, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                v.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 25, 60, 25), -1))
            }
        } catch (e: Exception) {}
    }

    private fun speak(t: String) {
        if (!voiceOn()) return
        speakUntil = SystemClock.uptimeMillis() + t.length * 70L + 700L
        tts?.speak(t, TextToSpeech.QUEUE_FLUSH, null, "m")
    }

    private fun reply(t: String) {
        card(t, 9000)
        speak(t)
        log("Marak: " + t.take(80))
    }

    private fun togglePause() {
        if (paused) {
            paused = false
            paint(1)
            tick()
            restart(0)
        } else {
            paused = true
            main.removeCallbacks(restartRun)
            main.removeCallbacks(partialRun)
            try { rec?.destroy() } catch (e: Exception) {}
            rec = null
            paint(3)
            tick(2)
        }
    }

    // ---------- continuous listening ----------

    private val restartRun = Runnable { startRec() }

    private val partialRun = Runnable {
        val t = pending
        if (t.isNotBlank()) {
            pending = ""
            consume(t)
            restart(0)
        }
    }

    private fun restart(ms: Long) {
        main.removeCallbacks(restartRun)
        main.postDelayed(restartRun, ms)
    }

    private fun micOk() =
        checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun newRecognizer(): SpeechRecognizer {
        if (onDevice && Build.VERSION.SDK_INT >= 31 &&
            SpeechRecognizer.isOnDeviceRecognitionAvailable(this)) {
            return SpeechRecognizer.createOnDeviceSpeechRecognizer(this)
        }
        return SpeechRecognizer.createSpeechRecognizer(this)
    }

    private fun startRec() {
        if (paused) return
        if (!micOk()) {
            card("Please allow the microphone in the Marak app.", 4000)
            restart(4000)
            return
        }
        goForeground()
        try {
            rec?.destroy()
            val r = newRecognizer()
            rec = r
            r.setRecognitionListener(object : RecognitionListener {
                override fun onResults(b: Bundle?) {
                    main.removeCallbacks(partialRun)
                    pending = ""
                    val t = b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull() ?: ""
                    if (t.isNotBlank()) consume(t)
                    restart(0)
                }
                override fun onPartialResults(b: Bundle?) {
                    val t = b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull() ?: return
                    if (t.isBlank()) return
                    pending = t
                    val s = clean(t).first
                    val quick = !waiting && s.split(" ").size <= 4 && match(s) != null
                    main.removeCallbacks(partialRun)
                    main.postDelayed(partialRun, if (waiting) 700L else if (quick) 320L else 800L)
                }
                override fun onError(error: Int) {
                    main.removeCallbacks(partialRun)
                    val t = pending
                    pending = ""
                    if (t.isNotBlank()) consume(t)
                    when (error) {
                        12, 13 -> { onDevice = false; restart(200) }
                        9 -> restart(4000)
                        8 -> restart(1000)
                        else -> restart(150)
                    }
                }
                override fun onReadyForSpeech(p: Bundle?) {}
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(v: Float) {}
                override fun onBufferReceived(b: ByteArray?) {}
                override fun onEndOfSpeech() {}
                override fun onEvent(t: Int, b: Bundle?) {}
            })
            val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            i.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-IN")
            i.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            i.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            i.putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, packageName)
            i.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 600L)
            i.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 600L)
            r.startListening(i)
        } catch (e: Exception) {
            restart(1500)
        }
    }

    // ---------- what was heard ----------

    private fun clean(raw: String): Pair<String, Boolean> {
        var s = raw.lowercase().trim()
        var addressed = false
        val m = Regex("^(hey |ok |okay |hi )?(marak|marrak|merak|maric|mirac)[,.!]?\\s*").find(s)
        if (m != null) {
            s = s.substring(m.value.length)
            addressed = true
        }
        s = s.replace(Regex("^(please|can you|could you|will you)\\s+"), "")
        s = s.replace(Regex("\\s+(please|now|for me)$"), "")
        s = s.trim().trimEnd('.', '!', '?', ',')
        return Pair(s, addressed)
    }

    private fun consume(raw: String) {
        if (SystemClock.uptimeMillis() < speakUntil || tts?.isSpeaking == true) return
        if (waiting) {
            answer = raw
            answerLatch?.countDown()
            return
        }
        val (s, addressed) = clean(raw)
        handle(s, addressed)
    }

    private fun handle(s: String, addressed0: Boolean) {
        if (s.isBlank()) return
        val now = SystemClock.uptimeMillis()
        val addressed = addressed0 || now < convUntil
        if (addressed0) convUntil = now + 8000
        if (getP().getBoolean("wake", false) && !addressed) return
        val words = s.split(" ").size
        val first = s.substringBefore(" ")
        if (!addressed && (words > 14 || first !in STARTERS)) return
        val cmd = match(s)
        if (cmd == null) {
            val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            if (!addressed && am.isMusicActive) return
        }
        tick()
        log(s)
        val g = interrupt()
        if (cmd != null) {
            Thread { runTask { cmd(g) } }.start()
        } else {
            Thread { runTask { agent(s, g) } }.start()
        }
    }

    fun startTask(goal: String) {
        val (s, _) = clean(goal)
        main.post { handle(s, true) }
    }

    private fun interrupt(): Int {
        val g = ++gen
        tts?.stop()
        speakUntil = 0L
        val c = conn
        if (c != null) Thread { try { c.disconnect() } catch (e: Exception) {} }.start()
        return g
    }

    private fun runTask(body: () -> Unit) {
        paint(2)
        val g = gen
        try {
            body()
        } catch (e: Exception) {
            if (alive(g)) {
                tick(2)
                card(friendly(e), 5000)
            }
        } finally {
            if (alive(g)) paint(if (paused) 3 else 1)
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

    private fun waitAnswer(g: Int, ms: Long): String {
        val l = CountDownLatch(1)
        answer = ""
        answerLatch = l
        waiting = true
        val end = SystemClock.uptimeMillis() + ms
        while (alive(g) && SystemClock.uptimeMillis() < end) {
            if (l.await(100, TimeUnit.MILLISECONDS)) break
        }
        waiting = false
        return if (alive(g)) answer else ""
    }

    private fun ask(q: String, g: Int): String {
        waiting = true
        card(q, 20000)
        tick(2)
        speak(q)
        val a = waitAnswer(g, 20000)
        main.post(hideBar)
        return a
    }

    // ---------- instant commands (no AI) ----------

    private fun curPkg(): String = rootInActiveWindow?.packageName?.toString() ?: ""
    private fun isVideoApp(p: String) = VIDEO_APPS.any { p.contains(it) }

    private fun siteFor(p: String) = when {
        p.contains("youtube") -> "youtube"
        p.contains("amazon") -> "amazon"
        p.contains("flipkart") -> "flipkart"
        else -> ""
    }

    private fun tryStart(i: Intent): Boolean {
        return try {
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(i)
            true
        } catch (e: Exception) {
            false
        }
    }

    private fun timer(secs: Int, msg: String) {
        val i = Intent(AlarmClock.ACTION_SET_TIMER)
        i.putExtra(AlarmClock.EXTRA_LENGTH, secs.coerceIn(1, 86400))
        i.putExtra(AlarmClock.EXTRA_MESSAGE, msg)
        i.putExtra(AlarmClock.EXTRA_SKIP_UI, true)
        tryStart(i)
    }

    private fun match(l: String): ((Int) -> Unit)? {
        val l2 = l.replace(".", "")

        if (Regex("cancel|never mind|nevermind|forget it|enough|stop scrolling|stop scroll").matches(l))
            return { _ -> }

        if (Regex("(stop|pause) listening|go to sleep|sleep|mic off").matches(l))
            return { _ -> main.post { if (!paused) togglePause() } }

        if (Regex("voice on|talk to me|speak to me|reply with voice").matches(l))
            return { _ -> setVoice(true); reply("Voice replies are on.") }

        if (Regex("voice off|be quiet|stop talking|no voice|silent mode").matches(l))
            return { _ -> setVoice(false); card("Voice replies are off.", 2000) }

        // memory
        val rem = Regex("remember (?:that )?(.+)").matchEntire(l)
        if (rem != null && !l.startsWith("remember to")) return { _ ->
            setFacts(facts() + rem.groupValues[1])
            card("Remembered.", 2000)
        }
        if (Regex("what do you (remember|know)( about me)?|show (my )?memory").matches(l))
            return { _ ->
                val f = facts()
                reply(if (f.isEmpty()) "I don't have anything saved yet." else f.joinToString(". "))
            }
        val fg2 = Regex("forget (?:that )?(.+)").matchEntire(l)
        if (fg2 != null) return { _ ->
            val x = fg2.groupValues[1]
            if (x == "everything" || x == "all") setFacts(emptyList())
            else setFacts(facts().filter { !it.lowercase().contains(x) })
            card("Done.", 2000)
        }

        // time and date
        if (Regex("(what('s| is) the )?time( now)?|what time is it").matches(l))
            return { _ -> reply("It's " + SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date())) }
        if (Regex("(what('s| is) )?(today's |the )?date( today)?|what day is it").matches(l))
            return { _ -> reply(SimpleDateFormat("EEEE, d MMMM", Locale.getDefault()).format(Date())) }

        // skills
        val nv = Regex("(?:navigate to|navigation to|directions to|route to|take me to|drive to) (.+)").matchEntire(l)
        if (nv != null) return { _ ->
            val d = Uri.encode(nv.groupValues[1])
            val i = Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=$d"))
            i.setPackage("com.google.android.apps.maps")
            if (!tryStart(i)) viewUrl("https://www.google.com/maps/dir/?api=1&destination=$d", emptyList())
        }

        val al = Regex("(?:set )?(?:an? )?alarm (?:for|at) (\\d{1,2})(?:[: ](\\d{2}))? ?(am|pm)?").matchEntire(l2)
        if (al != null) return { _ ->
            var h = al.groupValues[1].toInt()
            val mi = al.groupValues[2].ifEmpty { "0" }.toInt()
            val ap = al.groupValues[3]
            if (ap == "pm" && h < 12) h += 12
            if (ap == "am" && h == 12) h = 0
            val i = Intent(AlarmClock.ACTION_SET_ALARM)
            i.putExtra(AlarmClock.EXTRA_HOUR, h.coerceIn(0, 23))
            i.putExtra(AlarmClock.EXTRA_MINUTES, mi.coerceIn(0, 59))
            i.putExtra(AlarmClock.EXTRA_MESSAGE, "Marak")
            i.putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            tryStart(i)
            card("Alarm set for " + h.coerceIn(0, 23) + ":" + mi.coerceIn(0, 59).toString().padStart(2, '0'), 3000)
        }

        val tm = Regex("(?:set )?(?:a |an )?timer (?:for )?(\\d+) (second|minute|hour)s?").matchEntire(l)
        if (tm != null) return { _ ->
            val n = tm.groupValues[1].toInt()
            val secs = n * (if (tm.groupValues[2] == "hour") 3600 else if (tm.groupValues[2] == "minute") 60 else 1)
            timer(secs, "Marak")
            card("Timer set.", 2000)
        }

        val rm = Regex("remind me (?:in|after) (\\d+) (minute|hour)s? to (.+)").matchEntire(l)
        if (rm != null) return { _ ->
            val n = rm.groupValues[1].toInt()
            timer(n * (if (rm.groupValues[2] == "hour") 3600 else 60), rm.groupValues[3])
            card("I'll remind you.", 2000)
        }
        val rm2 = Regex("remind me to (.+) (?:in|after) (\\d+) (minute|hour)s?").matchEntire(l)
        if (rm2 != null) return { _ ->
            val n = rm2.groupValues[2].toInt()
            timer(n * (if (rm2.groupValues[3] == "hour") 3600 else 60), rm2.groupValues[1])
            card("I'll remind you.", 2000)
        }

        val st = Regex("(?:open )?(wi-?fi|bluetooth|battery|display|sound|location|airplane( mode)?|mobile data|accessibility) settings").matchEntire(l)
        if (st != null) return { _ ->
            val a = when (st.groupValues[1].replace("-", "")) {
                "wifi" -> Settings.ACTION_WIFI_SETTINGS
                "bluetooth" -> Settings.ACTION_BLUETOOTH_SETTINGS
                "battery" -> Settings.ACTION_BATTERY_SAVER_SETTINGS
                "display" -> Settings.ACTION_DISPLAY_SETTINGS
                "sound" -> Settings.ACTION_SOUND_SETTINGS
                "location" -> Settings.ACTION_LOCATION_SOURCE_SETTINGS
                "mobile data" -> Settings.ACTION_WIRELESS_SETTINGS
                "accessibility" -> Settings.ACTION_ACCESSIBILITY_SETTINGS
                else -> Settings.ACTION_AIRPLANE_MODE_SETTINGS
            }
            tryStart(Intent(a))
        }

        val cn = Regex("call (\\+?\\d[\\d ]{5,})").matchEntire(l)
        if (cn != null) return { _ ->
            tryStart(Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + cn.groupValues[1].replace(" ", ""))))
        }

        // media
        if (Regex("(stop|pause)( (the )?(video|music|song|playing|playback|it|this|that))?|hold on|wait").matches(l))
            return { _ -> pauseMedia() }

        if (Regex("(play|resume|continue|unpause|start)( (the )?(video|music|song|playing|playback|it|this|that|again))?").matches(l))
            return { _ -> playMedia() }

        if (Regex("(play |go )?(at )?(fast|faster|2x|double speed|speed up|fast forward)( (the )?(video|speed))?").matches(l))
            return { g -> agent("In the video player that is open, open the player settings (gear or three dots), choose Playback speed and select 2x. Then finish.", g) }
        if (Regex("(play |go )?(at )?(normal|regular|1x)( speed)?|back to normal|stop fast forward(ing)?|normal speed").matches(l))
            return { g -> agent("In the video player that is open, open the player settings (gear or three dots), choose Playback speed and select Normal (1x). Then finish.", g) }

        if (Regex("(go )?back").matches(l)) return { _ -> performGlobalAction(GLOBAL_ACTION_BACK) }
        if (Regex("(go )?home|go to (the )?home( screen)?").matches(l))
            return { _ -> performGlobalAction(GLOBAL_ACTION_HOME) }
        if (Regex("(show )?(recent apps|recents|app switcher)").matches(l))
            return { _ -> performGlobalAction(GLOBAL_ACTION_RECENTS) }
        if (Regex("(open |show |pull down )?notifications?( panel| bar)?").matches(l))
            return { _ -> performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS) }
        if (Regex("take (a )?screenshot").matches(l))
            return { _ -> performGlobalAction(GLOBAL_ACTION_TAKE_SCREENSHOT) }

        if (Regex("volume up|louder|increase (the )?volume|turn (the )?volume up|raise (the )?volume|turn it up").matches(l))
            return { _ -> vol(AudioManager.ADJUST_RAISE, 2) }
        if (Regex("volume down|quieter|decrease (the )?volume|turn (the )?volume down|lower (the )?volume|turn it down").matches(l))
            return { _ -> vol(AudioManager.ADJUST_LOWER, 2) }
        if (Regex("mute( (the )?(video|sound|volume|audio))?").matches(l))
            return { _ -> vol(AudioManager.ADJUST_MUTE, 1) }
        if (Regex("unmute( (the )?(video|sound|volume|audio))?").matches(l))
            return { _ -> vol(AudioManager.ADJUST_UNMUTE, 1) }

        if (Regex("skip( the| this)?( (ads?|adds?|advert|advertisement|commercial))?").matches(l))
            return { g -> skipAd(g) }

        if (Regex("(next|skip to (the )?next|play next)( (video|song|track|reel|short|one|post))?").matches(l))
            return { _ ->
                if (curPkg().contains("instagram") || l.contains("reel") || l.contains("short"))
                    swipe("up")
                else media(KeyEvent.KEYCODE_MEDIA_NEXT)
            }
        if (Regex("(previous|go to previous|play previous)( (video|song|track|reel|short|one|post))?").matches(l))
            return { _ ->
                if (curPkg().contains("instagram") || l.contains("reel") || l.contains("short"))
                    swipe("down")
                else media(KeyEvent.KEYCODE_MEDIA_PREVIOUS)
            }

        val few = l.split(" ").size <= 5
        if (few && Regex("\\bforward\\b").containsMatchIn(l) && !l.contains(" to "))
            return { g -> seek(true, l, g) }
        if ((few && Regex("\\b(rewind|backward|backwards)\\b").containsMatchIn(l)) ||
            Regex("(go |skip )?back \\d+( seconds?)?").matches(l))
            return { g -> seek(false, l, g) }

        val sc = Regex("(scroll|swipe)( to the)? (down|up|left|right)( (\\d+) times?)?").matchEntire(l)
        if (sc != null) return { g ->
            val dir = sc.groupValues[3]
            val finger = if (sc.groupValues[1] == "scroll")
                mapOf("down" to "up", "up" to "down", "left" to "right", "right" to "left")[dir] ?: "up"
            else dir
            val n = (sc.groupValues[5].toIntOrNull() ?: 1).coerceIn(1, 15)
            for (i in 1..n) {
                if (!alive(g)) break
                swipe(finger)
                Thread.sleep(350)
            }
        }
        if (l == "scroll") return { _ -> swipe("up") }

        val au = Regex("(?:keep scrolling|auto ?scroll|start scrolling|scroll automatically|scroll continuously|keep swiping|scroll (?:the )?reels?|scroll (?:the )?shorts?)(?: every (\\d+) seconds?)?").matchEntire(l)
        if (au != null) return { g ->
            val sec = (au.groupValues[1].toIntOrNull() ?: 7).coerceIn(2, 60)
            while (alive(g)) {
                swipe("up")
                if (!nap(sec * 1000L, g)) break
            }
        }

        // screen vision
        if (Regex(".*\\b(on (my |the )?screen|this page|this screen|this (message|email|error|post|image|picture|photo|video|article)|read (this|it|the screen)|what does this say|what is this|explain this|summari[sz]e this|why am i getting).*").matches(l))
            return { g -> askScreen(l, g) }

        // open / search
        val oc = Regex("open (youtube|amazon|flipkart|google|chrome) and (search|find|look up|play|watch)(?: for)? (.+)").matchEntire(l)
        if (oc != null) return { g ->
            doSearch(oc.groupValues[3], oc.groupValues[1].replace("chrome", "google"),
                oc.groupValues[2] == "play" || oc.groupValues[2] == "watch", g)
        }

        val o = Regex("(?:open|launch|start) (.+)").matchEntire(l)
        if (o != null && !Regex("\\b(and|then)\\b").containsMatchIn(l)) {
            val name = o.groupValues[1].trim()
            if (findApp(name) != null) return { _ ->
                val p = findApp(name)
                if (p != null) launch(p)
            }
        }

        // web answers
        if (Regex("(search|look up|find)( for)? (the )?(internet|web) .+").matches(l) ||
            Regex("(what|what's|whats|who|who's|when|where|why|how|which|tell me|explain|define|is|are|does|do|can|will|should) .+").matches(l) ||
            Regex("weather.*|news.*|.*\\bweather\\b.*").matches(l))
            return { g -> answerQ(l, g) }

        val s = Regex("(search|google|find|look up|look for)(?: for)? (.+?)(?: on (amazon|flipkart|youtube|google))?").matchEntire(l)
        if (s != null && !Regex("\\b(and|then)\\b").containsMatchIn(s.groupValues[2])) return { g ->
            var site = s.groupValues[3]
            if (site.isEmpty()) site = siteFor(curPkg())
            if (site.isEmpty()) site = "google"
            doSearch(s.groupValues[2], site, false, g)
        }

        val p = Regex("(play|watch)(?: me)? (.+?)(?: on youtube)?").matchEntire(l)
        if (p != null && !Regex("\\b(and|then)\\b").containsMatchIn(p.groupValues[2])) return { g ->
            doSearch(p.groupValues[2], "youtube", true, g)
        }

        return null
    }

    private fun vol(dir: Int, n: Int) {
        val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        for (i in 1..n) am.adjustStreamVolume(AudioManager.STREAM_MUSIC, dir, AudioManager.FLAG_SHOW_UI)
    }

    private fun media(code: Int) {
        val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
        am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
    }

    private fun playerY(p: String): Float {
        val m = resources.displayMetrics
        val w = m.widthPixels.toFloat()
        val h = m.heightPixels.toFloat()
        return if (w > h) h * 0.5f
        else if (p.contains("youtube")) h * 0.035f + w * 9f / 32f
        else h * 0.45f
    }

    private fun pauseMedia() {
        val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        if (!am.isMusicActive) return
        media(KeyEvent.KEYCODE_MEDIA_PAUSE)
        Thread.sleep(450)
        val p = curPkg()
        if (am.isMusicActive && isVideoApp(p)) {
            tap(resources.displayMetrics.widthPixels / 2, playerY(p).toInt())
        }
    }

    private fun playMedia() {
        val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        if (am.isMusicActive) return
        media(KeyEvent.KEYCODE_MEDIA_PLAY)
        Thread.sleep(500)
        val p = curPkg()
        if (!am.isMusicActive && isVideoApp(p)) {
            tap(resources.displayMetrics.widthPixels / 2, playerY(p).toInt())
        }
    }

    private fun seek(right: Boolean, l: String, g: Int) {
        val p = curPkg()
        if (p.contains("instagram")) {
            card("I can't seek in reels.", 2000)
            return
        }
        val sec = Regex("\\d+").find(l)?.value?.toIntOrNull() ?: 10
        val taps = ((sec + 5) / 10).coerceIn(1, 6)
        val x = resources.displayMetrics.widthPixels * (if (right) 0.82f else 0.18f)
        val y = playerY(p)
        for (i in 1..taps) {
            if (!alive(g)) return
            doubleTap(x, y)
            Thread.sleep(150)
        }
    }

    private fun skipAd(g: Int) {
        for (i in 1..20) {
            if (!alive(g)) return
            if (clickByText("skip ad", "skip")) return
            if (!nap(400, g)) return
        }
        card("I don't see a skip button yet.", 2500)
    }

    private fun clickByText(vararg words: String): Boolean {
        val root = rootInActiveWindow ?: return false
        var found: AccessibilityNodeInfo? = null
        fun walk(n: AccessibilityNodeInfo?) {
            if (n == null || found != null) return
            val t = ((n.text?.toString() ?: "") + " " + (n.contentDescription?.toString() ?: "")).lowercase()
            if (n.isVisibleToUser && words.any { t.contains(it) }) {
                found = n
                return
            }
            for (i in 0 until n.childCount) walk(n.getChild(i))
        }
        walk(root)
        val f = found ?: return false
        var c: AccessibilityNodeInfo? = f
        while (c != null && !c.isClickable) c = c.parent
        if (c != null) return c.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        val r = Rect()
        f.getBoundsInScreen(r)
        tap(r.centerX(), r.centerY())
        return true
    }

    private fun nap(ms: Long, g: Int): Boolean {
        val end = SystemClock.uptimeMillis() + ms
        while (SystemClock.uptimeMillis() < end) {
            if (!alive(g)) return false
            Thread.sleep(50)
        }
        return alive(g)
    }

    private fun doSearch(q0: String, site: String, playFirst: Boolean, g: Int) {
        var query = q0.trim()
        var extra = ""
        val cheapRe = Regex("\\b(cheapest|cheap|lowest price|low price|low to high|in price|price)\\b",
            RegexOption.IGNORE_CASE)
        if (Regex("\\b(cheapest|cheap|lowest price|low price|low to high)\\b",
                RegexOption.IGNORE_CASE).containsMatchIn(query) &&
            (site == "amazon" || site == "flipkart")) {
            query = cheapRe.replace(query, "").replace(Regex("\\s+"), " ").trim()
            extra = if (site == "amazon") "&s=price-asc-rank" else "&sort=price_asc"
        }
        val e = Uri.encode(query)
        val (url, pkgs) = when (site) {
            "amazon" -> Pair("https://www.amazon.in/s?k=$e$extra",
                listOf("in.amazon.mShop.android.shopping", "com.amazon.mShop.android.shopping"))
            "flipkart" -> Pair("https://www.flipkart.com/search?q=$e$extra", listOf("com.flipkart.android"))
            "youtube" -> Pair("https://www.youtube.com/results?search_query=$e", listOf("com.google.android.youtube"))
            else -> Pair("https://www.google.com/search?q=$e", emptyList<String>())
        }
        viewUrl(url, pkgs)
        if (playFirst) {
            settle(3000)
            if (!alive(g)) return
            agent("On the YouTube search results, tap the first real video (not an ad, not a Short, not a playlist) so it starts playing. Then finish.", g)
        }
    }

    private fun viewUrl(url: String, pkgs: List<String>) {
        for (p in pkgs) {
            val i = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            i.setPackage(p)
            if (tryStart(i)) return
        }
        tryStart(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
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
        if (i != null) tryStart(i)
    }

    // ---------- vision and answers ----------

    private fun shot(): String? {
        if (Build.VERSION.SDK_INT < 30) return null
        val latch = CountDownLatch(1)
        var out: String? = null
        takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor,
            object : AccessibilityService.TakeScreenshotCallback {
                override fun onSuccess(r: AccessibilityService.ScreenshotResult) {
                    try {
                        val hb = r.hardwareBuffer
                        val bmp = Bitmap.wrapHardwareBuffer(hb, r.colorSpace)
                        if (bmp != null) {
                            val sw = bmp.copy(Bitmap.Config.ARGB_8888, false)
                            val h = (sw.height * 720f / sw.width).toInt()
                            val small = Bitmap.createScaledBitmap(sw, 720, h, true)
                            val bos = ByteArrayOutputStream()
                            small.compress(Bitmap.CompressFormat.JPEG, 60, bos)
                            out = Base64.encodeToString(bos.toByteArray(), Base64.NO_WRAP)
                        }
                        hb.close()
                    } catch (e: Exception) {}
                    latch.countDown()
                }
                override fun onFailure(code: Int) { latch.countDown() }
            })
        latch.await(3, TimeUnit.SECONDS)
        return out
    }

    private fun imgPart(b64: String) = JSONObject().put("inline_data",
        JSONObject().put("mime_type", "image/jpeg").put("data", b64))

    private fun askScreen(q: String, g: Int) {
        val (_, els) = readScreen()
        val img = shot()
        val ui = els.joinToString("\n") { it.text.ifEmpty { it.desc } }.take(3000)
        val parts = JSONArray().put(JSONObject().put("text", "User question: $q\nVisible text on screen:\n$ui"))
        if (img != null) parts.put(imgPart(img))
        val r = gem(parts, ASK_SYS, false, false, g)
        if (alive(g)) reply(r.trim())
    }

    private fun answerQ(q: String, g: Int) {
        val mem = facts().joinToString("; ")
        val parts = JSONArray().put(JSONObject().put("text", "About the user: $mem\nQuestion: $q"))
        val r = gem(parts, ASK_SYS, false, true, g)
        if (alive(g)) reply(r.trim())
    }

    // ---------- AI agent (bigger tasks) ----------

    private fun agent(goal: String, g: Int) {
        val hist = mutableListOf<String>()
        val apps = appList()
        for (step in 1..25) {
            if (!alive(g)) return
            val (pkg, els) = readScreen()
            val img = if (els.size < 6) shot() else null
            val a = think(goal, pkg, els, hist, apps, g, img)
            if (!alive(g)) return
            val k = a.optString("action")
            if (k == "done") { reply(a.optString("message", "All done.")); return }
            if (k == "ask_user") {
                val r = ask(a.optString("message", "Could you tell me a bit more?"), g)
                if (!alive(g)) return
                if (r.isBlank()) { card("I didn't hear anything, so I'll stop here.", 4000); return }
                hist.add("asked the user, who said: $r")
                paint(2)
                continue
            }
            val e = if (k == "tap") els.getOrNull(a.optInt("index", -1)) else null
            val label = if (e == null) "" else e.text.ifEmpty { e.desc }
            val forced = e != null && SENSITIVE.containsMatchIn(label.lowercase())
            if (a.optBoolean("risky") || forced) {
                val q = if (forced && !a.optBoolean("risky")) "Should I tap \"$label\"?"
                else a.optString("message", "Should I go ahead?")
                val r = ask(q, g)
                if (!alive(g)) return
                if (!Regex("\\b(yes|yeah|yep|sure|ok|okay|go ahead|do it|haan|ha)\\b")
                        .containsMatchIn(r.lowercase())) {
                    card("Okay, I won't.", 3000)
                    return
                }
                paint(2)
            }
            val did = act(a, els)
            log(did)
            hist.add(did)
            if (hist.size >= 4 && hist.takeLast(4).distinct().size == 1) {
                reply("I'm a bit stuck. Could you take it from here?")
                return
            }
        }
        card("That's taking longer than expected, so I'll stop here.", 4000)
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
            if (code in 200..299) {
                val raw = c.inputStream.bufferedReader().readText()
                val ps = JSONObject(raw).getJSONArray("candidates").getJSONObject(0)
                    .getJSONObject("content").getJSONArray("parts")
                val sb = StringBuilder()
                for (i in 0 until ps.length()) sb.append(ps.getJSONObject(i).optString("text", ""))
                return sb.toString()
            }
            if (code == 400 && attempt == 0) continue
            throw Exception("Gemini error $code")
        }
        throw Exception("Gemini error 400")
    }

    private fun gem(parts: JSONArray, system: String, json: Boolean, search: Boolean, g: Int): String {
        val key = getP().getString("key", "") ?: ""
        val gc = JSONObject().put("temperature", 0.2)
        if (json) gc.put("responseMimeType", "application/json")
        val body = JSONObject()
            .put("system_instruction", JSONObject().put("parts",
                JSONArray().put(JSONObject().put("text", system))))
            .put("contents", JSONArray().put(JSONObject().put("role", "user").put("parts", parts)))
            .put("generationConfig", gc)
        if (search) body.put("tools", JSONArray().put(JSONObject().put("google_search", JSONObject())))
        var last: Exception? = null
        for (m in MODELS) {
            for (t in 1..2) {
                try {
                    return call(m, key, body)
                } catch (e: Exception) {
                    if (!alive(g)) throw e
                    last = e
                    val msg = e.message ?: ""
                    if (msg.contains("503") || msg.contains("500")) Thread.sleep(400) else break
                }
            }
        }
        throw last ?: Exception("Gemini failed")
    }

    private fun think(goal: String, pkg: String, els: List<El>, hist: List<String>,
                      apps: String, g: Int, img: String?): JSONObject {
        val ui = els.joinToString("\n") {
            "${it.i}|${it.text}|${it.desc}|${it.id.take(24)}|${if (it.click) "c" else ""}${if (it.edit) "e" else ""}"
        }
        val mem = facts().joinToString("; ")
        val prompt = "GOAL: $goal\nMEMORY: $mem\nCURRENT APP: $pkg\nAPPS: $apps\nDONE SO FAR: ${hist.takeLast(8)}\nSCREEN:\n$ui"
        val parts = JSONArray().put(JSONObject().put("text", prompt))
        if (img != null) parts.put(imgPart(img))
        var t = gem(parts, SYS, true, false, g).trim()
        if (t.startsWith("```")) t = t.removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        if (t.startsWith("[")) return JSONArray(t).getJSONObject(0)
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
                if (typeText(a.optString("text"))) {
                    settle(600)
                    "typed " + a.optString("text")
                } else {
                    card("Please type passwords yourself. I stay out of those.", 4000)
                    "skipped a password field"
                }
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

    // ---------- gestures ----------

    private fun dispatch(gd: GestureDescription) {
        val latch = CountDownLatch(1)
        dispatchGesture(gd, object : GestureResultCallback() {
            override fun onCompleted(d: GestureDescription?) { latch.countDown() }
            override fun onCancelled(d: GestureDescription?) { latch.countDown() }
        }, null)
        latch.await(3, TimeUnit.SECONDS)
    }

    private fun gesture(p: Path, dur: Long) {
        dispatch(GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(p, 0, dur)).build())
    }

    private fun tap(x: Int, y: Int) {
        val p = Path()
        p.moveTo((x + (-3..3).random()).toFloat(), (y + (-3..3).random()).toFloat())
        gesture(p, (45..85).random().toLong())
    }

    private fun doubleTap(x: Float, y: Float) {
        val p1 = Path()
        p1.moveTo(x, y)
        val p2 = Path()
        p2.moveTo(x, y)
        dispatch(GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(p1, 0, 40))
            .addStroke(GestureDescription.StrokeDescription(p2, 130, 40)).build())
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
        gesture(p, (220..320).random().toLong())
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

    private fun typeText(t: String): Boolean {
        val root = rootInActiveWindow ?: return true
        val n = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: firstEditable(root) ?: return true
        if (n.isPassword) return false
        val b = Bundle()
        b.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, t)
        n.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, b)
        return true
    }

    private fun pressEnter() {
        val n = rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return
        if (Build.VERSION.SDK_INT >= 30) {
            n.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
        }
    }
}
