package com.example.myandroidapp

import android.accessibilityservice.AccessibilityService
import android.animation.ValueAnimator
import android.app.*
import android.content.*
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.net.Uri
import android.os.*
import android.provider.Settings
import android.text.TextUtils
import android.util.LruCache
import android.util.TypedValue
import android.view.*
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.*
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.roundToInt

// ============================================================================
// 1. ACCESSIBILITY SERVICE: APP BLOCKER, ADULT/WEB BLOCKER & MORNING UNLOCK
// ============================================================================
class FocusAccessibilityService : AccessibilityService() {

    private var unlockReceiver: BroadcastReceiver? = null
    private var lastBlockedTime = 0L

    private val blockedAppsAlways = setOf(
        "com.google.android.youtube",
        "com.instagram.android",
        "com.zhiliaoapp.musically",
        "com.ss.android.ugc.trill",
        "com.snapchat.android",
        "com.facebook.katana",
        "com.reddit.frontpage"
    )

    private val browserPackages = setOf(
        "com.android.chrome",
        "org.mozilla.firefox",
        "com.microsoft.emmx",
        "com.brave.browser",
        "com.sec.android.app.sbrowser",
        "com.opera.browser",
        "com.opera.mini.native",
        "com.UCMobile.intl"
    )

    private val defaultBlockedDomains = listOf(
        "youtube.com", "youtu.be", "instagram.com", "reels",
        "pornhub", "xvideos", "xnxx", "xhamster", "redtube",
        "youporn", "spankbang", "onlyfans", "chaturbate",
        "stripchat", "brazzers", "eporner", "hqporner", "xxx", "adult"
    )

    private val allowedFocusPackages = setOf(
        "com.google.android.dialer",
        "com.android.dialer",
        "com.android.incallui",
        "com.android.server.telecom",
        "com.samsung.android.incallui",
        "com.samsung.android.dialer",
        "com.coloros.phonemanager",
        "com.android.contacts",
        "com.android.systemui",
        "android"
    )

    override fun onServiceConnected() {
        super.onServiceConnected()
        registerFirstUnlockDetector()
    }

    private fun registerFirstUnlockDetector() {
        if (unlockReceiver != null) return
        unlockReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action == Intent.ACTION_USER_PRESENT) {
                    checkAndShowMorningQuote(context)
                }
            }
        }
        val filter = IntentFilter(Intent.ACTION_USER_PRESENT)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(unlockReceiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            registerReceiver(unlockReceiver, filter)
        }
    }

    private fun checkAndShowMorningQuote(context: Context) {
        val prefs = context.getSharedPreferences("jee_command_db", Context.MODE_PRIVATE)
        val today = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        val lastShown = prefs.getString("last_morning_quote_date", "") ?: ""
        if (lastShown != today) {
            prefs.edit().putString("last_morning_quote_date", today).apply()
            val launchIntent = Intent(context, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                putExtra("MORNING_UNLOCK_QUOTE", true)
            }
            context.startActivity(launchIntent)
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        val pkg = event.packageName?.toString() ?: return

        val keyguard = getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
        if (keyguard?.isKeyguardLocked == false) {
            checkAndShowMorningQuote(this)
        }

        val prefs = getSharedPreferences("jee_command_db", Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        val focusEndMs = prefs.getLong("focus_end_ms", 0L)
        val isFocusActive = focusEndMs > now
        val shieldEnabled = prefs.getBoolean("always_shield_enabled", true)

        if (pkg == applicationContext.packageName ||
            pkg.contains("inputmethod") ||
            pkg.contains("keyboard") ||
            pkg.contains("launcher") ||
            pkg.contains("nexuslauncher") ||
            pkg.contains("permissioncontroller")
        ) {
            return
        }

        if (isFocusActive) {
            val isCallRelated = allowedFocusPackages.contains(pkg) ||
                    pkg.contains("dialer") || pkg.contains("incall") || pkg.contains("telecom") || pkg.contains("phone")
            if (!isCallRelated) {
                blockAndRedirect("Focus Lockdown Active! Only Phone Calls & JEE Prep are allowed.")
                return
            }
        }

        if (shieldEnabled || isFocusActive) {
            if (blockedAppsAlways.contains(pkg)) {
                blockAndRedirect("Blocked by JEE Shield: Stay locked in on your JEE target!")
                return
            }

            if (browserPackages.contains(pkg) || pkg.contains("browser") || pkg.contains("chrome")) {
                val customRaw = prefs.getString("custom_blocked_sites", "") ?: ""
                val customSites = customRaw.split(",").map { it.trim().lowercase() }.filter { it.isNotEmpty() }
                val allBlockedKeywords = defaultBlockedDomains + customSites

                val evText = event.text?.toString()?.lowercase() ?: ""
                if (allBlockedKeywords.any { evText.contains(it) }) {
                    performGlobalAction(GLOBAL_ACTION_BACK)
                    blockAndRedirect("Blocked Website (Social / Adult Filter Active)!")
                    return
                }

                val rootNode = rootInActiveWindow ?: return
                if (containsBlockedUrl(rootNode, allBlockedKeywords)) {
                    performGlobalAction(GLOBAL_ACTION_BACK)
                    blockAndRedirect("Blocked Website (Social / Adult Filter Active)!")
                }
            }
        }
    }

    private fun containsBlockedUrl(node: AccessibilityNodeInfo?, blockedKeywords: List<String>): Boolean {
        if (node == null) return false
        val text = node.text?.toString()?.lowercase() ?: ""
        val viewId = node.viewIdResourceName?.lowercase() ?: ""

        if (text.isNotEmpty() && (viewId.contains("url") || viewId.contains("search") || viewId.contains("omnibox") || viewId.contains("address") || text.contains("http") || text.contains(".com") || text.contains(".in") || text.contains(".org") || text.contains(".net"))) {
            for (kw in blockedKeywords) {
                if (text.contains(kw)) return true
            }
        }

        for (i in 0 until node.childCount) {
            if (containsBlockedUrl(node.getChild(i), blockedKeywords)) return true
        }
        return false
    }

    private fun blockAndRedirect(reason: String) {
        val now = System.currentTimeMillis()
        if (now - lastBlockedTime < 1200L) return
        lastBlockedTime = now

        performGlobalAction(GLOBAL_ACTION_HOME)
        Handler(Looper.getMainLooper()).postDelayed({
            Toast.makeText(applicationContext, reason, Toast.LENGTH_LONG).show()
            val intent = Intent(applicationContext, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            }
            startActivity(intent)
        }, 220)
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        super.onDestroy()
        unlockReceiver?.let {
            try { unregisterReceiver(it) } catch (_: Exception) {}
        }
    }
}

// ============================================================================
// 2. ALARM & NOTIFICATION RECEIVER
// ============================================================================
class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val title = intent.getStringExtra("TITLE") ?: "JEE Focus Reminder"
        val message = intent.getStringExtra("MESSAGE") ?: "Time to execute your scheduled JEE study block!"
        val notifId = intent.getIntExtra("NOTIF_ID", (System.currentTimeMillis() % 10000).toInt())

        val channelId = "jee_reminders_channel"
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(channelId, "JEE Study & PYQ Reminders", NotificationManager.IMPORTANCE_HIGH)
            nm.createNotificationChannel(channel)
        }

        val tapIntent = Intent(context, MainActivity::class.java)
        val pendingTap = PendingIntent.getActivity(
            context, notifId, tapIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle(title)
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pendingTap)
            .build()

        nm.notify(notifId, notification)
    }
}

// ============================================================================
// 3. SMOOTH ANIMATED GRAPHICAL SUBJECT ANALYTICS VIEW
// ============================================================================
class SubjectAnalyticsView(context: Context) : View(context) {

    data class SubjectStat(
        val name: String,
        val completed: Int,
        val partial: Int,
        val total: Int,
        val primaryColor: Int,
        val secondaryColor: Int
    )

    private var stats: List<SubjectStat> = emptyList()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rectF = RectF()
    private var animProgress = 0f
    private var animator: ValueAnimator? = null

