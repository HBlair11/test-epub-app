package com.epubreader.app.epub

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.os.CountDownTimer
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.text.Html
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import java.util.UUID
import java.util.zip.ZipFile

/**
 * Foreground-reader TTS engine wrapper (system engine only; no network and no
 * voice downloads are performed).
 *
 * Patch v37 additions on top of the original chunked chapter reader:
 *  - offline voice selection + per-book language from EPUB dc:language
 *  - speech rate (0.5x–3.0x) and pitch (0.5x–2.0x) applied live
 *  - sentence skip forward/backward with QUEUE_FLUSH
 *  - repeat loop cycling: off -> sentence -> single word
 *  - sleep timer with per-second progress callback
 *  - word-level range callbacks (onRangeStart) for bimodal reading
 *  - sentence position reporting for the status line
 *
 * Background playback is enabled by the host (ReaderActivity keeps the
 * process alive through ReaderTtsService when the user turns it on); the
 * controller itself is lifecycle-agnostic and guarded against stale
 * utterance callbacks.
 */
class ReaderTtsController(
    context: Context,
    private val onStateChanged: (Boolean) -> Unit,
    private val onChapterFinished: () -> Unit,
    private val onWordRange: ((sentence: String, start: Int, end: Int) -> Unit)? = null,
    private val onSleepTimerTick: ((remainingMs: Long) -> Unit)? = null,
    private val onSleepTimerFinished: (() -> Unit)? = null,
) : AutoCloseable {
    enum class State { UNAVAILABLE, INITIALIZING, READY, PLAYING, PAUSED }

    /** Repeat loop mode: replay the sentence, loop a single word, or off. */
    enum class RepeatMode { OFF, SENTENCE, WORD }

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
    private var activeChunkText: String = ""
    private var sleepTimer: CountDownTimer? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var wordLoopActive = false
    private var wordLoopText: String? = null

    var state: State = State.INITIALIZING
        private set

    /** Speech rate in engine units, 0.5x..3.0x. */
    var speechRate: Float = 0.9f
        set(value) {
            field = value.coerceIn(MIN_RATE, MAX_RATE)
            tts?.setSpeechRate(field)
        }

    /** Pitch in engine units, 0.5x..2.0x. */
    var pitch: Float = 1.0f
        set(value) {
            field = value.coerceIn(MIN_PITCH, MAX_PITCH)
            tts?.setPitch(field)
        }

    /** Explicit voice name; overrides [bookLanguage] when set and available. */
    var voiceName: String? = null

    /** Language tag from the EPUB's dc:language; used when no explicit voice. */
    var bookLanguage: String? = null

    /** Current repeat loop mode (off / sentence / word). */
    var repeatMode: RepeatMode = RepeatMode.OFF

    init {
        tts = TextToSpeech(appContext) { status ->
            initialized = status == TextToSpeech.SUCCESS
            state = if (initialized) {
                applyLanguage()
                State.READY
            } else {
                State.UNAVAILABLE
            }
            onStateChanged(state == State.PLAYING)
        }.also { engine ->
            engine.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String) = Unit

                override fun onRangeStart(utteranceId: String, start: Int, end: Int, frame: Int) {
                    if (utteranceId != activeUtteranceId) return
                    val sentence = activeChunkText
                    onWordRange?.invoke(sentence, start, end)
                    // Word-repeat mode: as soon as the engine starts a word,
                    // cut the sentence and loop that word until turned off.
                    if (repeatMode == RepeatMode.WORD && !wordLoopActive && state == State.PLAYING) {
                        val from = start.coerceIn(0, sentence.length)
                        val to = end.coerceIn(from, sentence.length)
                        val word = sentence.substring(from, to)
                        if (word.isNotBlank()) {
                            wordLoopActive = true
                            mainHandler.post { beginWordLoop(word) }
                        }
                    }
                }

                override fun onDone(utteranceId: String) {
                    if (utteranceId.startsWith(WORD_LOOP_PREFIX)) {
                        if (utteranceId != activeUtteranceId) return
                        activeUtteranceId = null
                        if (repeatMode == RepeatMode.WORD && state == State.PLAYING) {
                            wordLoopText?.let { word -> mainHandler.post { beginWordLoop(word) } }
                        } else {
                            // Word loop ended: resume the interrupted sentence.
                            wordLoopActive = false
                            wordLoopText = null
                            if (state == State.PLAYING && chunks.isNotEmpty()) speakCurrentChunk()
                        }
                        return
                    }
                    if (utteranceId != activeUtteranceId) return
                    activeUtteranceId = null
                    if (repeatMode == RepeatMode.SENTENCE && chunks.isNotEmpty()) {
                        speakCurrentChunk()
                        return
                    }
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
                    wordLoopActive = false
                    wordLoopText = null
                    state = State.READY
                    releaseAudioFocus()
                    onStateChanged(false)
                }
            })
        }
    }

    /** Offline (non-network) voices available on this device's engine. */
    fun availableVoices(): List<android.speech.tts.Voice> =
        tts?.voices.orEmpty()
            .filter { !it.isNetworkConnectionRequired }
            .sortedBy { it.locale.displayName }

    fun isVoiceAvailable(name: String): Boolean =
        availableVoices().any { it.name == name }

    /** 1-based (index, count) of the sentence currently being spoken. */
    fun sentencePosition(): Pair<Int, Int>? =
        if (chunks.isEmpty()) null else (chunkIndex + 1).coerceAtMost(chunks.size) to chunks.size

    fun sleepTimerRemainingMs(): Long =
        sleepTimer?.let { timer -> sleepRemainingMs } ?: -1L

    fun startSleepTimer(minutes: Int) {
        cancelSleepTimer()
        sleepRemainingMs = minutes * 60_000L
        sleepTimer = object : CountDownTimer(minutes * 60_000L, 1000L) {
            override fun onTick(millisUntilFinished: Long) {
                sleepRemainingMs = millisUntilFinished
                onSleepTimerTick?.invoke(millisUntilFinished)
            }

            override fun onFinish() {
                sleepRemainingMs = 0L
                sleepTimer = null
                onSleepTimerFinished?.invoke()
                stop()
            }
        }.start()
    }

    fun cancelSleepTimer() {
        sleepTimer?.cancel()
        sleepTimer = null
        sleepRemainingMs = -1L
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
            wordLoopActive = false
            wordLoopText = null
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

    /** Jump to the previous/next sentence and speak it immediately. */
    fun skipSentence(forward: Boolean) {
        if (!initialized || chunks.isEmpty()) return
        wordLoopActive = false
        wordLoopText = null
        val target = if (forward) (chunkIndex + 1).coerceAtMost(chunks.size - 1)
        else (chunkIndex - 1).coerceAtLeast(0)
        if (target == chunkIndex && state != State.PAUSED) {
            // Re-speak the same sentence on a no-op skip so the user gets feedback.
            speakCurrentChunk()
            return
        }
        chunkIndex = target
        if (state == State.PAUSED) {
            state = State.PLAYING
            onStateChanged(true)
        }
        speakCurrentChunk()
    }

    fun stop() {
        activeUtteranceId = null
        tts?.stop()
        chunks = emptyList()
        chunkIndex = 0
        wordLoopActive = false
        wordLoopText = null
        cancelSleepTimer()
        state = if (initialized) State.READY else State.UNAVAILABLE
        releaseAudioFocus()
        onStateChanged(false)
    }

    private fun playInternal() {
        if (!initialized || chunks.isEmpty()) return
        audioManager?.requestAudioFocus(audioFocusListener, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
        applyLanguage()
        tts?.setSpeechRate(speechRate)
        tts?.setPitch(pitch)
        state = State.PLAYING
        onStateChanged(true)
        speakCurrentChunk()
    }

    private fun applyLanguage() {
        val engine = tts ?: return
        val voice = voiceName?.let { name -> engine.voices.orEmpty().find { it.name == name && !it.isNetworkConnectionRequired } }
        if (voice != null) {
            engine.voice = voice
            return
        }
        val tag = bookLanguage
        if (!tag.isNullOrBlank()) {
            val locale = Locale.forLanguageTag(tag)
            engine.language = locale
        } else {
            engine.language = Locale.getDefault()
        }
    }

    private fun pause() {
        activeUtteranceId = null
        tts?.stop()
        wordLoopActive = false
        wordLoopText = null
        state = State.PAUSED
        releaseAudioFocus()
        onStateChanged(false)
    }

    /** Speak a single word on repeat; used by RepeatMode.WORD. */
    private fun beginWordLoop(word: String) {
        if (state != State.PLAYING) {
            wordLoopActive = false
            return
        }
        wordLoopText = word
        val id = WORD_LOOP_PREFIX + UUID.randomUUID()
        activeUtteranceId = id
        tts?.speak(word, TextToSpeech.QUEUE_FLUSH, null, id)
    }

    private fun speakCurrentChunk() {
        val text = chunks.getOrNull(chunkIndex) ?: return
        val id = UUID.randomUUID().toString()
        activeUtteranceId = id
        activeChunkText = text
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

    @Volatile
    private var sleepRemainingMs: Long = -1L

    override fun close() {
        stop()
        tts?.shutdown()
        tts = null
    }

    companion object {
        const val MIN_RATE = 0.5f
        const val MAX_RATE = 3.0f
        const val MIN_PITCH = 0.5f
        const val MAX_PITCH = 2.0f
        private const val WORD_LOOP_PREFIX = "livre-word-loop-"
    }
}
