package com.vanmypq.cameralophoc

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ContentValues
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.media.MediaActionSound
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.MediaStore
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.OrientationEventListener
import android.view.Surface
import android.view.View
import android.view.WindowManager
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class CameraService : LifecycleService() {

    companion object {
        const val ACTION_SHOT = "com.vanmypq.cameralophoc.SHOT"
        const val ACTION_SWITCH = "com.vanmypq.cameralophoc.SWITCH"
        const val ACTION_REBIND = "com.vanmypq.cameralophoc.REBIND"
        const val ACTION_STOP = "com.vanmypq.cameralophoc.STOP"
        private const val CH = "camera_lop_hoc"
        private const val NOTI_ID = 1
        private const val TAP_GAP_MS = 400L
        private const val COOLDOWN_MS = 1200L

        @Volatile
        var running = false
    }

    private var provider: ProcessCameraProvider? = null
    private var imageCapture: ImageCapture? = null
    private var overlay: View? = null
    private var orient: OrientationEventListener? = null
    private var rotation = Surface.ROTATION_0
    private var taps = 0
    private var lastTap = 0L
    private var lastShot = 0L
    private var count = 0
    private var sound: MediaActionSound? = null

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(CH, "Camera lớp học", NotificationManager.IMPORTANCE_LOW)
        )
        ServiceCompat.startForeground(
            this, NOTI_ID, buildNotification("Chạm ${Cfg.taps(this)} lần liên tiếp vào màn hình để chụp"),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MANIFEST
        )
        sound = MediaActionSound().apply { load(MediaActionSound.SHUTTER_CLICK) }
        setupOverlay()
        setupOrientation()
        setupCamera()
        running = true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_SHOT -> shoot()
            ACTION_SWITCH -> { Cfg.setFront(this, !Cfg.front(this)); bind() }
            ACTION_REBIND -> bind()
            ACTION_STOP -> stopSelf()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        try { overlay?.let { (getSystemService(WINDOW_SERVICE) as WindowManager).removeView(it) } } catch (_: Exception) {}
        overlay = null
        orient?.disable()
        try { provider?.unbindAll() } catch (_: Exception) {}
        sound?.release()
        super.onDestroy()
    }

    // ---------- Phát hiện chạm liên tiếp (không cần nút nổi) ----------
    // Cửa sổ 1x1px trong suốt, không chặn cảm ứng; nhận ACTION_OUTSIDE mỗi lần có chạm ở nơi khác.
    private fun setupOverlay() {
        if (!Settings.canDrawOverlays(this)) return
        val v = View(this)
        v.setOnTouchListener { _, e ->
            if (e.action == MotionEvent.ACTION_OUTSIDE) onTap()
            false
        }
        val lp = WindowManager.LayoutParams(
            1, 1,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT
        )
        lp.gravity = Gravity.TOP or Gravity.START
        try {
            (getSystemService(WINDOW_SERVICE) as WindowManager).addView(v, lp)
            overlay = v
        } catch (_: Exception) {}
    }

    private fun onTap() {
        val now = SystemClock.uptimeMillis()
        if (now - lastTap > TAP_GAP_MS) taps = 0
        lastTap = now
        taps++
        if (taps >= Cfg.taps(this)) {
            taps = 0
            shoot()
        }
    }

    // ---------- Camera ----------
    private fun setupOrientation() {
        orient = object : OrientationEventListener(this) {
            override fun onOrientationChanged(o: Int) {
                if (o == ORIENTATION_UNKNOWN) return
                rotation = when {
                    o >= 315 || o < 45 -> Surface.ROTATION_0
                    o < 135 -> Surface.ROTATION_270
                    o < 225 -> Surface.ROTATION_180
                    else -> Surface.ROTATION_90
                }
                imageCapture?.targetRotation = rotation
            }
        }.also { if (it.canDetectOrientation()) it.enable() }
    }

    private fun setupCamera() {
        val f = ProcessCameraProvider.getInstance(this)
        f.addListener({
            provider = f.get()
            bind()
        }, ContextCompat.getMainExecutor(this))
    }

    private fun bind() {
        val p = provider ?: return
        val wantFront = Cfg.front(this)
        fun tryBind(front: Boolean): Boolean {
            return try {
                val sel = if (front) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
                val ic = ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                    .setJpegQuality(92)
                    .setTargetRotation(rotation)
                    .build()
                p.unbindAll()
                p.bindToLifecycle(this, sel, ic)
                imageCapture = ic
                true
            } catch (e: Exception) {
                false
            }
        }
        if (!tryBind(wantFront)) {
            if (tryBind(!wantFront)) {
                Cfg.setFront(this, !wantFront)
                notify("Máy không mở được camera ${if (wantFront) "trước" else "sau"}, đã chuyển sang camera còn lại")
            } else {
                imageCapture = null
                notify("Không mở được camera. Có thể app khác đang dùng camera.")
            }
        } else {
            notify("Camera ${if (wantFront) "trước" else "sau"} sẵn sàng. Chạm ${Cfg.taps(this)} lần để chụp")
        }
    }

    private fun shoot() {
        val now = SystemClock.elapsedRealtime()
        if (now - lastShot < COOLDOWN_MS) return
        val ic = imageCapture ?: return
        lastShot = now
        val name = "Lop_" + SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())
        val cv = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/CameraLopHoc")
        }
        val opts = ImageCapture.OutputFileOptions
            .Builder(contentResolver, MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv).build()
        ic.takePicture(opts, ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(r: ImageCapture.OutputFileResults) {
                    count++
                    feedback()
                    notify("Đã chụp $count ảnh (Thư viện > CameraLopHoc)")
                }
                override fun onError(e: ImageCaptureException) {
                    notify("Chụp lỗi: ${e.message}")
                }
            })
    }

    private fun feedback() {
        if (Cfg.sound(this)) sound?.play(MediaActionSound.SHUTTER_CLICK)
        if (Cfg.vibrate(this)) {
            val v = getSystemService(VIBRATOR_SERVICE) as Vibrator
            v.vibrate(VibrationEffect.createOneShot(60, VibrationEffect.DEFAULT_AMPLITUDE))
        }
    }

    // ---------- Thông báo ----------
    private fun pi(action: String, code: Int) = PendingIntent.getService(
        this, code, Intent(this, CameraService::class.java).setAction(action),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    private fun buildNotification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CH)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentTitle("Camera lớp học đang chạy")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(0, "Chụp", pi(ACTION_SHOT, 1))
            .addAction(0, "Đổi camera", pi(ACTION_SWITCH, 2))
            .addAction(0, "Dừng", pi(ACTION_STOP, 3))
            .build()
    }

    private fun notify(text: String) {
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).notify(NOTI_ID, buildNotification(text))
    }
}