    fun updateStats(newStats: List<SubjectStat>, animate: Boolean = true) {
        stats = newStats
        animator?.cancel()
        if (animate) {
            animator = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 680L
                interpolator = DecelerateInterpolator(1.8f)
                addUpdateListener {
                    animProgress = it.animatedValue as Float
                    invalidate()
                }
                start()
            }
        } else {
            animProgress = 1f
            invalidate()
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val desiredHeight = (320 * resources.displayMetrics.density).roundToInt()
        setMeasuredDimension(width, desiredHeight)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val d = resources.displayMetrics.density
        val w = width.toFloat()
        val h = height.toFloat()

        // Elevated Surface Card with Subtle Border
        paint.style = Paint.Style.FILL
        paint.color = Color.parseColor("#111726")
        rectF.set(0f, 0f, w, h)
        canvas.drawRoundRect(rectF, 22f * d, 22f * d, paint)

        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1.2f * d
        paint.color = Color.parseColor("#1E293B")
        canvas.drawRoundRect(rectF, 22f * d, 22f * d, paint)
        paint.style = Paint.Style.FILL

        val pad = 20f * d

        // Section Header
        paint.color = Color.parseColor("#818CF8")
        paint.textSize = 11f * d
        paint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        canvas.drawText("LIVE SYLLABUS ANALYTICS", pad, 28f * d, paint)

        // Overall Mastery Calculation
        var totalChaps = 0
        var totalScore = 0f
        for (s in stats) {
            totalChaps += s.total
            totalScore += s.completed + (s.partial * 0.5f)
        }
        val overallPct = if (totalChaps > 0) ((totalScore / totalChaps) * 100f * animProgress).roundToInt() else 0

        paint.color = Color.WHITE
        paint.textSize = 18f * d
        canvas.drawText("Overall JEE Readiness: $overallPct%", pad, 52f * d, paint)

        // Legend Row
        paint.textSize = 11.5f * d
        paint.typeface = Typeface.DEFAULT
        paint.color = Color.parseColor("#10B981")
        canvas.drawText("● Completed", pad, 76f * d, paint)
        paint.color = Color.parseColor("#F59E0B")
        canvas.drawText("● Partial (50%)", pad + 95f * d, 76f * d, paint)
        paint.color = Color.parseColor("#475569")
        canvas.drawText("● Remaining", pad + 198f * d, 76f * d, paint)

        var startY = 112f * d
        val barHeight = 14f * d
        val barLeft = pad
        val barRight = w - pad
        val barWidth = barRight - barLeft

        for (stat in stats) {
            val total = max(stat.total, 1)
            val compRatio = (stat.completed.toFloat() / total) * animProgress
            val partRatio = (stat.partial.toFloat() / total) * animProgress
            val effectivePct = (((stat.completed * 1.0f + stat.partial * 0.5f) / total) * 100f * animProgress).roundToInt()

            // Subject Name
            paint.color = Color.WHITE
            paint.textSize = 14f * d
            paint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            canvas.drawText(stat.name, barLeft, startY, paint)

            // Percentage & Chapter Count on Right
            val badgeText = "$effectivePct%  (${stat.completed}/${stat.total} Done • ${stat.partial} Part)"
            paint.color = Color.parseColor("#94A3B8")
            paint.textSize = 12f * d
            paint.typeface = Typeface.DEFAULT
            val tw = paint.measureText(badgeText)
            canvas.drawText(badgeText, barRight - tw, startY, paint)

            val topY = startY + 10f * d
            val bottomY = topY + barHeight

            // Track Background
            paint.color = Color.parseColor("#1E293B")
            rectF.set(barLeft, topY, barRight, bottomY)
            canvas.drawRoundRect(rectF, 7f * d, 7f * d, paint)

            // Partial + Completed Bar
            val combinedWidth = barWidth * (compRatio + partRatio)
            if (combinedWidth > 0f) {
                paint.color = Color.parseColor("#F59E0B")
                rectF.set(barLeft, topY, barLeft + combinedWidth, bottomY)
                canvas.drawRoundRect(rectF, 7f * d, 7f * d, paint)
            }

            // Completed Bar
            val compWidth = barWidth * compRatio
            if (compWidth > 0f) {
                paint.color = stat.primaryColor
                rectF.set(barLeft, topY, barLeft + compWidth, bottomY)
                canvas.drawRoundRect(rectF, 7f * d, 7f * d, paint)
            }

            startY += 68f * d
        }
    }
}

// ============================================================================
// 4. MAIN ACTIVITY: SMOOTH 120FPS DARK UI COMMAND CENTER
// ============================================================================
class MainActivity : AppCompatActivity() {

    private lateinit var prefs: SharedPreferences
    private lateinit var daysBoxValue: TextView
    private lateinit var hoursBoxValue: TextView
    private lateinit var minsBoxValue: TextView
    private lateinit var secsBoxValue: TextView
    private lateinit var countdownSubBanner: TextView
    private lateinit var mainScrollView: ScrollView
    private lateinit var contentContainer: LinearLayout
    private var liveAnalyticsView: SubjectAnalyticsView? = null

    private val navButtons = mutableListOf<TextView>()
    private var currentTab = 0
    private var selectedSubject = "Physics"

    // Async Image Loader & Cache for Zero-Lag Error Book Scrolling
    private val imageExecutor = Executors.newFixedThreadPool(2)
    private val bitmapCache = object : LruCache<String, Bitmap>(16 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    // File Pickers
    private lateinit var worksheetPicker: ActivityResultLauncher<Array<String>>
    private lateinit var errorImagePicker: ActivityResultLauncher<Array<String>>
    private lateinit var timetableFilePicker: ActivityResultLauncher<Array<String>>

    private var pendingWorksheetTitle: String = ""
    private var pendingWorksheetSubject: String = "Physics"
    private var pendingErrorSubject: String = "Physics"
    private var pendingErrorChapter: String = ""
    private var pendingErrorNote: String = ""

    private val uiHandler = Handler(Looper.getMainLooper())
    private val timerRunnable = object : Runnable {
        override fun run() {
            updateCountdownHeader()
            uiHandler.postDelayed(this, 1000L)
        }
    }

    private val jeeSyllabus = mapOf(
        "Physics" to listOf(
            "Units, Dimensions & Error Analysis",
            "Kinematics (1D & 2D Motion)",
            "Laws of Motion & Friction",
            "Work, Energy & Power",
            "Center of Mass & Collisions",
            "Rotational Mechanics",
            "Gravitation",
            "Mechanical Properties of Solids & Fluids",
            "Thermal Physics & Thermodynamics",
            "Kinetic Theory of Gases",
            "Oscillations (SHM) & Waves",
            "Electrostatics & Capacitors",
            "Current Electricity",
            "Moving Charges & Magnetism",
            "Electromagnetic Induction (EMI) & AC",
            "Ray Optics & Optical Instruments",
            "Wave Optics",
            "Modern Physics & Nuclear Physics",
            "Semiconductors & Experimental Physics"
        ),
        "Chemistry" to listOf(
            "Some Basic Concepts of Chemistry (Mole Concept)",
            "Atomic Structure",
            "Chemical Bonding & Molecular Structure",
            "Chemical Thermodynamics & Thermochemistry",
            "Solutions & Colligative Properties",
            "Chemical & Ionic Equilibrium",
            "Redox Reactions & Electrochemistry",
            "Chemical Kinetics",
            "Periodic Table & Periodicity",
            "p-Block Elements",
            "d- and f-Block Elements",
            "Coordination Compounds",
            "General Organic Chemistry (GOC) & Isomerism",
            "Hydrocarbons",
            "Haloalkanes & Haloarenes",
            "Alcohols, Phenols & Ethers",
            "Aldehydes, Ketones & Carboxylic Acids",
            "Amines & Diazonium Salts",
            "Biomolecules & Practical Organic Chemistry"
        ),
        "Mathematics" to listOf(
            "Sets, Relations & Functions",
            "Complex Numbers & Quadratic Equations",
            "Matrices & Determinants",
            "Permutations & Combinations",
            "Binomial Theorem & Sequences/Series",
            "Limits, Continuity & Differentiability",
            "Application of Derivatives (AOD)",
            "Indefinite & Definite Integration",
            "Differential Equations",
            "Straight Lines & Pair of Lines",
            "Circles",
            "Conic Sections (Parabola, Ellipse, Hyperbola)",
            "Vectors & 3D Geometry",
            "Trigonometric Ratios, Identities & Equations",
            "Inverse Trigonometric Functions",
            "Statistics & Probability"
        )
    )

    private val fallbackQuotes = listOf(
        "\"Every tough question you solve today is one less rank you lose in January.\" — JEE Command",
        "\"Discipline is choosing between what you want now and the IIT/NIT seat you want most.\"",
        "\"Don't just read organic mechanisms or physics derivations — write them from memory.\"",
        "\"Your Error Book is your highest-yield rank booster. Review your mistakes ruthlessly.\"",
        "\"220+ in JEE Main isn't magic; it's 54 chapters mastered one PYQ set at a time.\""
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.parseColor("#070A12")
        window.navigationBarColor = Color.parseColor("#070A12")

        prefs = getSharedPreferences("jee_command_db", Context.MODE_PRIVATE)

        if (!prefs.contains("jee_target_ms")) {
            val cal = Calendar.getInstance().apply {
                set(2027, Calendar.JANUARY, 20, 9, 0, 0)
            }
            prefs.edit().putLong("jee_target_ms", cal.timeInMillis).apply()
        }

        registerFilePickers()
        requestNotificationPermissionIfNeeded()
        buildMainScreen()
        check15DayPyqRevisions(triggerNotification = true)

        val today = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        val isMorningUnlock = intent.getBooleanExtra("MORNING_UNLOCK_QUOTE", false)
        val lastQuoteDate = prefs.getString("last_morning_quote_date", "") ?: ""
        if (isMorningUnlock || lastQuoteDate != today) {
            prefs.edit().putString("last_morning_quote_date", today).apply()
            showMorningUnlockDialog(closeAppOnDismiss = isMorningUnlock)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.getBooleanExtra("MORNING_UNLOCK_QUOTE", false)) {
            showMorningUnlockDialog(closeAppOnDismiss = true)
        }
    }

    override fun onResume() {
        super.onResume()
        uiHandler.post(timerRunnable)
        renderCurrentTab(animateTransition = false, keepScroll = true)
    }

    override fun onPause() {
        super.onPause()
        uiHandler.removeCallbacks(timerRunnable)
    }

    // ========================================================================
    // DP & TOUCH ANIMATION UTILITIES
    // ========================================================================
    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).roundToInt()
    private fun dpF(value: Float): Float = value * resources.displayMetrics.density

