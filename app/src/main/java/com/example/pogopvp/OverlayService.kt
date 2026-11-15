package com.example.pogopvp

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.WindowManager
import android.widget.TextView
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import kotlin.math.abs

class OverlayService : Service() {
    private lateinit var wm: WindowManager
    private lateinit var tv: TextView
    private lateinit var lp: WindowManager.LayoutParams
    private var projection: MediaProjection? = null
    private var reader: ImageReader? = null
    private var display: VirtualDisplay? = null
    private val handler = Handler(Looper.getMainLooper())
    private val recognizer = TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build())
    private var busy = false
    private var league = 0
    private var opp: Mon? = null
    private var me: Mon? = null
    private var w = 0
    private var h = 0

    override fun onBind(i: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val code = intent?.getIntExtra("code", 0) ?: 0
        @Suppress("DEPRECATION")
        val data: Intent? = intent?.getParcelableExtra("data")
        if (data == null) { stopSelf(); return START_NOT_STICKY }
        startForegroundCompat()
        Dex.load(this)
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        projection = mpm.getMediaProjection(code, data).also {
            it.registerCallback(object : MediaProjection.Callback() { override fun onStop() { stopSelf() } }, handler)
        }
        setupCapture()
        setupOverlay()
        handler.postDelayed(loop, 1500)
        return START_NOT_STICKY
    }

    private fun startForegroundCompat() {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(NotificationChannel("pogo", "포고 PvP 상성", NotificationManager.IMPORTANCE_LOW))
        val n = Notification.Builder(this, "pogo").setContentTitle("포고 PvP 상성 실행 중")
            .setSmallIcon(android.R.drawable.ic_menu_view).build()
        if (Build.VERSION.SDK_INT >= 29) startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        else startForeground(1, n)
    }

    private fun setupCapture() {
        val dm = resources.displayMetrics
        if (Build.VERSION.SDK_INT >= 30) {
            val b = wm.maximumWindowMetrics.bounds; w = b.width(); h = b.height()
        } else { w = dm.widthPixels; h = dm.heightPixels }
        reader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2)
        display = projection!!.createVirtualDisplay("pogo-cap", w, h, dm.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, reader!!.surface, null, handler)
    }

    private fun setupOverlay() {
        tv = TextView(this).apply {
            setTextColor(Color.WHITE); setBackgroundColor(Color.argb(200, 20, 24, 36)); textSize = 13f
            setPadding(20, 14, 20, 14); text = Matchup.report(null, null, league)
        }
        lp = WindowManager.LayoutParams(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT)
        lp.gravity = Gravity.TOP or Gravity.START; lp.x = 20; lp.y = 200
        var dx = 0f; var dy = 0f; var sx = 0; var sy = 0; var moved = false
        tv.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> { dx = e.rawX; dy = e.rawY; sx = lp.x; sy = lp.y; moved = false }
                MotionEvent.ACTION_MOVE -> {
                    if (abs(e.rawX - dx) > 15 || abs(e.rawY - dy) > 15) moved = true
                    lp.x = sx + (e.rawX - dx).toInt(); lp.y = sy + (e.rawY - dy).toInt(); wm.updateViewLayout(tv, lp)
                }
                MotionEvent.ACTION_UP -> if (!moved) { league = (league + 1) % 3; refresh() }
            }
            true
        }
        wm.addView(tv, lp)
    }

    private fun refresh() { tv.text = Matchup.report(opp, me, league) }

    private val loop = object : Runnable {
        override fun run() { capture(); handler.postDelayed(this, 1500) }
    }

    private fun capture() {
        if (busy) return
        val img = reader?.acquireLatestImage() ?: return
        val bmp: Bitmap
        try {
            val p = img.planes[0]
            val pad = p.rowStride - p.pixelStride * w
            val full = Bitmap.createBitmap(w + pad / p.pixelStride, h, Bitmap.Config.ARGB_8888)
            full.copyPixelsFromBuffer(p.buffer)
            bmp = Bitmap.createBitmap(full, 0, 0, w, h)
        } finally { img.close() }
        busy = true
        recognizer.process(InputImage.fromBitmap(bmp, 0))
            .addOnSuccessListener { handleText(it) }
            .addOnCompleteListener { busy = false }
    }

    /** 위쪽에 있는 이름 = 상대, 아래쪽에 있는 이름 = 나 (포켓몬고 배틀 화면 배치 가정) */
    private fun handleText(t: Text) {
        val loc = IntArray(2); tv.getLocationOnScreen(loc)
        val own = Rect(loc[0], loc[1], loc[0] + tv.width, loc[1] + tv.height) // 이 앱의 창 글자를 다시 읽지 않도록 제외
        val found = LinkedHashMap<String, Pair<Mon, Int>>()
        for (b in t.textBlocks) for (l in b.lines) {
            val box = l.boundingBox ?: continue
            if (own.contains(box.centerX(), box.centerY())) continue
            val m = Dex.find(l.text) ?: continue
            found.putIfAbsent(m.id, m to box.centerY())
        }
        if (found.size >= 2) {
            val s = found.values.sortedBy { it.second }
            opp = s.first().first; me = s.last().first
            refresh()
        }
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        runCatching { wm.removeView(tv) }
        display?.release(); reader?.close(); projection?.stop(); recognizer.close()
        super.onDestroy()
    }
}
