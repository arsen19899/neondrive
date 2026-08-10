package com.neondrive.launcher.nav

import android.app.ActivityOptions
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast

data class NavApp(val packageName: String, val label: String)

/**
 * Работа с навигационными приложениями, установленными на головном устройстве.
 *
 * Важно понимать границу возможного: подписка Плюс в Навигаторе относится к аккаунту
 * пользователя внутри того приложения и не даёт стороннему лаунчеру право рисовать
 * карту Яндекса у себя — для этого нужен ключ MapKit/NaviKit SDK. Поэтому «карта во
 * фрейме» делается двумя обходными путями, каждый из которых честно работает без ключа:
 *
 *  • FRAME   — приложение поднимается плавающим окном ровно по границам панели
 *              (freeform-режим прошивки);
 *  • OVERLAY — приложение занимает весь экран, а панели оболочки висят поверх него.
 */
object NavigatorBridge {

    const val PKG_NAVI = "ru.yandex.yandexnavi"
    const val PKG_MAPS = "ru.yandex.yandexmaps"
    const val PKG_GMAPS = "com.google.android.apps.maps"
    const val PKG_NAVITEL = "com.navitel"

    /** Пакеты, которые точно являются навигацией, но могут не отвечать на geo:. */
    private val KNOWN = linkedMapOf(
        PKG_NAVI to "Яндекс Навигатор",
        PKG_MAPS to "Яндекс Карты",
        PKG_NAVITEL to "Навител",
        PKG_GMAPS to "Google Карты",
        "ru.dublgis.dgismobile" to "2ГИС",
        "com.sygic.aura" to "Sygic",
        "com.waze" to "Waze",
        "com.mapswithme.maps.pro" to "Organic Maps"
    )

    /* ─────────────────  ЧТО УСТАНОВЛЕНО  ───────────────── */

    fun isInstalled(context: Context, pkg: String): Boolean =
        context.packageManager.getLaunchIntentForPackage(pkg) != null

    /**
     * Все навигационные приложения устройства.
     *
     * Собираем из трёх источников, чтобы не зависеть от хардкода: приложения,
     * умеющие открывать `geo:`-ссылки, приложения с категорией APP_MAPS и
     * заведомо известные пакеты. Так в списке окажется и Навител, и любой
     * штатный навигатор конкретной прошивки.
     */
    fun installedNavApps(context: Context): List<NavApp> {
        val pm = context.packageManager
        val found = LinkedHashMap<String, String>()

        fun collect(intent: Intent) {
            runCatching { pm.queryIntentActivities(intent, 0) }.getOrNull()
                ?.forEach { ri ->
                    val pkg = ri.activityInfo?.packageName ?: return@forEach
                    if (pkg == context.packageName) return@forEach
                    found.putIfAbsent(pkg, runCatching { ri.loadLabel(pm).toString() }.getOrDefault(pkg))
                }
        }

        collect(Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=")))
        collect(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_MAPS))

        KNOWN.forEach { (pkg, label) ->
            if (isInstalled(context, pkg)) found.putIfAbsent(pkg, label)
        }

        // Известные — вперёд, у них понятные названия
        return found.entries
            .map { NavApp(it.key, KNOWN[it.key] ?: it.value) }
            .sortedBy { KNOWN.keys.indexOf(it.packageName).takeIf { i -> i >= 0 } ?: 99 }
    }

    /** Первый доступный пакет: сначала выбранный, потом любой установленный навигатор. */
    fun resolvePackage(context: Context, preferred: String): String? {
        if (isInstalled(context, preferred)) return preferred
        return installedNavApps(context).firstOrNull()?.packageName
    }

    fun labelOf(context: Context, pkg: String): String =
        KNOWN[pkg] ?: runCatching {
            val pm = context.packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
        }.getOrDefault(pkg)

    /* ─────────────────  ЗАПУСК  ───────────────── */

    fun openFullscreen(context: Context, preferred: String): Boolean {
        val pkg = resolvePackage(context, preferred) ?: return notInstalled(context)
        val intent = context.packageManager.getLaunchIntentForPackage(pkg) ?: return false
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        return start(context, intent)
    }

