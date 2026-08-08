package com.neondrive.launcher.overlay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.neondrive.launcher.MainActivity
import com.neondrive.launcher.R
import com.neondrive.launcher.data.LauncherSettings
import com.neondrive.launcher.data.SettingsRepository
import com.neondrive.launcher.data.SidebarSide
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Режим «карта во фрейме» через оверлей.
 *
 * Навигационное приложение занимает весь экран, а приборы и плеер оболочки живут
 * в отдельных окнах поверх него — по краям, ровно там же, где они на рабочем столе.
 * Визуально получается тот же рабочий стол, но карта настоящая и полностью
 * интерактивная: касания вне наших панелей уходят в навигацию.
 *
 * Работает на любой прошивке, нужно лишь разрешение «Поверх других приложений».
 */
class NeonOverlayService : LifecycleService() {

    companion object {
        private const val CHANNEL_ID = "neon_overlay"
        private const val NOTIF_ID = 0x4E4F

        const val ACTION_SHOW = "com.neondrive.launcher.OVERLAY_SHOW"
        const val ACTION_HIDE = "com.neondrive.launcher.OVERLAY_HIDE"

        private val _visible = MutableStateFlow(false)
        val visible: StateFlow<Boolean> = _visible

        fun canDraw(context: Context): Boolean =
            Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(context)

        fun show(context: Context) {
            if (!canDraw(context)) return
            val i = Intent(context, NeonOverlayService::class.java).setAction(ACTION_SHOW)
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(i)
                } else {
                    context.startService(i)
                }
            }
        }

        fun hide(context: Context) {
            runCatching {
                context.startService(
                    Intent(context, NeonOverlayService::class.java).setAction(ACTION_HIDE)
                )
            }
        }

        /** Открыть системный экран выдачи разрешения на рисование поверх окон. */
        fun requestPermission(context: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
            runCatching {
                context.startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        android.net.Uri.parse("package:${context.packageName}")
                    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
        }
    }

    private lateinit var repo: SettingsRepository
    private val windowManager: WindowManager by lazy {
        getSystemService(Context.WINDOW_SERVICE) as WindowManager
    }

    private var host: OverlayComposeHost? = null
    private var columnView: View? = null
    private var dockView: View? = null

    private val settingsState = MutableStateFlow(LauncherSettings())

    override fun onCreate() {
        super.onCreate()
        repo = SettingsRepository(applicationContext)
        startForeground(NOTIF_ID, buildNotification())

        lifecycleScope.launch {
            repo.settings.collect { settingsState.value = it }
        }
        // Сторона панелей меняется на лету — окна нужно переставить
        lifecycleScope.launch {
            repo.settings
                .map { it.sidebarSide }
                .distinctUntilChanged()
                .collect { if (columnView != null) { removeViews(); addViews() } }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_HIDE -> {
                removeViews()
                _visible.value = false
                stopSelf()
            }
            else -> if (columnView == null) addViews()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        removeViews()
        _visible.value = false
        super.onDestroy()
    }

    /* ─────────────────  ОКНА  ───────────────── */

    private fun addViews() {
        if (!canDraw(this)) {
            stopSelf()
            return
        }
        val h = OverlayComposeHost(this).also { host = it }

        val metrics = resources.displayMetrics
        val columnWidth = (metrics.widthPixels * 0.25f).toInt()
        val dockWidth = (108 * metrics.density).toInt()
        val side = settingsState.value.sidebarSide

        val column = h.createView {
            OverlayColumn(
                settingsFlow = settingsState,
                onOpenLauncher = ::openLauncher
            )
        }
        val dock = h.createView {
            OverlayDock(
                settingsFlow = settingsState,
                onOpenLauncher = ::openLauncher,
                onHide = { hide(applicationContext) }
            )
        }

        // Колонка приборов — со стороны, противоположной доку
        val columnGravity = if (side == SidebarSide.RIGHT) Gravity.START else Gravity.END
        val dockGravity = if (side == SidebarSide.RIGHT) Gravity.END else Gravity.START

        runCatching {
            windowManager.addView(column, params(columnWidth, Gravity.TOP or columnGravity))
            windowManager.addView(dock, params(dockWidth, Gravity.TOP or dockGravity))
            columnView = column
            dockView = dock
            _visible.value = true
        }.onFailure {
            removeViews()
            stopSelf()
        }
    }

    private fun params(width: Int, gravity: Int): WindowManager.LayoutParams {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE
        }
        return WindowManager.LayoutParams(
            width,
            WindowManager.LayoutParams.MATCH_PARENT,
            type,
            // NOT_FOCUSABLE — не воруем фокус у навигации,
            // NOT_TOUCH_MODAL — касания мимо наших панелей уходят в карту
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            this.gravity = gravity
        }
    }

    private fun removeViews() {
        columnView?.let { v -> runCatching { windowManager.removeView(v) } }
        dockView?.let { v -> runCatching { windowManager.removeView(v) } }
        columnView = null
        dockView = null
        host?.destroy()
        host = null
    }

    private fun openLauncher() {
        runCatching {
            startActivity(
                Intent(this, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            )
        }
    }

    /* ─────────────────  УВЕДОМЛЕНИЕ  ───────────────── */

    private fun buildNotification(): Notification {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.channel_overlay),
                    NotificationManager.IMPORTANCE_MIN
                ).apply { setShowBadge(false) }
            )
        }
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val b = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION") Notification.Builder(this)
        }
        return b.setContentTitle("NeonDrive")
            .setContentText("Панели поверх карты")
            .setSmallIcon(R.drawable.ic_stat_neon)
            .setContentIntent(open)
            .setOngoing(true)
            .build()
    }
}
