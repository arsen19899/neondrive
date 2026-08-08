package com.neondrive.launcher.apps

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.graphics.drawable.Drawable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class AppEntry(
    val label: String,
    val packageName: String,
    val icon: Drawable?,
    val system: Boolean
)

object AppRepository {

    /** Приложения, которые на ГУ полезно вынести вперёд. */
    private val PRIORITY = listOf(
        "ru.yandex.yandexnavi",
        "ru.yandex.yandexmaps",
        "ru.yandex.music",
        "com.google.android.apps.maps",
        "com.spotify.music"
    )

    suspend fun load(context: Context): List<AppEntry> = withContext(Dispatchers.IO) {
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        pm.queryIntentActivities(intent, 0)
            .asSequence()
            .filter { it.activityInfo.packageName != context.packageName }
            .map { ri ->
                val ai = ri.activityInfo.applicationInfo
                AppEntry(
                    label = ri.loadLabel(pm).toString(),
                    packageName = ri.activityInfo.packageName,
                    icon = runCatching { ri.loadIcon(pm) }.getOrNull(),
                    system = (ai.flags and ApplicationInfo.FLAG_SYSTEM) != 0
                )
            }
            .distinctBy { it.packageName }
            .sortedWith(
                compareBy(
                    { PRIORITY.indexOf(it.packageName).takeIf { i -> i >= 0 } ?: 999 },
                    { it.label.lowercase() }
                )
            )
            .toList()
    }

    fun launch(context: Context, packageName: String) {
        val i = context.packageManager.getLaunchIntentForPackage(packageName)
            ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (i != null) runCatching { context.startActivity(i) }
    }

    fun openAppInfo(context: Context, packageName: String) {
        runCatching {
            context.startActivity(
                Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                    .setData(android.net.Uri.parse("package:$packageName"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }
}