    /**
     * Поддерживает ли прошивка плавающие окна.
     *
     * Проверяем и системный флаг устройства, и глобальную настройку: на китайских
     * головных устройствах freeform часто скомпилирован, но выключен.
     */
    fun freeformSupported(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return false
        val byFeature = context.packageManager
            .hasSystemFeature(PackageManager.FEATURE_FREEFORM_WINDOW_MANAGEMENT)
        val byGlobal = runCatching {
            Settings.Global.getInt(context.contentResolver, "enable_freeform_support", 0) == 1
        }.getOrDefault(false)
        val resizable = runCatching {
            Settings.Global.getInt(context.contentResolver, "force_resizable_activities", 0) == 1
        }.getOrDefault(false)
        return byFeature || byGlobal || resizable
    }

    /**
     * Запуск в окне по границам панели карты.
     *
     * Если прошивка freeform не поддерживает, окно откроется на весь экран —
     * поэтому в настройках для таких устройств предлагается режим «Поверх карты».
     */
    fun openInFrame(context: Context, preferred: String, bounds: Rect): Boolean {
        val pkg = resolvePackage(context, preferred) ?: return notInstalled(context)
        val intent = context.packageManager.getLaunchIntentForPackage(pkg) ?: return false
        intent.addFlags(
            Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_MULTIPLE_TASK or
                Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT
        )

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N || bounds.isEmpty) {
            return openFullscreen(context, preferred)
        }

        val options = ActivityOptions.makeBasic()
        runCatching { options.setLaunchBounds(bounds) }

        val ok = runCatching { context.startActivity(intent, options.toBundle()) }.isSuccess
        return if (ok) true else openFullscreen(context, preferred)
    }

    /* ─────────────────  URL-СХЕМЫ  ───────────────── */

    /**
     * Маршрут до точки. У Яндекса схема документирована:
     * `yandexnavi://build_route_on_map?lat_from=&lon_from=&lat_to=&lon_to=`.
     * Для остальных приложений уходим на универсальный `geo:`-интент.
     */
    fun buildRoute(
        context: Context,
        pkg: String,
        latTo: Double,
        lonTo: Double,
        latFrom: Double? = null,
        lonFrom: Double? = null
    ) {
        if (pkg == PKG_NAVI) {
            val sb = StringBuilder("yandexnavi://build_route_on_map?lat_to=$latTo&lon_to=$lonTo")
            if (latFrom != null && lonFrom != null) sb.append("&lat_from=$latFrom&lon_from=$lonFrom")
            if (sendScheme(context, sb.toString(), pkg)) return
        }
        val geo = Intent(Intent.ACTION_VIEW, Uri.parse("geo:$latTo,$lonTo?q=$latTo,$lonTo"))
            .setPackage(pkg)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (!start(context, geo)) openFullscreen(context, pkg)
    }

    /** Показать точку на карте. */
    fun showPoint(
        context: Context,
        pkg: String,
        lat: Double,
        lon: Double,
        zoom: Int = 16,
        description: String? = null
    ) {
        if (pkg == PKG_NAVI) {
            val desc = description?.let { "&desc=" + Uri.encode(it) }.orEmpty()
            val uri = "yandexnavi://show_point_on_map?lat=$lat&lon=$lon&zoom=$zoom$desc&no-balloon=0"
            if (sendScheme(context, uri, pkg)) return
        }
        val geo = Intent(Intent.ACTION_VIEW, Uri.parse("geo:$lat,$lon?z=$zoom"))
            .setPackage(pkg)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (!start(context, geo)) openFullscreen(context, pkg)
    }

    private fun sendScheme(context: Context, uri: String, pkg: String): Boolean {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(uri))
            .setPackage(pkg)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val resolvable = runCatching {
            context.packageManager.resolveActivity(intent, 0) != null
        }.getOrDefault(false)
        return resolvable && start(context, intent)
    }

    /* ─────────────────  СЛУЖЕБНОЕ  ───────────────── */

    private fun start(context: Context, intent: Intent): Boolean =
        runCatching { context.startActivity(intent) }.isSuccess

    private fun notInstalled(context: Context): Boolean {
        toast(context, "Навигационное приложение не найдено")
        return false
    }

    private fun toast(context: Context, text: String) {
        runCatching { Toast.makeText(context, text, Toast.LENGTH_SHORT).show() }
    }
}
