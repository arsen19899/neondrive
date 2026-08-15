package com.neondrive.launcher.voice

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

/**
 * Распознавание системным сервисом Android.
 *
 * Запасной путь на случай, когда офлайн-модели Vosk на устройстве нет. Ничего
 * скачивать не надо, но и гарантий никаких: `SpeechRecognizer` сам ничего не
 * распознаёт, он лишь обращается к установленному в системе сервису
 * распознавания. На магнитолах без сервисов Google такого сервиса обычно нет
 * вовсе — [isAvailable] тогда честно вернёт false, и оболочка объяснит это в
 * настройках вместо того, чтобы молча не реагировать на кнопку микрофона.
 *
 * ## Чего этот движок не умеет
 *
 * Ждать ключевое слово. `SpeechRecognizer` устроен под сеанс «нажал — сказал —
 * получил результат»: он сам закрывает микрофон по паузе в речи, а на попытку
 * перезапускать его по кругу многие прошивки отвечают ERROR_RECOGNIZER_BUSY.
 * Поэтому [ListenPurpose.WAKE_WORD] здесь сразу возвращает ошибку, а не делает
 * вид, что слушает: «Елисей» без нажатия кнопки работает только на Vosk.
 *
 * ## Офлайн по возможности
 *
 * На Android 13+ есть отдельный распознаватель, работающий на устройстве
 * ([SpeechRecognizer.createOnDeviceSpeechRecognizer]), а начиная с Android 6 —
 * флаг `EXTRA_PREFER_OFFLINE`. Оба задействованы: в машине сети может не быть, и
 * если система умеет разобрать фразу локально, пусть разбирает локально.
 */
object SystemSpeechEngine : SpeechEngine {

    private var recognizer: SpeechRecognizer? = null
    private var listening = false

    override fun isAvailable(context: Context): Boolean =
        runCatching { SpeechRecognizer.isRecognitionAvailable(context) }.getOrDefault(false)

    override fun unavailableReason(context: Context): String =
        if (isAvailable(context)) "" else
            "В системе нет сервиса распознавания речи — обычная ситуация для " +
                "магнитол без сервисов Google. Используйте офлайн-модель Vosk."

    override fun start(
        context: Context,
        purpose: ListenPurpose,
        onResult: (SpeechResult) -> Unit,
        onError: (String) -> Unit
    ) {
        if (purpose == ListenPurpose.WAKE_WORD) {
            onError("Системный распознаватель не умеет ждать ключевое слово")
            return
        }
        if (!isAvailable(context)) {
            onError("Распознавание речи недоступно")
            return
        }

        stop()

        val created = runCatching { createRecognizer(context) }.getOrNull()
        if (created == null) {
            onError("Не удалось запустить распознавание")
            return
        }
        recognizer = created

        created.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) = Unit
            override fun onBeginningOfSpeech() = Unit
            override fun onRmsChanged(rmsdB: Float) = Unit
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEndOfSpeech() = Unit
            override fun onEvent(eventType: Int, params: Bundle?) = Unit

            override fun onPartialResults(partialResults: Bundle?) {
                val text = best(partialResults)
                if (text.isNotBlank()) onResult(SpeechResult(text, partial = true))
            }

            override fun onResults(results: Bundle?) {
                listening = false
                val text = best(results)
                if (text.isNotBlank()) onResult(SpeechResult(text, partial = false))
                else onError("Не расслышал")
            }

            override fun onError(error: Int) {
                listening = false
                onError(describe(error))
            }
        })

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
            )
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ru-RU")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            }
        }

        listening = true
        runCatching { created.startListening(intent) }.onFailure {
            listening = false
            onError("Не удалось открыть микрофон")
        }
    }

    private fun createRecognizer(context: Context): SpeechRecognizer {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val onDevice = runCatching {
                if (SpeechRecognizer.isOnDeviceRecognitionAvailable(context)) {
                    SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
                } else null
            }.getOrNull()
            if (onDevice != null) return onDevice
        }
        return SpeechRecognizer.createSpeechRecognizer(context)
    }

    private fun best(bundle: Bundle?): String =
        bundle?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            ?.firstOrNull()
            ?.trim()
            .orEmpty()

    private fun describe(error: Int): String = when (error) {
        SpeechRecognizer.ERROR_NO_MATCH -> "Не расслышал"
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Тишина"
        SpeechRecognizer.ERROR_AUDIO -> "Микрофон недоступен"
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Распознаватель занят"
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Нет доступа к микрофону"
        SpeechRecognizer.ERROR_NETWORK,
        SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Нет сети для распознавания"
        else -> "Ошибка распознавания"
    }

    override fun stop() {
        val r = recognizer ?: return
        recognizer = null
        listening = false
        // Порядок важен: cancel() до destroy(), иначе часть прошивок оставляет
        // микрофон захваченным до перезапуска процесса.
        runCatching { r.cancel() }
        runCatching { r.destroy() }
    }

    override fun release() = stop()
}
