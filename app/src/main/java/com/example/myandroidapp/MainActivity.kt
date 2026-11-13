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
            "Set
