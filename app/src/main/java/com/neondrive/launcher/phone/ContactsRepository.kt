package com.neondrive.launcher.phone

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.ContactsContract
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class Contact(
    val id: Long,
    val name: String,
    val number: String,
    val photoUri: Uri?,
    val starred: Boolean
) {
    /** Только цифры — по ним ищем, когда пользователь набирает на клавиатуре. */
    val digits: String = number.filter { it.isDigit() }

    val initials: String
        get() = name.split(' ', '-')
            .filter { it.isNotBlank() }
            .take(2)
            .joinToString("") { it.first().uppercase() }
            .ifBlank { "?" }
}

/**
 * Контакты головного устройства. На магнитолах телефонная книга обычно
 * прилетает с телефона по Bluetooth (профиль PBAP) и складывается в ту же
 * системную базу, поэтому отдельного источника не нужно.
 */
object ContactsRepository {

    fun hasPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) ==
            PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    suspend fun load(context: Context): List<Contact> = withContext(Dispatchers.IO) {
        if (!hasPermission(context)) return@withContext emptyList()

        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.CONTACT_ID,
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME_PRIMARY,
            ContactsContract.CommonDataKinds.Phone.NUMBER,
            ContactsContract.CommonDataKinds.Phone.PHOTO_THUMBNAIL_URI,
            ContactsContract.CommonDataKinds.Phone.STARRED
        )
        val order = "${ContactsContract.CommonDataKinds.Phone.STARRED} DESC, " +
            "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME_PRIMARY} COLLATE NOCASE ASC"

        val out = ArrayList<Contact>(256)
        runCatching {
            context.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                projection,
                null,
                null,
                order
            )
        }.getOrNull()?.use { c ->
            val idIx = c.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.CONTACT_ID)
            val nameIx = c.getColumnIndexOrThrow(
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME_PRIMARY
            )
            val numIx = c.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.NUMBER)
            val photoIx = c.getColumnIndexOrThrow(
                ContactsContract.CommonDataKinds.Phone.PHOTO_THUMBNAIL_URI
            )
            val starIx = c.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.STARRED)

            while (c.moveToNext()) {
                val number = c.getString(numIx)?.trim().orEmpty()
                if (number.isBlank()) continue
                out += Contact(
                    id = c.getLong(idIx),
                    name = c.getString(nameIx)?.trim().orEmpty().ifBlank { number },
                    number = number,
                    photoUri = c.getString(photoIx)?.let { Uri.parse(it) },
                    starred = c.getInt(starIx) == 1
                )
            }
        }

        // У одного человека бывает несколько записей одного и того же номера
        out.distinctBy { it.name + it.digits }
    }

    /**
     * Имя контакта по номеру — для экрана звонка: входящий вызов отдаёт только
     * номер, а платформенный caller ID (details.callerDisplayName) заполнен
     * далеко не всегда. PhoneLookup сам нормализует форматирование номера
     * (пробелы, +7 / 8, скобки), поэтому работает надёжнее, чем сравнение строк
     * по уже загруженному списку контактов.
     */
    @SuppressLint("MissingPermission")
    suspend fun nameForNumber(context: Context, number: String): String? = withContext(Dispatchers.IO) {
        if (!hasPermission(context) || number.isBlank()) return@withContext null
        runCatching {
            val uri = Uri.withAppendedPath(
                ContactsContract.PhoneLookup.CONTENT_FILTER_URI,
                Uri.encode(number)
            )
            context.contentResolver.query(
                uri,
                arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME),
                null, null, null
            )?.use { c ->
                if (c.moveToFirst()) {
                    c.getString(c.getColumnIndexOrThrow(ContactsContract.PhoneLookup.DISPLAY_NAME))
                } else null
            }
        }.getOrNull()
    }

    /**
     * Поиск и по имени, и по номеру: на ходу удобнее набрать три цифры,
     * чем целиться в буквы.
     */
    fun filter(all: List<Contact>, query: String): List<Contact> {
        val q = query.trim()
        if (q.isBlank()) return all
        val qDigits = q.filter { it.isDigit() }
        return all.filter { contact ->
            contact.name.contains(q, ignoreCase = true) ||
                (qDigits.isNotEmpty() && contact.digits.contains(qDigits))
        }
    }
}
