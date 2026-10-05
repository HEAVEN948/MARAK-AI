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
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.TextView
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
        "record", "upload", "fill", "enter", "scan", "pay", "reply", "no")

    private val VIDEO_APPS = listOf("youtube", "instagram", "facebook", "netflix", "hotstar",
        "avod", "mxtech", "vlc", "snapchat", "musically", "tiktok", "primevideo", "sonyliv", "zee5")

    private val main = Handler(Looper.getMainLooper())
    private var tts: TextToSpeech? = null
    private var btn: TextView? = null
    private var bar: TextView? = null
    private var rec: SpeechRecognizer? = null
    private val tone by lazy { ToneGenerator(AudioManager.STREAM_MUSIC, 60) }

    @Volatile private var gen = 0
    @Volatile private var lastEvt = 0L
    @Volatile private var conn: HttpURLConnection? = null
    @Volatile private var paused = false
    @Volatile private var waiting = false
    @Volatile private var answer = ""
    @Volatile private var answerLatch: CountDownLatch? = null
    private var fg = false

    private fun alive(g: Int) = g == gen

    private val SYS = """You are MARAK, a fast, polite phone assistant. You operate an Android phone for the user the way a skilled person would. Work step by step toward the goal.
Each turn you see the current app, the installed apps (label:package) and the screen elements (index|text|desc|id|flags where c=clickable e=editable).
Reply ONLY with one JSON object: {"action":"","index":0,"text":"","package":"","key":"","direction":"","risky":false,"message":""}
Actions: open_app(package) | tap(index) | type(text) | key(back|home|recents|enter) | swipe(direction up|down|left|right) | wait | ask_user(message) | done(message).
Rules:
- Take the shortest path. Do not reopen an app that is already open. Prefer search boxes over scrolling.
- Tap a search field before typing, then use key enter.
- swipe up scrolls the page down.
- To play a video on YouTube: search, then tap the first real video result, not an ad and not a Short.
- To change YouTube playback speed: open the player menu (gear or three dots), then Playback speed.
- Do exactly what the user asked and nothing extra. If the request is unclear, use ask_user with one short question.
- Set risky=true for anything that buys, pays, sends, posts, calls, deletes or changes settings, and put a short natural yes/no question in message, like: Should I send it to Rahul?
- Never enter passwords or OTPs: use ask_user.
- If the screen is empty or blocked, use done and say so.
- message is shown on screen: one or two short, warm, natural sentences in first person, no technical words, no emojis, no lists.
- When finished, use done."""

    // ---------- lifecycle ----------

    override fun onServiceConnected() {
        instance = this
        tts = TextToSpeech(this, this)
        addButton()
        addBar()
        goForeground()
        paint(1)
        show("Marak is listening.", 2500)
        restart(300)
    }

    override fun onInit(status: Int) {
        tts?.language = Locale.getDefault()
        tts?.setSpeechRate(1.0f)
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

    // ---------- overlay: button + text bar ----------

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

    private fun show(t: String, ms: Long = 3500) {
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

    private fun voiceOn() = getSharedPreferences("m", MODE_PRIVATE).getBoolean("voice", false)
    private fun setVoice(v: Boolean) =
        getSharedPreferences("m", MODE_PRIVATE).edit().putBoolean("voice", v).apply()

    private fun speak(t: String) {
        if (voiceOn()) tts?.speak(t, TextToSpeech.QUEUE_FLUSH, null, "m")
    }

    private fun say(t: String, ms: Long = 4000) {
        show(t, ms)
        speak(t)
    }

    private fun attention() {
        try { tone.startTone(ToneGenerator.TONE_PROP_ACK, 120) } catch (e: Exception) {}
    }

    private fun togglePause() {
        if (paused) {
            paused = false
            paint(1)
            show("Listening.", 1500)
            restart(0)
        } else {
            paused = true
            main.removeCallbacks(restartRun)
            try { rec?.destroy() } catch (e: Exception) {}
            rec = null
            paint(3)
            show("Mic paused. Tap the button to resume.", 3000)
        }
    }

    // ---------- continuous listening ----------

    private val restartRun = Runnable { startRec() }

    private fun restart(ms: Long) {
        main.removeCallbacks(restartRun)
        main.postDelayed(restartRun, ms)
    }

    private fun micOk() =
        checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun startRec() {
        if (paused) return
        if (!micOk()) {
            show("Please allow the microphone in the Marak app.", 4000)
            restart(4000)
            return
        }
        goForeground()
        try {
            rec?.destroy()
            val r = SpeechRecognizer.createSpeechRecognizer(this)
            rec = r
            r.setRecognitionListener(object : RecognitionListener {
                override fun onResults(b: Bundle?) {
                    val t = b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull() ?: ""
                    if (t.isNotBlank()) onHeard(t)
                    restart(120)
                }
                override fun onError(error: Int) {
                    when (error) {
                        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> restart(4000)
                        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> restart(1200)
                        SpeechRecognizer.ERROR_NO_MATCH,
                        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> restart(150)
                        else -> restart(700)
                    }
                }
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
            i.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-IN")
            i.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 700L)
            i.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 700L)
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

    private fun onHeard(raw: String) {
        if (tts?.isSpeaking == true) return
        if (waiting) {
            answer = raw
            answerLatch?.countDown()
            return
        }
        val (s, addressed) = clean(raw)
        handle(s, addressed)
    }

    private fun handle(s: String, addressed: Boolean) {
        if (s.isBlank()) return
        val words = s.split(" ").size
        val first = s.substringBefore(" ")
        if (!addressed && (words > 14 || first !in STARTERS)) return
        val act = match(s)
        if (act != null) {
            show("\u25B6 $s", 2500)
            val g = interrupt()
            Thread { runTask { act(g) } }.start()
        } else {
            val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            if (!addressed && am.isMusicActive) return
            show("\u25B6 $s", 2500)
            val g = interrupt()
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
            if (alive(g)) show(friendly(e), 5000)
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
        show(q, 20000)
        attention()
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

    private fun match(l: String): ((Int) -> Unit)? {
        if (Regex("cancel|never mind|nevermind|forget it|enough").matches(l))
            return { _ -> show("Okay.", 1500) }

        if (Regex("(stop|pause) listening|go to sleep|sleep|mic off").matches(l))
            return { _ -> main.post { if (!paused) togglePause() } }

        if (Regex("voice on|talk to me|speak to me|reply with voice").matches(l))
            return { _ -> setVoice(true); say("Voice replies are on.") }

        if (Regex("voice off|be quiet|stop talking|no voice|silent mode").matches(l))
            return { _ -> setVoice(false); show("Voice replies are off.", 2500) }

        if (Regex("(stop|pause)( (the )?(video|music|song|playing|playback|it|this|that))?|hold on|wait").matches(l))
            return { _ -> pauseMedia() }

        if (Regex("(play|resume|continue|unpause|start)( (the )?(video|music|song|playing|playback|it|this|that|again))?").matches(l))
            return { _ -> playMedia() }

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

        val au = Regex("(keep scrolling|auto ?scroll|start scrolling|scroll automatically|scroll continuously|keep swiping)( every (\\d+) seconds?)?").matchEntire(l)
        if (au != null) return { g ->
            val sec = (au.groupValues[3].toIntOrNull() ?: 7).coerceIn(2, 60)
            show("Scrolling every $sec seconds. Say stop to end.", 4000)
            while (alive(g)) {
                swipe("up")
                if (!nap(sec * 1000L, g)) break
            }
        }

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
                if (p != null) {
                    show("Opening $name.", 1500)
                    launch(p)
                }
            }
        }

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
        show("Paused.", 1200)
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
            show("I can't seek in reels.", 2000)
            return
        }
        val sec = Regex("\\d+").find(l)?.value?.toIntOrNull() ?: 10
        val taps = ((sec + 5) / 10).coerceIn(1, 6)
        val x = resources.displayMetrics.widthPixels * (if (right) 0.82f else 0.18f)
        val y = playerY(p)
        show((if (right) "Forward " else "Back ") + (taps * 10) + " seconds", 1500)
        for (i in 1..taps) {
            if (!alive(g)) return
            doubleTap(x, y)
            Thread.sleep(150)
        }
    }

    private fun skipAd(g: Int) {
        for (i in 1..20) {
            if (!alive(g)) return
            if (clickByText("skip ad", "skip")) {
                show("Skipped.", 1200)
                return
            }
            if (!nap(400, g)) return
        }
        show("I don't see a skip button yet.", 2500)
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
        show("Searching " + site.replaceFirstChar { it.uppercase() } + " for " + query + "\u2026", 2500)
        viewUrl(url, pkgs)
        if (playFirst) {
            settle(3000)
            if (!alive(g)) return
            agent("On the YouTube search results, tap the first real video (not an ad, not a Short, not a playlist) so it starts playing. Then finish.", g)
        }
    }

    private fun viewUrl(url: String, pkgs: List<String>) {
        for (p in pkgs) {
            try {
                val i = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                i.setPackage(p)
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(i)
                return
            } catch (e: Exception) {}
        }
        val i = Intent(Intent.ACTION_VIEW, Uri.parse(url))
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(i)
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

    // ---------- AI agent (bigger tasks) ----------

    private fun agent(goal: String, g: Int) {
        val hist = mutableListOf<String>()
        val apps = appList()
        show("On it\u2026", 2000)
        for (step in 1..25) {
            if (!alive(g)) return
            val (pkg, els) = readScreen()
            val a = think(goal, pkg, els, hist, apps, g)
            if (!alive(g)) return
            val k = a.optString("action")
            if (k == "done") { say(a.optString("message", "All done.")); return }
            if (k == "ask_user") {
                val r = ask(a.optString("message", "Could you tell me a bit more?"), g)
                if (!alive(g)) return
                if (r.isBlank()) { show("I didn't hear anything, so I'll stop here.", 4000); return }
                hist.add("asked the user, who said: $r")
                paint(2)
                continue
            }
            if (a.optBoolean("risky")) {
                val r = ask(a.optString("message", "Should I go ahead?"), g)
                if (!alive(g)) return
                if (!Regex("\\b(yes|yeah|yep|sure|ok|okay|go ahead|do it|haan|ha)\\b")
                        .containsMatchIn(r.lowercase())) {
                    show("Okay, I won't.", 3000)
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
        show("That's taking longer than expected, so I'll stop here.", 4000)
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