    private fun attachSpringTouch(view: View, onClick: () -> Unit) {
        view.isClickable = true
        view.isFocusable = true
        view.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    v.animate().scaleX(0.96f).scaleY(0.96f).setDuration(90)
                        .setInterpolator(DecelerateInterpolator()).start()
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v.animate().scaleX(1f).scaleY(1f).setDuration(160)
                        .setInterpolator(OvershootInterpolator(1.6f)).start()
                }
            }
            false
        }
        view.setOnClickListener { onClick() }
    }

    private fun roundedRippleBg(
        fillHex: String,
        radiusDp: Float,
        strokeHex: String? = null,
        strokeWidthDp: Float = 1.2f
    ): RippleDrawable {
        val base = GradientDrawable().apply {
            setColor(Color.parseColor(fillHex))
            cornerRadius = dpF(radiusDp)
            if (strokeHex != null) {
                setStroke(dp(strokeWidthDp.roundToInt()).coerceAtLeast(1), Color.parseColor(strokeHex))
            }
        }
        val mask = GradientDrawable().apply {
            setColor(Color.WHITE)
            cornerRadius = dpF(radiusDp)
        }
        return RippleDrawable(ColorStateList.valueOf(Color.parseColor("#33FFFFFF")), base, mask)
    }

    private fun gradientCardBg(
        topHex: String,
        bottomHex: String,
        radiusDp: Float,
        strokeHex: String
    ): GradientDrawable {
        return GradientDrawable(
            GradientDrawable.Orientation.TL_BR,
            intArrayOf(Color.parseColor(topHex), Color.parseColor(bottomHex))
        ).apply {
            cornerRadius = dpF(radiusDp)
            setStroke(dp(1), Color.parseColor(strokeHex))
        }
    }

    // ========================================================================
    // FILE PICKERS & PERMISSIONS
    // ========================================================================
    private fun registerFilePickers() {
        worksheetPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
            if (uri != null) {
                try {
                    contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                } catch (_: Exception) {}
                saveWorksheet(pendingWorksheetTitle, pendingWorksheetSubject, uri.toString())
                renderCurrentTab(animateTransition = false, keepScroll = true)
            }
        }

        errorImagePicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
            if (uri != null) {
                try {
                    contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                } catch (_: Exception) {}
                saveErrorBookEntry(pendingErrorSubject, pendingErrorChapter, pendingErrorNote, uri.toString())
                Toast.makeText(this, "Saved to $pendingErrorChapter!", Toast.LENGTH_SHORT).show()
                renderCurrentTab(animateTransition = false, keepScroll = true)
            }
        }

        timetableFilePicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
            if (uri != null) {
                try {
                    contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                } catch (_: Exception) {}
                prefs.edit().putString("uploaded_timetable_uri", uri.toString()).apply()
                Toast.makeText(this, "Timetable file linked!", Toast.LENGTH_SHORT).show()
                renderCurrentTab(animateTransition = false, keepScroll = true)
            }
        }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 101)
            }
        }
    }

    // ========================================================================
    // MORNING FIRST-UNLOCK DAILY QUOTE FULLSCREEN OVERLAY
    // ========================================================================
    private fun showMorningUnlockDialog(closeAppOnDismiss: Boolean) {
        val dialog = Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.parseColor("#070A12"))
            setPadding(dp(28), dp(40), dp(28), dp(40))
        }

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            background = gradientCardBg("#161F38", "#0D1322", 26f, "#312E81")
            setPadding(dp(26), dp(34), dp(26), dp(30))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        val badge = TextView(this).apply {
            text = "☀️  FIRST UNLOCK • DAILY JEE IGNITION"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
            setTextColor(Color.parseColor("#38BDF8"))
            setTypeface(null, Typeface.BOLD)
            gravity = Gravity.CENTER
            background = roundedRippleBg("#13233A", 50f, "#1E3A5F")
            setPadding(dp(16), dp(8), dp(16), dp(8))
        }

        val quoteText = TextView(this).apply {
            text = "Syncing today's quote..."
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
            setTextColor(Color.WHITE)
            setTypeface(null, Typeface.BOLD)
            gravity = Gravity.CENTER
            setLineSpacing(dpF(5f), 1f)
            setPadding(dp(8), dp(28), dp(8), dp(28))
        }

        val actionBtn = createPrimaryButton("Start Today's Grind", "#10B981") {
            dialog.dismiss()
            if (closeAppOnDismiss) {
                finishAndRemoveTask()
            }
        }

        card.addView(badge)
        card.addView(quoteText)
        card.addView(actionBtn)
        root.addView(card)
        dialog.setContentView(root)
        dialog.show()

        card.alpha = 0f
        card.scaleX = 0.92f
        card.scaleY = 0.92f
        card.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(280)
            .setInterpolator(DecelerateInterpolator()).start()

        Thread {
            var fetchedQuote: String? = null
            try {
                val url = URL("https://zenquotes.io/api/random")
                val conn = (url.openConnection() as HttpURLConnection).apply {
                    connectTimeout = 3500
                    readTimeout = 3500
                    requestMethod = "GET"
                }
                if (conn.responseCode == 200) {
                    val reader = BufferedReader(InputStreamReader(conn.inputStream))
                    val response = reader.readText()
                    reader.close()
                    val arr = JSONArray(response)
                    if (arr.length() > 0) {
                        val obj = arr.getJSONObject(0)
                        fetchedQuote = "\"${obj.getString("q")}\"\n\n— ${obj.getString("a")}"
                    }
                }
            } catch (_: Exception) {}

            val finalQuote = fetchedQuote ?: fallbackQuotes.random()
            prefs.edit().putString("cached_daily_quote", finalQuote).apply()
            runOnUiThread {
                quoteText.text = finalQuote
            }
        }.start()
    }

    // ========================================================================
    // MAIN SCREEN ARCHITECTURE: HERO COUNTDOWN + SEGMENTED BAR + SCROLL AREA
    // ========================================================================
    private fun buildMainScreen() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#070A12"))
        }

        // 1. Hero Top-Center JEE Countdown Card
        val heroCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            background = gradientCardBg("#181D3A", "#0E1424", 24f, "#4338CA")
            setPadding(dp(18), dp(18), dp(18), dp(16))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(dp(16), dp(14), dp(16), dp(10)) }
        }
        attachSpringTouch(heroCard) { promptChangeJeeExamDate() }

        val topBadge = TextView(this).apply {
            text = "🎯 JEE MAIN COUNTDOWN  •  TAP TO SET EXAM DATE"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f)
            setTextColor(Color.parseColor("#A5B4FC"))
            setTypeface(null, Typeface.BOLD)
            gravity = Gravity.CENTER
            letterSpacing = 0.06f
        }
        heroCard.addView(topBadge)

        // 4-Box Matrix Row (DAYS | HRS | MIN | SEC)
        val matrixRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(12); bottomMargin = dp(10) }
        }

        val (dBox, dVal) = createCountdownUnitBox("DAYS")
        val (hBox, hVal) = createCountdownUnitBox("HOURS")
        val (mBox, mVal) = createCountdownUnitBox("MINS")
        val (sBox, sVal) = createCountdownUnitBox("SECS")

        daysBoxValue = dVal
        hoursBoxValue = hVal
        minsBoxValue = mVal
        secsBoxValue = sVal

        matrixRow.addView(dBox)
        matrixRow.addView(hBox)
        matrixRow.addView(mBox)
        matrixRow.addView(sBox)
        heroCard.addView(matrixRow)

        countdownSubBanner = TextView(this).apply {
            text = "Syncing target date..."
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTextColor(Color.parseColor("#34D399"))
            setTypeface(null, Typeface.BOLD)
            gravity = Gravity.CENTER
        }
        heroCard.addView(countdownSubBanner)

        // 2. Sleek Floating Segmented Tab Bar
        val navTrack = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = roundedRippleBg("#111726", 16f, "#1E293B")
            setPadding(dp(5), dp(5), dp(5), dp(5))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(dp(16), 0, dp(16), dp(10)) }
        }

        val tabs = listOf("📚 Syllabus", "📸 Mistakes", "🛡️ Focus Lock", "⏰ Schedule")
        navButtons.clear()
        for (i in tabs.indices) {
            val tabView = TextView(this).apply {
                text = tabs[i]
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                setTypeface(null, Typeface.BOLD)
                gravity = Gravity.CENTER
                setPadding(dp(6), dp(11), dp(6), dp(11))
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            attachSpringTouch(tabView) {
                if (currentTab != i) {
                    currentTab = i
                    renderCurrentTab(animateTransition = true, keepScroll = false)
                }
            }
            navButtons.add(tabView)
            navTrack.addView(tabView)
        }

        // 3. Smooth Scrollable Content Area
        mainScrollView = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        }

        contentContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(6), dp(16), dp(90))
        }
        mainScrollView.addView(contentContainer)

        root.addView(heroCard)
        root.addView(navTrack)
        root.addView(mainScrollView)
        setContentView(root)

        updateCountdownHeader()
        renderCurrentTab(animateTransition = true, keepScroll = false)
    }

    private fun createCountdownUnitBox(unitLabel: String): Pair<LinearLayout, TextView> {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            background = roundedRippleBg("#0B101E", 14f, "#26314E")
            setPadding(dp(8), dp(10), dp(8), dp(10))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                setMargins(dp(4), 0, dp(4), 0)
            }
        }
        val numText = TextView(this).apply {
            text = "00"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
            setTextColor(Color.WHITE)
            setTypeface(Typeface.MONOSPACE, Typeface.BOLD)
            gravity = Gravity.CENTER
        }
        val lblText = TextView(this).apply {
            text = unitLabel
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 9.5f)
            setTextColor(Color.parseColor("#818CF8"))
            setTypeface(null, Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding(0, dp(2), 0, 0)
        }
        box.addView(numText)
        box.addView(lblText)
        return Pair(box, numText)
    }

    private fun updateCountdownHeader() {
        val targetMs = prefs.getLong("jee_target_ms", 0L)
        val now = System.currentTimeMillis()
        val diff = max(0L, targetMs - now)

        val days = diff / (1000L * 60 * 60 * 24)
        val hours = (diff / (1000L * 60 * 60)) % 24
        val mins = (diff / (1000L * 60)) % 60
        val secs = (diff / 1000L) % 60

        daysBoxValue.text = String.format(Locale.US, "%02d", days)
        hoursBoxValue.text = String.format(Locale.US, "%02d", hours)
        minsBoxValue.text = String.format(Locale.US, "%02d", mins)
        secsBoxValue.text = String.format(Locale.US, "%02d", secs)

        val dateStr = SimpleDateFormat("dd MMM yyyy", Locale.US).format(Date(targetMs))
        val focusEndMs = prefs.getLong("focus_end_ms", 0L)
        if (focusEndMs > now) {
            val remMin = ((focusEndMs - now) / 60000L) + 1
            countdownSubBanner.text = "🔒 FOCUS LOCKDOWN ACTIVE ($remMin MIN LEFT) • CALLS ALLOWED"
            countdownSubBanner.setTextColor(Color.parseColor("#F87171"))
        } else {
            countdownSubBanner.text = "Target Exam Date: $dateStr"
            countdownSubBanner.setTextColor(Color.parseColor("#34D399"))
        }
    }

    private fun promptChangeJeeExamDate() {
        val cal = Calendar.getInstance().apply {
            timeInMillis = prefs.getLong("jee_target_ms", System.currentTimeMillis())
        }
        DatePickerDialog(
            this,
            { _, year, month, dayOfMonth ->
                val newCal = Calendar.getInstance().apply {
                    set(year, month, dayOfMonth, 9, 0, 0)
                }
                prefs.edit().putLong("jee_target_ms", newCal.timeInMillis).apply()
                updateCountdownHeader()
            },
            cal.get(Calendar.YEAR),
            cal.get(Calendar.MONTH),
            cal.get(Calendar.DAY_OF_MONTH)
        ).show()
    }

    private fun renderCurrentTab(animateTransition: Boolean = false, keepScroll: Boolean = true) {
        val savedScrollY = if (keepScroll) mainScrollView.scrollY else 0

        for (i in navButtons.indices) {
            val active = (i == currentTab)
            navButtons[i].background = if (active) {
                roundedRippleBg("#4F46E5", 12f, "#6366F1")
            } else {
                roundedRippleBg("#00000000", 12f)
            }
            navButtons[i].setTextColor(if (active) Color.WHITE else Color.parseColor("#94A3B8"))
        }

        contentContainer.removeAllViews()
        when (currentTab) {
            0 -> renderSyllabusAndWorksheetsTab(animateTransition)
            1 -> renderErrorBookTab()
            2 -> renderFocusAndBlockerTab()
            3 -> renderTimetableAndPyqTab()
        }

        if (animateTransition) {
            contentContainer.alpha = 0f
            contentContainer.translationY = dpF(16f)
            contentContainer.animate()
                .alpha(1f)
                .translationY(0f)
                .setDuration(220)
                .setInterpolator(DecelerateInterpolator())
                .start()
        }

        mainScrollView.post {
            mainScrollView.scrollTo(0, savedScrollY)
        }
    }

    // ========================================================================
    // TAB 0: MOTIVATION + WORKSHEETS + ANIMATED GRAPH + INTERACTIVE SYLLABUS
    // ========================================================================
    private fun renderSyllabusAndWorksheetsTab(animateChart: Boolean) {
        // 1. Daily Motivation Card
        val quoteCard = createSurfaceCard()
        val quote = prefs.getString("cached_daily_quote", fallbackQuotes[0]) ?: fallbackQuotes[0]
        quoteCard.addView(createBadgeLabel("💡 DAILY MINDSET", "#38BDF8"))
        quoteCard.addView(TextView(this).apply {
            text = quote
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14.5f)
            setTextColor(Color.parseColor("#F1F5F9"))
            setLineSpacing(dpF(4f), 1f)
            setPadding(0, dp(10), 0, dp(14))
        })
        quoteCard.addView(createSecondaryButton("🔄 Refresh Daily Quote from Internet") {
            showMorningUnlockDialog(closeAppOnDismiss = false)
        })
        contentContainer.addView(quoteCard)

        // 2. Uploaded Worksheets & DPPs Card
        val worksheetCard = createSurfaceCard()
        worksheetCard.addView(createBadgeLabel("📄 WORKSHEETS & COACHING DPPs", "#A78BFA"))
        worksheetCard.addView(createCardTitle("Solve & Track Your Sheets"))
        worksheetCard.addView(createCardSub("Upload PDF or Image worksheets, open them in one tap, and mark them solved."))

        worksheetCard.addView(createPrimaryButton("＋ Upload Worksheet (PDF / Image)", "#4F46E5") {
            showUploadWorksheetModal()
        })

        val worksheets = getJsonArray("worksheets_list")
        if (worksheets.length() == 0) {
            worksheetCard.addView(createEmptyStateBox("No worksheets uploaded yet. Tap above to add your DPPs or coaching sheets."))
        } else {
            for (i in 0 until worksheets.length()) {
                val obj = worksheets.getJSONObject(i)
                val title = obj.getString("title")
                val subj = obj.getString("subject")
                val uriStr = obj.getString("uri")
                val done = obj.getBoolean("done")

                val itemCard = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    background = roundedRippleBg(
                        if (done) "#0B241D" else "#172033",
                        14f,
                        if (done) "#10B981" else "#26334D"
                    )
                    setPadding(dp(14), dp(14), dp(14), dp(14))
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply { topMargin = dp(10) }
                }

                val textCol = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                }
                attachSpringTouch(textCol) { openUriDocument(uriStr) }

                textCol.addView(TextView(this).apply {
                    text = if (done) "✅ $title" else "📄 $title"
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 14.5f)
                    setTextColor(if (done) Color.parseColor("#34D399") else Color.WHITE)
                    setTypeface(null, Typeface.BOLD)
                })
                textCol.addView(TextView(this).apply {
                    text = "$subj  •  Tap to open file"
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                    setTextColor(Color.parseColor("#94A3B8"))
                    setPadding(0, dp(3), 0, 0)
                })

                val openChip = createActionChip("Open", "#2563EB") {
                    openUriDocument(uriStr)
                }
                val doneChip = createActionChip(
                    if (done) "Undo" else "Done",
                    if (done) "#334155" else "#10B981"
                ) {
                    obj.put("done", !done)
                    saveJsonArray("worksheets_list", worksheets)
                    renderCurrentTab(animateTransition = false, keepScroll = true)
                }

                itemCard.addView(textCol)
                itemCard.addView(openChip)
                itemCard.addView(doneChip)
                worksheetCard.addView(itemCard)
            }
        }
        contentContainer.addView(worksheetCard)

        // 3. Smooth Animated Graphical Subject Analysis
        val chartView = SubjectAnalyticsView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(18) }
        }
        chartView.updateStats(calculateSubjectStats(), animate = animateChart)
        liveAnalyticsView = chartView
        contentContainer.addView(chartView)

        // 4. JEE Main & Advanced Interactive Syllabus
        val syllabusCard = createSurfaceCard()
        syllabusCard.addView(createBadgeLabel("🎯 JEE MAIN & ADVANCED SYLLABUS", "#34D399"))
        syllabusCard.addView(createCardTitle("Subject-Wise Chapter Tracker"))
        syllabusCard.addView(createCardSub("Tap a subject below, then tap Pending, Partial, or Done on any chapter."))

        // Subject Selector Pills
        val subjRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(10); bottomMargin = dp(14) }
        }

        val subjects = listOf("Physics", "Chemistry", "Mathematics")
        for (subj in subjects) {
            val isSel = (subj == selectedSubject)
            val pill = TextView(this).apply {
                text = subj
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                setTypeface(null, Typeface.BOLD)
                gravity = Gravity.CENTER
                background = roundedRippleBg(
                    if (isSel) "#10B981" else "#172033",
                    12f,
                    if (isSel) "#34D399" else "#26334D"
                )
                setTextColor(Color.WHITE)
                setPadding(dp(10), dp(12), dp(10), dp(12))
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    setMargins(dp(4), 0, dp(4), 0)
                }
            }
            attachSpringTouch(pill) {
                if (selectedSubject != subj) {
                    selectedSubject = subj
                    renderCurrentTab(animateTransition = false, keepScroll = true)
                }
            }
            subjRow.addView(pill)
        }
        syllabusCard.addView(subjRow)

        val chapters = jeeSyllabus[selectedSubject] ?: emptyList()
        val allErrors = getJsonArray("error_book_list")
        val now = System.currentTimeMillis()
        val fifteenDaysMs = 15L * 24 * 60 * 60 * 1000L

        for ((idx, chapter) in chapters.withIndex()) {
            val statusKey = "status_${selectedSubject}_$chapter"
            val timeKey = "time_${selectedSubject}_$chapter"
            val status = prefs.getInt(statusKey, 0)
            val completedAt = prefs.getLong(timeKey, 0L)
            val isPyqDue = (status == 2 && completedAt > 0L && (now - completedAt) >= fifteenDaysMs)

            var errorCount = 0
            for (i in 0 until allErrors.length()) {
                val err = allErrors.getJSONObject(i)
                if (err.getString("subject") == selectedSubject && err.getString("chapter") == chapter) {
                    errorCount++
                }
            }

            val (cardFill, cardBorder) = when {
                isPyqDue -> Pair("#2A1219", "#F43F5E")
                status == 2 -> Pair("#0C241D", "#10B981")
                status == 1 -> Pair("#261E10", "#F59E0B")
                else -> Pair("#172033", "#26334D")
            }

            val chapBox = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                background = roundedRippleBg(cardFill, 16f, cardBorder)
                setPadding(dp(16), dp(16), dp(16), dp(16))
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(12) }
            }

            // Top Row: Chapter Number + Name
            val titleView = TextView(this).apply {
                text = String.format(Locale.US, "%02d. %s", idx + 1, chapter)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                setTextColor(Color.WHITE)
                setTypeface(null, Typeface.BOLD)
            }

            val statusSubtitle = TextView(this).apply {
                val daysAgo = if (completedAt > 0L) ((now - completedAt) / (1000L * 60 * 60 * 24)) else 0L
                text = when {
                    isPyqDue -> "⚠️ Completed $daysAgo days ago • 15+ Days Passed: Revise with PYQs!"
                    status == 2 -> "✅ Completed ($daysAgo days ago) • 15-Day PYQ Timer Running"
                    status == 1 -> "🌗 Partially Completed (50%)"
                    else -> "⭕ Not Started Yet"
                }
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                setTextColor(
                    when {
                        isPyqDue -> Color.parseColor("#FDA4AF")
                        status == 2 -> Color.parseColor("#34D399")
                        status == 1 -> Color.parseColor("#FBBF24")
                        else -> Color.parseColor("#94A3B8")
                    }
                )
                setPadding(0, dp(4), 0, dp(12))
            }

            // Interactive 3-State Segmented Selector Row
            val stateRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            }

            val st0Btn = createSegmentPill("Pending", status == 0, "#475569") {
                prefs.edit().putInt(statusKey, 0).putLong(timeKey, 0L).apply()
                renderCurrentTab(animateTransition = false, keepScroll = true)
            }
            val st1Btn = createSegmentPill("Partial", status == 1, "#D97706") {
                prefs.edit().putInt(statusKey, 1).putLong(timeKey, 0L).apply()
                renderCurrentTab(animateTransition = false, keepScroll = true)
            }
            val st2Btn = createSegmentPill("Completed", status == 2, "#059669") {
                prefs.edit().putInt(statusKey, 2).putLong(timeKey, System.currentTimeMillis()).apply()
                renderCurrentTab(animateTransition = false, keepScroll = true)
            }

            stateRow.addView(st0Btn)
            stateRow.addView(st1Btn)
            stateRow.addView(st2Btn)

            // Bottom Action Row: Mistakes Vault Button + 15-Day PYQ Controls
            val bottomRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(10) }
            }

            val mistakesBtn = createActionChip(
                "📸 Mistakes ($errorCount)",
                if (errorCount > 0) "#E11D48" else "#26334D"
            ) {
                showChapterMistakesModal(selectedSubject, chapter)
            }.apply {
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    rightMargin = dp(6)
                }
            }
            bottomRow.addView(mistakesBtn)

            if (isPyqDue) {
                val pyqResetBtn = createActionChip("✅ Mark PYQs Done", "#10B981") {
                    prefs.edit().putLong(timeKey, System.currentTimeMillis()).apply()
                    Toast.makeText(this, "15-Day PYQ timer reset for $chapter!", Toast.LENGTH_SHORT).show()
                    renderCurrentTab(animateTransition = false, keepScroll = true)
                }
                bottomRow.addView(pyqResetBtn)
            } else {
                val sim15dBtn = createActionChip("⚡ Test 15d Alert", "#334155") {
                    val sixteenDaysAgo = System.currentTimeMillis() - (16L * 24 * 60 * 60 * 1000L)
                    prefs.edit().putInt(statusKey, 2).putLong(timeKey, sixteenDaysAgo).apply()
                    check15DayPyqRevisions(triggerNotification = true)
                    renderCurrentTab(animateTransition = false, keepScroll = true)
                }
                bottomRow.addView(sim15dBtn)
            }

            chapBox.addView(titleView)
            chapBox.addView(statusSubtitle)
            chapBox.addView(stateRow)
            chapBox.addView(bottomRow)
            syllabusCard.addView(chapBox)
        }

        contentContainer.addView(syllabusCard)
    }

    private fun createSegmentPill(label: String, selected: Boolean, activeHex: String, onClick: () -> Unit): TextView {
        return TextView(this).apply {
            text = if (selected) "● $label" else label
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTypeface(null, Typeface.BOLD)
            gravity = Gravity.CENTER
            setTextColor(if (selected) Color.WHITE else Color.parseColor("#94A3B8"))
            background = roundedRippleBg(
                if (selected) activeHex else "#0F1626",
                10f,
                if (selected) "#FFFFFF" else "#1E293B",
                if (selected) 1.5f else 1f
            )
            setPadding(dp(8), dp(9), dp(8), dp(9))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                setMargins(dp(3), 0, dp(3), 0)
            }
            attachSpringTouch(this, onClick)
        }
    }

    private fun showUploadWorksheetModal() {
        val modalLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        val titleInput = createDarkEditText("Worksheet Title (e.g., Rotational Motion DPP #4)")
        var chosenSubj = "Physics"

        val subjRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(12) }
        }

        val subjPills = mutableListOf<TextView>()
        val subjects = listOf("Physics", "Chemistry", "Mathematics")
        for (s in subjects) {
            val pill = TextView(this).apply {
                text = s
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                setTypeface(null, Typeface.BOLD)
                gravity = Gravity.CENTER
                setPadding(dp(8), dp(10), dp(8), dp(10))
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    setMargins(dp(3), 0, dp(3), 0)
                }
            }
            subjPills.add(pill)
            subjRow.addView(pill)
        }

        fun refreshSubjPills() {
            for (i in subjects.indices) {
                val active = (subjects[i] == chosenSubj)
                subjPills[i].background = roundedRippleBg(if (active) "#4F46E5" else "#172033", 10f, "#334155")
                subjPills[i].setTextColor(Color.WHITE)
            }
        }
        for (i in subjects.indices) {
            attachSpringTouch(subjPills[i]) {
                chosenSubj = subjects[i]
                refreshSubjPills()
            }
        }
        refreshSubjPills()

        modalLayout.addView(titleInput)
        modalLayout.addView(subjRow)

        showCustomDarkModal(
            title = "📄 Upload Worksheet (PDF / Image)",
            subtitle = "Choose subject and pick your file from storage",
            bodyView = modalLayout,
            confirmText = "Choose File",
            onConfirm = {
                pendingWorksheetTitle = titleInput.text.toString().trim().ifEmpty { "Worksheet #${System.currentTimeMillis() % 1000}" }
                pendingWorksheetSubject = chosenSubj
                worksheetPicker.launch(arrayOf("application/pdf", "image/*"))
            }
        )
    }

    private fun saveWorksheet(title: String, subject: String, uri: String) {
        val arr = getJsonArray("worksheets_list")
        arr.put(JSONObject().apply {
            put("title", title)
            put("subject", subject)
            put("uri", uri)
            put("done", false)
        })
        saveJsonArray("worksheets_list", arr)
    }

    private fun calculateSubjectStats(): List<SubjectAnalyticsView.SubjectStat> {
        val colors = mapOf(
            "Physics" to Pair(Color.parseColor("#38BDF8"), Color.parseColor("#0284C7")),
            "Chemistry" to Pair(Color.parseColor("#10B981"), Color.parseColor("#059669")),
            "Mathematics" to Pair(Color.parseColor("#A855F7"), Color.parseColor("#7E22CE"))
        )
        return jeeSyllabus.map { (subj, chapList) ->
            var comp = 0
            var part = 0
            for (c in chapList) {
                when (prefs.getInt("status_${subj}_$c", 0)) {
                    2 -> comp++
                    1 -> part++
                }
            }
            val pair = colors[subj] ?: Pair(Color.WHITE, Color.GRAY)
            SubjectAnalyticsView.SubjectStat(subj, comp, part, chapList.size, pair.first, pair.second)
        }
    }

    // ========================================================================
    // TAB 1: ERROR BOOK (CHAPTER-LINKED PHOTO MISTAKE VAULT)
    // ========================================================================
    private fun renderErrorBookTab() {
        val formCard = createSurfaceCard()
        formCard.addView(createBadgeLabel("📸 MISTAKE VAULT", "#F43F5E"))
        formCard.addView(createCardTitle("Add Question to Error Book"))
        formCard.addView(createCardSub("Select the Subject and Chapter below, write the concept trap, and attach a photo of the mistake."))

        var chosenSubj = selectedSubject
        val chaptersForSubj = (jeeSyllabus[chosenSubj] ?: emptyList()).toMutableList()
        var chosenChap = chaptersForSubj.firstOrNull() ?: ""

        // Subject Pill Row
        val subjRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(8); bottomMargin = dp(12) }
        }

        val chapSelectBtn = createSecondaryButton("📘 Chapter: $chosenChap (Tap to change)") {}

        val subjects = listOf("Physics", "Chemistry", "Mathematics")
        val subjButtons = mutableListOf<TextView>()
        for (s in subjects) {
            val b = TextView(this).apply {
                text = s
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
                setTypeface(null, Typeface.BOLD)
                gravity = Gravity.CENTER
                setPadding(dp(8), dp(11), dp(8), dp(11))
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    setMargins(dp(4), 0, dp(4), 0)
                }
            }
            subjButtons.add(b)
            subjRow.addView(b)
        }

        fun updateSubjSelectionUI() {
            for (i in subjects.indices) {
                val active = (subjects[i] == chosenSubj)
                subjButtons[i].background = roundedRippleBg(
                    if (active) "#E11D48" else "#172033",
                    12f,
                    if (active) "#FB7185" else "#26334D"
                )
                subjButtons[i].setTextColor(Color.WHITE)
            }
            val list = jeeSyllabus[chosenSubj] ?: emptyList()
            chosenChap = list.firstOrNull() ?: ""
            chapSelectBtn.text = "📘 Chapter: $chosenChap  ▾"
        }

        for (i in subjects.indices) {
            attachSpringTouch(subjButtons[i]) {
                chosenSubj = subjects[i]
                updateSubjSelectionUI()
            }
        }
        updateSubjSelectionUI()

        attachSpringTouch(chapSelectBtn) {
            val list = jeeSyllabus[chosenSubj] ?: emptyList()
            showChapterPickerModal(chosenSubj, list) { picked ->
                chosenChap = picked
                chapSelectBtn.text = "📘 Chapter: $chosenChap  ▾"
            }
        }

        val noteInput = createDarkEditText("What was the mistake / trap? (e.g., Sign error in work done / Carbocation shift)")

        val uploadBtn = createPrimaryButton("📸 Pick Mistake Photo & Save to Chapter", "#E11D48") {
            pendingErrorSubject = chosenSubj
            pendingErrorChapter = chosenChap
            pendingErrorNote = noteInput.text.toString().trim().ifEmpty { "Important Concept Trap" }
            errorImagePicker.launch(arrayOf("image/*"))
        }

        formCard.addView(subjRow)
        formCard.addView(chapSelectBtn)
        formCard.addView(noteInput)
        formCard.addView(uploadBtn)
        contentContainer.addView(formCard)

        // All Recorded Mistakes List
        val vaultCard = createSurfaceCard()
        val errors = getJsonArray("error_book_list")
        vaultCard.addView(createBadgeLabel("🗂️ RECORDED MISTAKES (${errors.length()})", "#38BDF8"))
        vaultCard.addView(createCardTitle("Your Chapter Mistake Archive"))

        if (errors.length() == 0) {
            vaultCard.addView(createEmptyStateBox("Your Error Book is empty. Record mistakes from your mock tests and DPPs above."))
        } else {
            for (i in errors.length() - 1 downTo 0) {
                val err = errors.getJSONObject(i)
                vaultCard.addView(buildErrorItemView(err, i, errors))
            }
        }
        contentContainer.addView(vaultCard)
    }

    private fun showChapterPickerModal(subject: String, chapters: List<String>, onPick: (String) -> Unit) {
        val dialog = Dialog(this)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        val wrapper = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = gradientCardBg("#151D30", "#0C111D", 22f, "#334155")
            setPadding(dp(20), dp(20), dp(20), dp(20))
        }

        wrapper.addView(createCardTitle("Select $subject Chapter"))

        val scroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(380)
            ).apply { topMargin = dp(12) }
        }
        val listCol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        for (c in chapters) {
            val item = TextView(this).apply {
                text = c
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                setTextColor(Color.WHITE)
                background = roundedRippleBg("#172033", 12f, "#26334D")
                setPadding(dp(14), dp(13), dp(14), dp(13))
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(8) }
            }
            attachSpringTouch(item) {
                onPick(c)
                dialog.dismiss()
            }
            listCol.addView(item)
        }
        scroll.addView(listCol)
        wrapper.addView(scroll)

        dialog.setContentView(wrapper)
        dialog.window?.setLayout(
            (resources.displayMetrics.widthPixels * 0.92).roundToInt(),
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        dialog.show()
    }

    private fun saveErrorBookEntry(subject: String, chapter: String, note: String, uri: String) {
        val arr = getJsonArray("error_book_list")
        arr.put(JSONObject().apply {
            put("subject", subject)
            put("chapter", chapter)
            put("note", note)
            put("uri", uri)
            put("date", SimpleDateFormat("dd MMM yyyy", Locale.US).format(Date()))
        })
        saveJsonArray("error_book_list", arr)
    }

    private fun showChapterMistakesModal(subject: String, chapter: String) {
        val allErrors = getJsonArray("error_book_list")
        val filtered = JSONArray()
        for (i in 0 until allErrors.length()) {
            val obj = allErrors.getJSONObject(i)
            if (obj.getString("subject") == subject && obj.getString("chapter") == chapter) {
                filtered.put(obj)
            }
        }

        if (filtered.length() == 0) {
            Toast.makeText(this, "No mistakes recorded for $chapter yet!", Toast.LENGTH_SHORT).show()
            return
        }

        val scroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(440))
        }
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        for (i in 0 until filtered.length()) {
            container.addView(buildErrorItemView(filtered.getJSONObject(i), -1, null))
        }
        scroll.addView(container)

        showCustomDarkModal(
            title = "📸 $chapter",
            subtitle = "${filtered.length()} recorded mistake(s) in $subject",
            bodyView = scroll,
            confirmText = "Done",
            onConfirm = {}
        )
    }

    private fun buildErrorItemView(err: JSONObject, indexInAll: Int, fullArray: JSONArray?): View {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = roundedRippleBg("#172033", 16f, "#26334D")
            setPadding(dp(16), dp(16), dp(16), dp(16))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(12) }
        }

        val badge = TextView(this).apply {
            text = "${err.getString("subject").uppercase()}  •  ${err.getString("chapter")}  •  ${err.optString("date", "")}"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setTextColor(Color.parseColor("#FB7185"))
            setTypeface(null, Typeface.BOLD)
        }

        val noteText = TextView(this).apply {
            text = "Trap: ${err.getString("note")}"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14.5f)
            setTextColor(Color.WHITE)
            setTypeface(null, Typeface.BOLD)
            setPadding(0, dp(6), 0, dp(12))
        }

        val uriStr = err.getString("uri")
        val imgView = ImageView(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(190))
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = roundedRippleBg("#0B0F19", 12f, "#1E293B")
            clipToOutline = true
        }
        loadAsyncThumbnail(uriStr, imgView)
        attachSpringTouch(imgView) { openUriDocument(uriStr) }

        box.addView(badge)
        box.addView(noteText)
        box.addView(imgView)

        if (indexInAll >= 0 && fullArray != null) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.END
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(10) }
            }
            row.addView(createActionChip("🔍 View Full Image", "#2563EB") {
                openUriDocument(uriStr)
            })
            row.addView(createActionChip("🗑 Delete", "#334155") {
                val updated = JSONArray()
                for (j in 0 until fullArray.length()) {
                    if (j != indexInAll) updated.put(fullArray.getJSONObject(j))
                }
                saveJsonArray("error_book_list", updated)
                renderCurrentTab(animateTransition = false, keepScroll = true)
            })
            box.addView(row)
        }
        return box
    }

    private fun loadAsyncThumbnail(uriStr: String, imageView: ImageView) {
        imageView.tag = uriStr
        val cached = bitmapCache.get(uriStr)
        if (cached != null) {
            imageView.setImageBitmap(cached)
            return
        }
        imageExecutor.execute {
            try {
                val uri = Uri.parse(uriStr)
                val opts = BitmapFactory.Options().apply { inSampleSize = 4 }
                contentResolver.openInputStream(uri)?.use { stream ->
                    val bmp = BitmapFactory.decodeStream(stream, null, opts)
                    if (bmp != null) {
                        bitmapCache.put(uriStr, bmp)
                        runOnUiThread {
                            if (imageView.tag == uriStr) {
                                imageView.setImageBitmap(bmp)
                            }
                        }
                    }
                }
            } catch (_: Exception) {}
        }
    }

    // ========================================================================
    // TAB 2: FOCUS MODE (1-5 HOURS) + ADMIN SHIELD & ADULT WEB BLOCKER
    // ========================================================================
    private fun renderFocusAndBlockerTab() {
        // 1. Administrative Shield Setup Card
        val permCard = createSurfaceCard()
        permCard.addView(createBadgeLabel("🛡️ ADMINISTRATIVE ACCESS", "#38BDF8"))
        permCard.addView(createCardTitle("Protection & Blocker Status"))
        permCard.addView(createCardSub("Activate both shields below to block YouTube, Instagram, and adult websites across browsers."))

        val accEnabled = isAccessibilityServiceEnabled()
        val adminEnabled = prefs.getBoolean("admin_lock_active", false)

        val accBtn = createPrimaryButton(
            if (accEnabled) "✅ App & Website Accessibility Shield: ACTIVE" else "⚠️ Enable App & Website Shield (Tap Here)",
            if (accEnabled) "#059669" else "#E11D48"
        ) {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            Toast.makeText(this, "Turn ON 'JEE Focus Shield' in Accessibility", Toast.LENGTH_LONG).show()
        }

        val adminBtn = createPrimaryButton(
            if (adminEnabled) "✅ Administrative Lock Protection: ACTIVE" else "⚡ Activate Administrative Lock Protection",
            if (adminEnabled) "#059669" else "#D97706"
        ) {
            prefs.edit().putBoolean("admin_lock_active", !adminEnabled).apply()
            Toast.makeText(
                this,
                if (!adminEnabled) "Administrative Lock Protection Activated!" else "Admin Lock Paused",
                Toast.LENGTH_SHORT
            ).show()
            renderCurrentTab(animateTransition = false, keepScroll = true)
        }

        permCard.addView(accBtn)
        permCard.addView(adminBtn)
        contentContainer.addView(permCard)

        // 2. Hardcore Focus Lockdown (1 to 5 Hours)
        val focusCard = createSurfaceCard()
        focusCard.addView(createBadgeLabel("🔒 DEEP WORK LOCKDOWN (1H – 5H)", "#F43F5E"))
        focusCard.addView(createCardTitle("Hardcore JEE Focus Mode"))
        focusCard.addView(createCardSub("Blocks every other app on your phone for 1 to 5 hours. Sending and receiving Phone Calls remains 100% allowed."))

        val now = System.currentTimeMillis()
        val focusEndMs = prefs.getLong("focus_end_ms", 0L)
        val isFocusActive = focusEndMs > now

        if (isFocusActive) {
            val minsLeft = ((focusEndMs - now) / 60000L) + 1
            val activeBox = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                background = roundedRippleBg("#2A1219", 14f, "#F43F5E")
                setPadding(dp(16), dp(16), dp(16), dp(16))
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(8); bottomMargin = dp(8) }
            }
            activeBox.addView(TextView(this).apply {
                text = "🔥 LOCKDOWN RUNNING • $minsLeft MINS REMAINING"
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                setTextColor(Color.parseColor("#FDA4AF"))
                setTypeface(null, Typeface.BOLD)
            })
            activeBox.addView(TextView(this).apply {
                text = "All distracting apps and social/adult sites are blocked. Phone Dialer is unlocked below."
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
                setTextColor(Color.WHITE)
                setPadding(0, dp(4), 0, 0)
            })
            focusCard.addView(activeBox)
        } else {
            val hoursRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(10); bottomMargin = dp(8) }
            }

            for (hr in 1..5) {
                val pill = TextView(this).apply {
                    text = "${hr}h"
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                    setTypeface(null, Typeface.BOLD)
                    setTextColor(Color.WHITE)
                    gravity = Gravity.CENTER
                    background = roundedRippleBg("#4F46E5", 14f, "#818CF8")
                    setPadding(dp(8), dp(14), dp(8), dp(14))
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                        setMargins(dp(4), 0, dp(4), 0)
                    }
                }
                attachSpringTouch(pill) {
                    val endMs = System.currentTimeMillis() + (hr * 60L * 60L * 1000L)
                    prefs.edit().putLong("focus_end_ms", endMs).apply()
                    Toast.makeText(this, "$hr-Hour Focus Lockdown Started!", Toast.LENGTH_LONG).show()
                    updateCountdownHeader()
                    renderCurrentTab(animateTransition = false, keepScroll = true)
                }
                hoursRow.addView(pill)
            }
            focusCard.addView(hoursRow)
        }

        // Phone Calls / Dialer Button
        focusCard.addView(createPrimaryButton("📞 Open Phone Dialer (Send / Receive Calls)", "#10B981") {
            try {
                startActivity(Intent(Intent.ACTION_DIAL))
            } catch (_: Exception) {
                Toast.makeText(this, "Dialer unavailable", Toast.LENGTH_SHORT).show()
            }
        })

        if (isFocusActive) {
            focusCard.addView(createSecondaryButton("🔓 Emergency Stop Focus Mode") {
                showCustomDarkModal(
                    title = "Break Focus Lockdown Early?",
                    subtitle = "Stay disciplined for your January JEE Main target! Do you really need to unlock early?",
                    bodyView = TextView(this).apply {
                        text = "Tip: Use the green Phone Dialer button if you just need to make a phone call."
                        setTextColor(Color.parseColor("#94A3B8"))
                        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f)
                    },
                    confirmText = "Unlock Now",
                    onConfirm = {
                        prefs.edit().putLong("focus_end_ms", 0L).apply()
                        updateCountdownHeader()
                        renderCurrentTab(animateTransition = false, keepScroll = true)
                    }
                )
            })
        }
        contentContainer.addView(focusCard)

        // 3. 24/7 Website & Adult Content Blocker
        val webCard = createSurfaceCard()
        webCard.addView(createBadgeLabel("🌐 24/7 WEB & ADULT FILTER", "#A78BFA"))
        webCard.addView(createCardTitle("YouTube, Instagram & Adult Blocker"))
        val alwaysShield = prefs.getBoolean("always_shield_enabled", true)
        webCard.addView(createCardSub("Automatically closes YouTube, Instagram, and adult websites inside Chrome, Brave, Firefox, and Opera."))

        webCard.addView(createPrimaryButton(
            if (alwaysShield) "✅ 24/7 Social & Adult Website Shield: ON" else "Enable 24/7 Social & Adult Shield",
            if (alwaysShield) "#10B981" else "#475569"
        ) {
            prefs.edit().putBoolean("always_shield_enabled", !alwaysShield).apply()
            renderCurrentTab(animateTransition = false, keepScroll = true)
        })

        val customSitesInput = createDarkEditText("Custom blocked domains (comma-separated)").apply {
            setText(prefs.getString("custom_blocked_sites", "netflix.com, twitch.tv, discord.com, reddit.com"))
        }

        webCard.addView(customSitesInput)
        webCard.addView(createSecondaryButton("💾 Save Custom Blocked Domains") {
            prefs.edit().putString("custom_blocked_sites", customSitesInput.text.toString()).apply()
            Toast.makeText(this, "Blocked domain list saved!", Toast.LENGTH_SHORT).show()
        })
        contentContainer.addView(webCard)
    }

    // ========================================================================
    // TAB 3: 15-DAY PYQ RADAR + DAILY TIMETABLE + MOCK TEST SCHEDULER
    // ========================================================================
    private fun renderTimetableAndPyqTab() {
        // 1. 15-Day PYQ Revision Radar
        val pyqCard = createSurfaceCard()
        pyqCard.addView(createBadgeLabel("🔔 SPACED REPETITION RADAR", "#F43F5E"))
        pyqCard.addView(createCardTitle("15-Day PYQ Revision Queue"))
        pyqCard.addView(createCardSub("Chapters completed more than 15 days ago automatically trigger an alert here so you revise them with PYQs."))

        val dueChapters = getChaptersDueForPyqRevision()
        if (dueChapters.isEmpty()) {
            pyqCard.addView(createEmptyStateBox("✅ Zero chapters overdue! All completed chapters were revised within the last 15 days."))
        } else {
            for ((subj, chap, days) in dueChapters) {
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    background = roundedRippleBg("#2A1219", 14f, "#F43F5E")
                    setPadding(dp(14), dp(14), dp(14), dp(14))
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply { topMargin = dp(10) }
                }

                val tv = TextView(this).apply {
                    text = "⚠️ [$subj] $chap\nCompleted $days days ago • Solve 25 PYQs today"
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                    setTextColor(Color.parseColor("#FECDD3"))
                    setTypeface(null, Typeface.BOLD)
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                }

                val doneBtn = createActionChip("PYQs Done", "#10B981") {
                    prefs.edit().putLong("time_${subj}_$chap", System.currentTimeMillis()).apply()
                    renderCurrentTab(animateTransition = false, keepScroll = true)
                }

                row.addView(tv)
                row.addView(doneBtn)
                pyqCard.addView(row)
            }
        }
        contentContainer.addView(pyqCard)

        // 2. Daily Timetable & Alarm Reminders
        val ttCard = createSurfaceCard()
        ttCard.addView(createBadgeLabel("⏰ DAILY SCHEDULE", "#38BDF8"))
        ttCard.addView(createCardTitle("Study Timetable & Reminders"))
        ttCard.addView(createCardSub("Upload your full timetable chart or add daily time-slotted study blocks with automatic alarm notifications."))

        val uploadedUri = prefs.getString("uploaded_timetable_uri", null)
        ttCard.addView(createSecondaryButton(
            if (uploadedUri != null) "📋 Replace Uploaded Timetable Image/PDF" else "📋 Upload Timetable Chart (Image / PDF)"
        ) {
            timetableFilePicker.launch(arrayOf("image/*", "application/pdf"))
        })

        if (uploadedUri != null) {
            ttCard.addView(createPrimaryButton("👁️ View Uploaded Timetable Chart", "#2563EB") {
                openUriDocument(uploadedUri)
            })
        }

        ttCard.addView(createPrimaryButton("＋ Add Daily Time Slot & Alarm", "#4F46E5") {
            promptAddTimetableSlot()
        })

        val slots = getJsonArray("timetable_slots")
        if (slots.length() == 0) {
            ttCard.addView(createEmptyStateBox("No daily alarm slots added yet. Tap above to schedule your study blocks."))
        } else {
            for (i in 0 until slots.length()) {
                val slot = slots.getJSONObject(i)
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    background = roundedRippleBg("#172033", 14f, "#26334D")
                    setPadding(dp(14), dp(14), dp(14), dp(14))
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply { topMargin = dp(10) }
                }

                val tv = TextView(this).apply {
                    text = "⏰ ${slot.getString("time")}   •   ${slot.getString("task")}"
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                    setTextColor(Color.WHITE)
                    setTypeface(null, Typeface.BOLD)
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                }

                val delBtn = createActionChip("Remove", "#334155") {
                    val updated = JSONArray()
                    for (j in 0 until slots.length()) {
                        if (j != i) updated.put(slots.getJSONObject(j))
                    }
                    saveJsonArray("timetable_slots", updated)
                    renderCurrentTab(animateTransition = false, keepScroll = true)
                }

                row.addView(tv)
                row.addView(delBtn)
                ttCard.addView(row)
            }
        }
        contentContainer.addView(ttCard)

        // 3. Mock Test Day Scheduler
        val mockCard = createSurfaceCard()
        mockCard.addView(createBadgeLabel("🏆 TEST SERIES PLANNER", "#10B981"))
        mockCard.addView(createCardTitle("Upcoming Mock Test Days"))
        mockCard.addView(createCardSub("Schedule your coaching or full-syllabus mock test dates for automatic morning alerts."))

        mockCard.addView(createPrimaryButton("＋ Schedule Mock Test Day", "#10B981") {
            promptAddMockTestDay()
        })

        val mocks = getJsonArray("mock_test_days")
        if (mocks.length() == 0) {
            mockCard.addView(createEmptyStateBox("No upcoming mock test dates scheduled."))
        } else {
            for (i in 0 until mocks.length()) {
                val m = mocks.getJSONObject(i)
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    background = roundedRippleBg("#172033", 14f, "#26334D")
                    setPadding(dp(14), dp(14), dp(14), dp(14))
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply { topMargin = dp(10) }
                }

                val tv = TextView(this).apply {
                    text = "📝 ${m.getString("date")}  •  ${m.getString("title")}"
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                    setTextColor(Color.parseColor("#38BDF8"))
                    setTypeface(null, Typeface.BOLD)
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                }

                val delBtn = createActionChip("Done", "#334155") {
                    val updated = JSONArray()
                    for (j in 0 until mocks.length()) {
                        if (j != i) updated.put(mocks.getJSONObject(j))
                    }
                    saveJsonArray("mock_test_days", updated)
                    renderCurrentTab(animateTransition = false, keepScroll = true)
                }

                row.addView(tv)
                row.addView(delBtn)
                mockCard.addView(row)
            }
        }
        contentContainer.addView(mockCard)
    }

    private fun promptAddTimetableSlot() {
        val input = createDarkEditText("Study Block (e.g., Organic Chemistry Named Reactions / PYQs)")
        showCustomDarkModal(
            title = "⏰ Add Daily Timetable Slot",
            subtitle = "Enter the task name, then pick the reminder time",
            bodyView = input,
            confirmText = "Pick Time",
            onConfirm = {
                val taskName = input.text.toString().trim().ifEmpty { "JEE Study Block" }
                val cal = Calendar.getInstance()
                TimePickerDialog(
                    this,
                    { _, hourOfDay, minute ->
                        val timeStr = String.format(Locale.US, "%02d:%02d", hourOfDay, minute)
                        val arr = getJsonArray("timetable_slots")
                        arr.put(JSONObject().apply {
                            put("time", timeStr)
                            put("task", taskName)
                        })
                        saveJsonArray("timetable_slots", arr)
                        scheduleAlarmReminder(hourOfDay, minute, "⏰ Study Slot: $taskName", "Scheduled for $timeStr — Lock in now!")
                        renderCurrentTab(animateTransition = false, keepScroll = true)
                    },
                    cal.get(Calendar.HOUR_OF_DAY),
                    cal.get(Calendar.MINUTE),
                    true
                ).show()
            }
        )
    }

    private fun promptAddMockTestDay() {
        val input = createDarkEditText("Mock Test Name (e.g., Allen Full Syllabus Major Test #3)")
        showCustomDarkModal(
            title = "🏆 Schedule Mock Test Day",
            subtitle = "Enter test title, then select the exam date",
            bodyView = input,
            confirmText = "Pick Date",
            onConfirm = {
                val title = input.text.toString().trim().ifEmpty { "JEE Main Mock Test" }
                val cal = Calendar.getInstance()
                DatePickerDialog(
                    this,
                    { _, year, month, day ->
                        val pickedCal = Calendar.getInstance().apply {
                            set(year, month, day, 8, 0, 0)
                        }
                        val dateStr = SimpleDateFormat("dd MMM yyyy", Locale.US).format(pickedCal.time)
                        val arr = getJsonArray("mock_test_days")
                        arr.put(JSONObject().apply {
                            put("date", dateStr)
                            put("title", title)
                        })
                        saveJsonArray("mock_test_days", arr)
                        scheduleExactDateAlarm(pickedCal.timeInMillis, "🏆 MOCK TEST DAY TODAY!", "$title is scheduled today. Give it your best!")
                        renderCurrentTab(animateTransition = false, keepScroll = true)
                    },
                    cal.get(Calendar.YEAR),
                    cal.get(Calendar.MONTH),
                    cal.get(Calendar.DAY_OF_MONTH)
                ).show()
            }
        )
    }

    // ========================================================================
    // 15-DAY PYQ CHECKER & ALARM SCHEDULER
    // ========================================================================
    private fun getChaptersDueForPyqRevision(): List<Triple<String, String, Long>> {
        val result = mutableListOf<Triple<String, String, Long>>()
        val now = System.currentTimeMillis()
        val fifteenDaysMs = 15L * 24 * 60 * 60 * 1000L

        for ((subj, chaps) in jeeSyllabus) {
            for (c in chaps) {
                val status = prefs.getInt("status_${subj}_$c", 0)
                val completedAt = prefs.getLong("time_${subj}_$c", 0L)
                if (status == 2 && completedAt > 0L && (now - completedAt) >= fifteenDaysMs) {
                    val days = (now - completedAt) / (1000L * 60 * 60 * 24)
                    result.add(Triple(subj, c, days))
                }
            }
        }
        return result
    }

    private fun check15DayPyqRevisions(triggerNotification: Boolean) {
        val due = getChaptersDueForPyqRevision()
        if (due.isNotEmpty() && triggerNotification) {
            val first = due[0]
            val intent = Intent(this, ReminderReceiver::class.java).apply {
                putExtra("TITLE", "⚠️ 15-Day PYQ Revision Due (${due.size} Chapters)")
                putExtra("MESSAGE", "${first.first}: ${first.second} was completed ${first.third} days ago! Revise it with PYQs today.")
                putExtra("NOTIF_ID", 9915)
            }
            sendBroadcast(intent)
        }
    }

    private fun scheduleAlarmReminder(hour: Int, minute: Int, title: String, message: String) {
        val cal = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            if (timeInMillis <= System.currentTimeMillis()) {
                add(Calendar.DAY_OF_YEAR, 1)
            }
        }
        scheduleExactDateAlarm(cal.timeInMillis, title, message)
    }

    private fun scheduleExactDateAlarm(triggerAtMillis: Long, title: String, message: String) {
        val am = getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val reqCode = (triggerAtMillis % 100000).toInt()
        val intent = Intent(this, ReminderReceiver::class.java).apply {
            putExtra("TITLE", title)
            putExtra("MESSAGE", message)
            putExtra("NOTIF_ID", reqCode)
        }
        val pi = PendingIntent.getBroadcast(
            this, reqCode, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        try {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pi)
        } catch (_: SecurityException) {
            am.set(AlarmManager.RTC_WAKEUP, triggerAtMillis, pi)
        }
    }

    // ========================================================================
    // CUSTOM DARK MODAL DIALOG & REUSABLE DESIGN SYSTEM COMPONENTS
    // ========================================================================
    private fun showCustomDarkModal(
        title: String,
        subtitle: String,
        bodyView: View,
        confirmText: String,
        onConfirm: () -> Unit
    ) {
        val dialog = Dialog(this)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = gradientCardBg("#161F36", "#0D1322", 24f, "#334155")
            setPadding(dp(22), dp(22), dp(22), dp(20))
        }

        root.addView(createCardTitle(title))
        root.addView(createCardSub(subtitle))
        root.addView(bodyView)

        val btnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(18) }
        }

        val cancelBtn = createActionChip("Cancel", "#334155") {
            dialog.dismiss()
        }
        val okBtn = createActionChip(confirmText, "#4F46E5") {
            dialog.dismiss()
            onConfirm()
        }

        btnRow.addView(cancelBtn)
        btnRow.addView(okBtn)
        root.addView(btnRow)

        dialog.setContentView(root)
        dialog.window?.setLayout(
            (resources.displayMetrics.widthPixels * 0.92).roundToInt(),
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        dialog.show()

        root.alpha = 0f
        root.scaleX = 0.94f
        root.scaleY = 0.94f
        root.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(180)
            .setInterpolator(DecelerateInterpolator()).start()
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        val expectedComponentName = ComponentName(this, FocusAccessibilityService::class.java)
        val enabledServicesSetting = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        val colonSplitter = TextUtils.SimpleStringSplitter(':')
        colonSplitter.setString(enabledServicesSetting)
        while (colonSplitter.hasNext()) {
            val componentNameString = colonSplitter.next()
            val enabledComponent = ComponentName.unflattenFromString(componentNameString)
            if (enabledComponent != null && enabledComponent == expectedComponentName) return true
        }
        return false
    }

    private fun openUriDocument(uriStr: String) {
        try {
            val uri = Uri.parse(uriStr)
            val mime = contentResolver.getType(uri) ?: "*/*"
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, mime)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(intent)
        } catch (_: Exception) {
            Toast.makeText(this, "Unable to open file. Verify it still exists on your device.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun getJsonArray(key: String): JSONArray {
        val raw = prefs.getString(key, null) ?: return JSONArray()
        return try { JSONArray(raw) } catch (_: Exception) { JSONArray() }
    }

    private fun saveJsonArray(key: String, arr: JSONArray) {
        prefs.edit().putString(key, arr.toString()).apply()
    }

    private fun createSurfaceCard(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = roundedRippleBg("#111726", 22f, "#1E293B")
            setPadding(dp(20), dp(20), dp(20), dp(20))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(18) }
        }
    }

    private fun createBadgeLabel(textStr: String, colorHex: String): TextView {
        return TextView(this).apply {
            text = textStr
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setTextColor(Color.parseColor(colorHex))
            setTypeface(null, Typeface.BOLD)
            letterSpacing = 0.06f
            setPadding(0, 0, 0, dp(4))
        }
    }

    private fun createCardTitle(textStr: String): TextView {
        return TextView(this).apply {
            text = textStr
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            setTextColor(Color.WHITE)
            setTypeface(null, Typeface.BOLD)
        }
    }

    private fun createCardSub(textStr: String): TextView {
        return TextView(this).apply {
            text = textStr
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTextColor(Color.parseColor("#94A3B8"))
            setLineSpacing(dpF(3f), 1f)
            setPadding(0, dp(4), 0, dp(12))
        }
    }

    private fun createEmptyStateBox(message: String): TextView {
        return TextView(this).apply {
            text = message
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTextColor(Color.parseColor("#94A3B8"))
            gravity = Gravity.CENTER
            background = roundedRippleBg("#0B101D", 14f, "#1E293B")
            setPadding(dp(16), dp(18), dp(16), dp(18))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(10) }
        }
    }

    private fun createDarkEditText(hintStr: String): EditText {
        return EditText(this).apply {
            hint = hintStr
            setHintTextColor(Color.parseColor("#64748B"))
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            background = roundedRippleBg("#0B101D", 14f, "#26334D")
            setPadding(dp(16), dp(14), dp(16), dp(14))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(10); bottomMargin = dp(6) }
        }
    }

    private fun createPrimaryButton(label: String, colorHex: String, onClick: () -> Unit): TextView {
        return TextView(this).apply {
            text = label
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            background = roundedRippleBg(colorHex, 14f, "#33FFFFFF")
            setPadding(dp(16), dp(15), dp(16), dp(15))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(10) }
            attachSpringTouch(this, onClick)
        }
    }

    private fun createSecondaryButton(label: String, onClick: () -> Unit): TextView {
        return TextView(this).apply {
            text = label
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f)
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#E2E8F0"))
            gravity = Gravity.CENTER
            background = roundedRippleBg("#172033", 14f, "#2E3C56")
            setPadding(dp(16), dp(13), dp(16), dp(13))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(10) }
            attachSpringTouch(this, onClick)
        }
    }

    private fun createActionChip(label: String, colorHex: String, onClick: () -> Unit): TextView {
        return TextView(this).apply {
            text = label
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            background = roundedRippleBg(colorHex, 10f)
            setPadding(dp(14), dp(9), dp(14), dp(9))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { leftMargin = dp(8) }
            attachSpringTouch(this, onClick)
        }
    }
}
