package com.neondrive.launcher.system

import android.app.Activity
import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings

/**
 * «Сделать оболочкой по умолчанию».
 *
 * Назначить себя домашним экраном приложение не может — это всегда осознанный
 * выбор пользователя. Что можно сделать: честно показать текущее состояние и
 * открыть нужный системный диалог в один шаг, а не отправлять человека
 * блуждать по настройкам.
 */
object DefaultLauncherHelper {

    /** Является ли NeonDrive текущим домашним экраном. */
    fun isDefault(context: Context): Boolean = runCatching {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val res = context.packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)
        res?.activityInfo?.packageName == context.packageName
    }.getOrDefault(false)

    /**
     * Открыть выбор домашнего экрана.
     *
     * На Android 10+ есть точечный системный диалог роли HOME — он открывается
     * сразу на нужном пункте. На более старых прошивках уходим в общий экран
     * «Приложение для главного экрана», а если и его нет — в список приложений
     * по умолчанию.
     */
    fun requestDefault(context: Context) {
        val activity = context.findActivity()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && activity != null) {
            val rm = activity.getSystemService(RoleManager::class.java)
            if (rm != null && rm.isRoleAvailable(RoleManager.ROLE_HOME) &&
                !rm.isRoleHeld(RoleManager.ROLE_HOME)
            ) {
                val ok = runCatching {
                    activity.startActivityForResult(
                        rm.createRequestRoleIntent(RoleManager.ROLE_HOME),
                        REQUEST_ROLE_HOME
                    )
                }.isSuccess
                if (ok) return
            }
        }

        val candidates = listOf(
            Intent(Settings.ACTION_HOME_SETTINGS),
            Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS),
            Intent(Settings.ACTION_SETTINGS)
        )
        for (intent in candidates) {
            val ok = runCatching {
                context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }.isSuccess
            if (ok) return
        }
    }

    /**
     * Сбросить текущий выбор домашнего экрана.
     *
     * Нужно, когда лаунчером уже назначено другое приложение: без сброса система
     * не покажет диалог выбора заново. Открываем карточку этого приложения —
     * там есть кнопка «Удалить настройки по умолчанию».
     */
    fun openCurrentLauncherSettings(context: Context) {
        val current = runCatching {
            val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
            context.packageManager
                .resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)
                ?.activityInfo?.packageName
        }.getOrNull() ?: return

        runCatching {
            context.startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                    .setData(android.net.Uri.parse("package:$current"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }

    const val REQUEST_ROLE_HOME = 0x4E44

    /** В Compose LocalContext бывает обёрткой — разворачиваем до настоящей активности. */
    private fun Context.findActivity(): Activity? {
        var c: Context? = this
        while (c is android.content.ContextWrapper) {
            if (c is Activity) return c
            c = c.baseContext
        }
        return null
    }
}
