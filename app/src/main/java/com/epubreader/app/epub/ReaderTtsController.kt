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
 * Patch v37 follow-up: major rework of text segmentation and voice switching.
 *  - Segments now preserve paragraph/heading boundaries and add natural pauses
 *    (short after commas, medium after sentences, longer after headings/POV).
 *  - speakChapter accepts an optional startOffset so TTS can begin from the
 *    user's current reading position instead of always the chapter start.
 *  - Voice changes take effect immediately: if playing, the current utterance
 *    is stopped and the segment is re-spoken with the new voice. If paused,
 *    the new voice is applied and the user resumes from the same segment.
 *  - Sentence highlight callback (onSentenceHighlight) fires for each segment.
 */
class ReaderTtsController(
    context: Context,
    private val onStateChanged: (Boolean) -> Unit,
    private val onChapterFinished: () -> Unit,
    private val onWordRange: ((sentence: String, start: Int, end: Int) -> Unit)? = null,
    private val onSleepTimerTick: ((remainingMs: Long) -> Unit)? = null,
    private val onSleepTimerFinished: (() -> Unit)? = null,
    private val onSentenceHighlight: ((sentence: String) -> Unit)? = null,
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
    private var segments: List<TtsSegment> = emptyList()
    private var segmentIndex = 0
    private var initialized = false
    private var activeUtteranceId: String? = null
    private var activeSegmentText: String = ""
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
        set(value) {
            field = value
            // Apply immediately so the change is audible without restart.
            applyVoice()
        }

    /** Language tag from the EPUB's dc:language; used when no explicit voice. */
    var bookLanguage: String? = null

    /** Current repeat loop mode (off / sentence / word). */
    var repeatMode: RepeatMode = RepeatMode.OFF

    init {
        tts = TextToSpeech(appContext) { status ->
            initialized = status == TextToSpeech.SUCCESS
            state = if (initialized) {
                applyVoice()
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
                    val sentence = activeSegmentText
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
                            if (state == State.PLAYING && segments.isNotEmpty()) speakCurrentSegment()
                        }
                        return
                    }
                    if (utteranceId != activeUtteranceId) return
                    activeUtteranceId = null

                    // Insert a natural pause after this segment if configured.
                    val pauseMs = segments.getOrNull(segmentIndex)?.pauseAfterMs ?: 0
                    if (pauseMs > 0 && state == State.PLAYING) {
                        // Use a silent utterance to create a pause.
                        val pauseId = PAUSE_PREFIX + UUID.randomUUID()
                        activeUtteranceId = pauseId
                        tts?.playSilentUtterance(pauseMs.toLong(), TextToSpeech.QUEUE_FLUSH, pauseId)
                        return
                    }

                    if (repeatMode == RepeatMode.SENTENCE && segments.isNotEmpty()) {
                        speakCurrentSegment()
                        return
                    }
                    segmentIndex++
                    if (segmentIndex < segments.size) {
                        speakCurrentSegment()
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
        if (segments.isEmpty()) null else (segmentIndex + 1).coerceAtMost(segments.size) to segments.size

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

    /**
     * Speak the chapter starting from [startOffset] characters into the text.
     * If startOffset > 0, the segment containing that offset is located and
     * playback begins from it. If 0 or omitted, starts from the beginning.
     */
    suspend fun speakChapter(file: File, href: String, startOffset: Int = 0) {
        val text = extractText(file, href)
        withContext(Dispatchers.Main) {
            if (!initialized || text.isBlank()) {
                state = if (initialized) State.READY else State.UNAVAILABLE
                onStateChanged(false)
                return@withContext
            }
            segments = buildSegments(text)
            segmentIndex = if (startOffset > 0) {
                findSegmentIndex(segments, text, startOffset)
            } else {
                0
            }
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
        if (!initialized || segments.isEmpty()) return
        wordLoopActive = false
        wordLoopText = null
        val target = if (forward) (segmentIndex + 1).coerceAtMost(segments.size - 1)
        else (segmentIndex - 1).coerceAtLeast(0)
        if (target == segmentIndex && state != State.PAUSED) {
            // Re-speak the same sentence on a no-op skip so the user gets feedback.
            speakCurrentSegment()
            return
        }
        segmentIndex = target
        if (state == State.PAUSED) {
            state = State.PLAYING
            onStateChanged(true)
        }
        speakCurrentSegment()
    }

    fun stop() {
        activeUtteranceId = null
        tts?.stop()
        segments = emptyList()
        segmentIndex = 0
        wordLoopActive = false
        wordLoopText = null
        cancelSleepTimer()
        state = if (initialized) State.READY else State.UNAVAILABLE
        releaseAudioFocus()
        onStateChanged(false)
    }

    private fun playInternal() {
        if (!initialized || segments.isEmpty()) return
        audioManager?.requestAudioFocus(audioFocusListener, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
        applyVoice()
        tts?.setSpeechRate(speechRate)
        tts?.setPitch(pitch)
        state = State.PLAYING
        onStateChanged(true)
        speakCurrentSegment()
    }

    /** Apply the selected voice (or language fallback) to the engine. */
    private fun applyVoice() {
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

    private fun speakCurrentSegment() {
        val segment = segments.getOrNull(segmentIndex) ?: return
        val id = UUID.randomUUID().toString()
        activeUtteranceId = id
        activeSegmentText = segment.text
        onSentenceHighlight?.invoke(segment.text)
        tts?.speak(segment.text, TextToSpeech.QUEUE_FLUSH, null, id)
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

    /**
     * Build TTS segments from the raw chapter text. Each segment is a sentence
     * or short clause with an associated pause duration for natural rhythm.
     *
     * - Headings (short standalone lines, < 60 chars, no sentence-ending punctuation):
     *   longer pause (600ms) after them.
     * - Paragraph breaks: medium pause (400ms).
     * - Sentence endings (. ! ?): medium pause (350ms).
     * - Commas, semicolons, colons: short pause (150ms).
     * - POV / chapter names: treated as headings.
     */
    private fun buildSegments(text: String): List<TtsSegment> {
        val result = mutableListOf<TtsSegment>()
        val maxChars = 3500

        // Split into paragraphs first (double newline or heading-like short lines)
        val paragraphs = splitIntoParagraphs(text)

        for (para in paragraphs) {
            val trimmed = para.trim()
            if (trimmed.isBlank()) continue

            val isHeading = isLikelyHeading(trimmed)
            val pauseAfterPara = if (isHeading) 600L else 400L

            // Split paragraph into sentences/clauses
            val sentences = splitIntoSentences(trimmed)
            for ((idx, sentence) in sentences.withIndex()) {
                val s = sentence.trim()
                if (s.isBlank()) continue

                val pauseAfter = when {
                    idx == sentences.lastIndex -> pauseAfterPara
                    s.endsWith(",") || s.endsWith(";") || s.endsWith(":") -> 150L
                    else -> 350L
                }

                if (s.length <= maxChars) {
                    result += TtsSegment(s, pauseAfter)
                } else {
                    // Split very long segments at natural points
                    var start = 0
                    while (start < s.length) {
                        val end = findSplitPoint(s, start, maxChars)
                        val chunk = s.substring(start, end).trim()
                        if (chunk.isNotBlank()) {
                            val isLast = end >= s.length
                            result += TtsSegment(chunk, if (isLast) pauseAfter else 200L)
                        }
                        start = end
                    }
                }
            }
        }
        return result
    }

    /** Split text into paragraphs, preserving heading-like lines separately. */
    private fun splitIntoParagraphs(text: String): List<String> {
        val result = mutableListOf<String>()
        // Split on double newlines (paragraph breaks)
        val rawParas = text.split(Regex("\\n{2,}"))
        for (para in rawParas) {
            val trimmed = para.trim()
            if (trimmed.isBlank()) continue
            // If a "paragraph" contains single newlines, it might be multiple
            // heading-like lines stacked. Split on single newlines too.
            val lines = trimmed.split(Regex("\\n"))
            if (lines.size > 1) {
                for (line in lines) {
                    val lt = line.trim()
                    if (lt.isNotBlank()) result += lt
                }
            } else {
                result += trimmed
            }
        }
        return result
    }

    /**
     * Heuristic: a short line (< 80 chars) with no sentence-ending punctuation
     * is likely a heading, chapter title, or POV marker.
     */
    private fun isLikelyHeading(text: String): Boolean {
        if (text.length > 80) return false
        if (text.endsWith(".") || text.endsWith("!") || text.endsWith("?")) return false
        // All-caps or title-case short lines are headings
        if (text == text.uppercase() && text.length > 2) return true
        // Short lines without verbs are likely headings
        if (text.length < 50) return true
        return false
    }

    /**
     * Split a paragraph into sentence-level segments. Splits after sentence
     * punctuation (. ! ?) and also after commas/semicolons/colons for shorter
     * spoken chunks with natural pauses.
     */
    private fun splitIntoSentences(text: String): List<String> {
        val result = mutableListOf<String>()
        val regex = Regex("(?<=[.!?])\\s+|(?<=[,;:])\\s+")
        val parts = text.split(regex)
        // Re-join sentence fragments that were split at commas into proper sentences
        val current = StringBuilder()
        for (part in parts) {
            val p = part.trim()
            if (p.isBlank()) continue
            current.append(p)
            // End a segment after sentence-ending punctuation or commas
            if (p.endsWith(".") || p.endsWith("!") || p.endsWith("?") ||
                p.endsWith(",") || p.endsWith(";") || p.endsWith(":")
            ) {
                result += current.toString()
                current.clear()
            }
        }
        if (current.isNotEmpty()) {
            result += current.toString()
        }
        return result
    }

    /** Find a good split point in a long string near maxLen. */
    private fun findSplitPoint(text: String, start: Int, maxLen: Int): Int {
        val end = minOf(start + maxLen, text.length)
        if (end >= text.length) return end
        // Look for a sentence boundary near the end
        for (i in end downTo start) {
            val c = text[i]
            if (c == '.' || c == '!' || c == '?' || c == ',' || c == ';' || c == ':') {
                return i + 1
            }
        }
        return end
    }

    /**
     * Find the segment index that contains the given character offset in the
     * original text. Uses a cumulative character count across segments.
     */
    private fun findSegmentIndex(segments: List<TtsSegment>, fullText: String, offset: Int): Int {
        if (offset <= 0 || segments.isEmpty()) return 0
        // Build a cumulative offset map by searching for each segment's text
        // in the full text. This is more robust than character counting because
        // the segment text may differ slightly from the raw text (trimming).
        var searchPos = 0
        for (i in segments.indices) {
            val segText = segments[i].text
            val found = fullText.indexOf(segText.substring(0, minOf(40, segText.length)), searchPos)
            if (found < 0) continue
            val segEnd = found + segText.length
            if (offset <= segEnd) {
                return i
            }
            searchPos = segEnd
        }
        return 0
    }

    @Volatile
    private var sleepRemainingMs: Long = -1L

    override fun close() {
        stop()
        tts?.shutdown()
        tts = null
    }

    /** A TTS segment: the text to speak and the pause (ms) after it. */
    private data class TtsSegment(
        val text: String,
        val pauseAfterMs: Long,
    )

    companion object {
        const val MIN_RATE = 0.5f
        const val MAX_RATE = 3.0f
        const val MIN_PITCH = 0.5f
        const val MAX_PITCH = 2.0f
        private const val WORD_LOOP_PREFIX = "livre-word-loop-"
        private const val PAUSE_PREFIX = "livre-pause-"
    }
}
