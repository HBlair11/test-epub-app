package com.epubreader.app.epub

import android.content.Context
import android.media.AudioManager
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.text.Html
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import java.util.UUID
import java.util.zip.ZipFile

/** Foreground-only, system-engine TTS. No network or voice downloads are performed. */
class ReaderTtsController(
    context: Context,
    private val onStateChanged: (Boolean) -> Unit,
    private val onChapterFinished: () -> Unit,
) : AutoCloseable {
    enum class State { UNAVAILABLE, READY, PLAYING, PAUSED }

    private val appContext = context.applicationContext
    private val audioManager = appContext.getSystemService(AudioManager::class.java)
    private val audioFocusListener = AudioManager.OnAudioFocusChangeListener { change ->
        if (change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) {
            stop()
        }
    }
    private var tts: TextToSpeech? = null
    private var chunks: List<String> = emptyList()
    private var chunkIndex = 0
    private var initialized = false
    private var activeUtteranceId: String? = null
    var state: State = State.UNAVAILABLE
        private set
    var speechRate: Float = 0.9f
        set(value) {
            field = value.coerceIn(0.1f, 3.0f)
            tts?.setSpeechRate(field)
        }

    init {
        tts = TextToSpeech(appContext) { status ->
            initialized = status == TextToSpeech.SUCCESS
            if (initialized) {
                val result = tts?.setLanguage(Locale.getDefault()) ?: TextToSpeech.ERROR
                state = if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) State.UNAVAILABLE else State.READY
                initialized = state == State.READY
            } else {
                state = State.UNAVAILABLE
            }
            onStateChanged(state == State.PLAYING)
        }.also { engine ->
            engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String) = Unit

                override fun onDone(utteranceId: String) {
                    if (utteranceId != activeUtteranceId) return
                    activeUtteranceId = null
                    chunkIndex++
                    if (chunkIndex < chunks.size) {
                        speakCurrentChunk()
                    } else {
                        state = State.READY
                        releaseAudioFocus()
                        onStateChanged(false)
                        onChapterFinished()
                    }
                }

                override fun onError(utteranceId: String) {
                    if (utteranceId != activeUtteranceId) return
                    activeUtteranceId = null
                    state = State.READY
                    releaseAudioFocus()
                    onStateChanged(false)
                }
            })
        }
    }

    suspend fun speakChapter(file: File, href: String) {
        val text = extractText(file, href)
        withContext(Dispatchers.Main) {
            if (!initialized || text.isBlank()) {
                state = if (initialized) State.READY else State.UNAVAILABLE
                onStateChanged(false)
                return@withContext
            }
            chunks = chunkText(text)
            chunkIndex = 0
            playInternal()
        }
    }

    fun togglePauseResume() {
        when (state) {
            State.PLAYING -> pause()
            State.PAUSED -> playInternal()
            else -> Unit
        }
    }

    fun stop() {
        activeUtteranceId = null
        tts?.stop()
        chunks = emptyList()
        chunkIndex = 0
        state = if (initialized) State.READY else State.UNAVAILABLE
        releaseAudioFocus()
        onStateChanged(false)
    }

    private fun playInternal() {
        if (!initialized || chunks.isEmpty()) return
        audioManager?.requestAudioFocus(audioFocusListener, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
        tts?.setSpeechRate(speechRate)
        state = State.PLAYING
        onStateChanged(true)
        speakCurrentChunk()
    }

    private fun pause() {
        activeUtteranceId = null
        tts?.stop()
        state = State.PAUSED
        releaseAudioFocus()
        onStateChanged(false)
    }

    private fun speakCurrentChunk() {
        val text = chunks.getOrNull(chunkIndex) ?: return
        val id = UUID.randomUUID().toString()
        activeUtteranceId = id
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, id)
    }

    private fun releaseAudioFocus() {
        audioManager?.abandonAudioFocus(audioFocusListener)
    }

    private suspend fun extractText(file: File, href: String): String = withContext(Dispatchers.IO) {
        runCatching {
            ZipFile(file).use { zip ->
                val entry = zip.getEntry(href) ?: zip.entries().toList().firstOrNull { it.name.equals(href, true) }
                val html = entry?.let { zip.getInputStream(it).bufferedReader(Charsets.UTF_8).use { r -> r.readText() } }.orEmpty()
                val withoutScripts = html.replace(Regex("(?is)<(script|style)[^>]*>.*?</\\1>"), " ")
                Html.fromHtml(withoutScripts, Html.FROM_HTML_MODE_LEGACY).toString()
                    .replace(Regex("\\s+"), " ")
                    .trim()
            }
        }.getOrDefault("")
    }

    private fun chunkText(text: String): List<String> {
        val paragraphs = text.split(Regex("(?<=[.!?])\\s+|\\n+"))
            .map { it.trim() }
            .filter { it.isNotBlank() }
        val result = mutableListOf<String>()
        val maxChars = 3500
        for (paragraph in paragraphs) {
            if (paragraph.length <= maxChars) {
                result += paragraph
                continue
            }
            var start = 0
            while (start < paragraph.length) {
                val end = minOf(start + maxChars, paragraph.length)
                result += paragraph.substring(start, end).trim()
                start = end
            }
        }
        return result
    }

    override fun close() {
        stop()
        tts?.shutdown()
        tts = null
    }
}
