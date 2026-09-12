package com.epubreader.app.epub

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.os.CountDownTimer
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
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
 * Phase 1: TTS now consumes a structural XHTML model rather than a flattened
 * chapter string. Paragraphs, headings, list items, quotes, and source ranges
 * are retained so later playback/highlighting phases can operate on stable
 * locations instead of global string searches.
 */
class ReaderTtsController(
    context: Context,
    private val onStateChanged: (Boolean) -> Unit,
    private val onChapterFinished: () -> Unit,
    private val onWordRange: ((segment: ReaderTtsSegment, start: Int, end: Int) -> Unit)? = null,
    private val onSleepTimerTick: ((remainingMs: Long) -> Unit)? = null,
    private val onSleepTimerFinished: (() -> Unit)? = null,
    private val onSentenceHighlight: ((segment: ReaderTtsSegment) -> Unit)? = null,
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
    private var segments: List<ReaderTtsSegment> = emptyList()
    private var segmentIndex = 0
    private var initialized = false
    private var activeUtteranceId: String? = null
    private var activeSegmentText: String = ""
    private var sleepTimer: CountDownTimer? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var wordLoopActive = false
    private var wordLoopText: String? = null
    /** Character position reported by onRangeStart for the active segment. */
    private var resumeCharOffset: Int = 0
    private var activeSegmentIndex: Int = -1
    private var activeSegmentBaseOffset: Int = 0
    /** Guards stale asynchronous TTS callbacks after stop/skip/voice changes. */
    private var playbackGeneration: Long = 0L

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
                    if (activeSegmentIndex == segmentIndex) {
                        resumeCharOffset = start.coerceIn(0, sentence.length)
                    }
                    val segment = segments.getOrNull(activeSegmentIndex)
                    if (segment != null) {
                        onWordRange?.invoke(segment, (activeSegmentBaseOffset + start).coerceAtMost(segment.text.length), (activeSegmentBaseOffset + end).coerceAtMost(segment.text.length))
                    }
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
                    if (utteranceId.startsWith(PAUSE_PREFIX)) {
                        if (utteranceId != activeUtteranceId) return
                        activeUtteranceId = null
                        if (state == State.PLAYING) {
                            advanceAfterSegment()
                        }
                        return
                    }
                    if (utteranceId.startsWith(WORD_LOOP_PREFIX)) {
                        if (utteranceId != activeUtteranceId) return
                        activeUtteranceId = null
                        if (repeatMode == RepeatMode.WORD && state == State.PLAYING) {
                            wordLoopText?.let { word -> mainHandler.post { beginWordLoop(word) } }
                        } else {
                            wordLoopActive = false
                            wordLoopText = null
                            if (state == State.PLAYING && segments.isNotEmpty()) speakCurrentSegment()
                        }
                        return
                    }
                    if (utteranceId != activeUtteranceId) return
                    activeUtteranceId = null
                    resumeCharOffset = 0

                    if (repeatMode == RepeatMode.SENTENCE && segments.isNotEmpty()) {
                        speakCurrentSegment()
                        return
                    }

                    val pauseMs = segments.getOrNull(segmentIndex)?.pauseAfterMs ?: 0L
                    if (pauseMs > 0L && state == State.PLAYING) {
                        val pauseId = PAUSE_PREFIX + UUID.randomUUID()
                        activeUtteranceId = pauseId
                        tts?.playSilentUtterance(pauseMs, TextToSpeech.QUEUE_FLUSH, pauseId)
                    } else {
                        advanceAfterSegment()
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
        val document = extractDocument(file, href)
        withContext(Dispatchers.Main) {
            if (!initialized || document.blocks.none { it.text.isNotBlank() }) {
                state = if (initialized) State.READY else State.UNAVAILABLE
                onStateChanged(false)
                return@withContext
            }
            segments = buildSegments(document)
            segmentIndex = if (startOffset > 0) {
                findSegmentIndex(segments, startOffset)
            } else {
                0
            }
            wordLoopActive = false
            wordLoopText = null
            resumeCharOffset = 0
            activeSegmentIndex = -1
            activeSegmentBaseOffset = 0
            playbackGeneration++
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
        resumeCharOffset = 0
        playbackGeneration++
        tts?.stop()
        activeUtteranceId = null
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
        playbackGeneration++
        activeUtteranceId = null
        tts?.stop()
        segments = emptyList()
        segmentIndex = 0
        resumeCharOffset = 0
        activeSegmentIndex = -1
        activeSegmentBaseOffset = 0
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
        val voice = voiceName?.let { name ->
            engine.voices.orEmpty().firstOrNull {
                it.name == name && !it.isNetworkConnectionRequired
            }
        }
        if (voice != null) {
            val result = runCatching { engine.voice = voice }.isSuccess
            if (result) return
            // A broken/offline-incompatible voice must never leave the engine
            // unusable. Fall through to the EPUB language/default locale.
            // Keep the requested name persisted so the UI can still show the
            // user's selection; the engine uses the safe locale fallback here.
        }
        val tag = bookLanguage
        val locale = if (!tag.isNullOrBlank()) Locale.forLanguageTag(tag) else Locale.getDefault()
        val result = runCatching { engine.setLanguage(locale) }.getOrNull()
        if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
            engine.setLanguage(Locale.getDefault())
        }
    }

    private fun pause() {
        playbackGeneration++
        activeUtteranceId = null
        tts?.stop()
        wordLoopActive = false
        wordLoopText = null
        // TextToSpeech has no portable pause-at-character API. We stop the
        // utterance and retain the last onRangeStart position, then resume by
        // speaking the unspoken suffix of the same segment.
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
        val fullText = segment.text
        val from = resumeCharOffset.coerceIn(0, fullText.length)
        if (from >= fullText.length) {
            resumeCharOffset = 0
            advanceAfterSegment()
            return
        }

        playbackGeneration++
        val id = UUID.randomUUID().toString()
        activeUtteranceId = id
        activeSegmentIndex = segmentIndex
        activeSegmentBaseOffset = from
        activeSegmentText = fullText.substring(from)
        // The callback receives the original segment. Word offsets are relative
        // to the spoken suffix and are translated by ReaderActivity using the
        // same suffix offset when needed.
        onSentenceHighlight?.invoke(segment)
        tts?.speak(activeSegmentText, TextToSpeech.QUEUE_FLUSH, null, id)
    }

    private fun advanceAfterSegment() {
        resumeCharOffset = 0
        activeSegmentIndex = -1
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

    private fun releaseAudioFocus() {
        audioManager?.abandonAudioFocus(audioFocusListener)
    }

    /**
     * Parse the XHTML into a TTS-specific structure. Unlike the old
     * extractText() path, this does not flatten the chapter before we know
     * where its paragraphs/headings came from.
     */
    private suspend fun extractDocument(file: File, href: String): ReaderTtsDocument =
        withContext(Dispatchers.IO) {
            runCatching {
                ZipFile(file).use { zip ->
                    val entry = zip.getEntry(href)
                        ?: zip.entries().toList().firstOrNull { it.name.equals(href, true) }
                    val html = entry?.let {
                        zip.getInputStream(it).bufferedReader(Charsets.UTF_8).use { reader ->
                            reader.readText()
                        }
                    }.orEmpty()
                    ReaderTtsDocumentBuilder.build(href, html)
                }
            }.getOrElse {
                ReaderTtsDocument(href, "", emptyList())
            }
        }

    /**
     * Build speech units from structural blocks. Structural XHTML headings
     * receive a longer pause; real paragraph/list/quote boundaries receive a
     * paragraph pause. Sentence/clause punctuation still controls shorter
     * pauses inside a block.
     */
    private fun buildSegments(document: ReaderTtsDocument): List<ReaderTtsSegment> {
        val result = mutableListOf<ReaderTtsSegment>()
        val maxChars = 3500

        for ((blockIndex, block) in document.blocks.withIndex()) {
            val text = block.text.trim()
            if (text.isBlank()) continue

            val isHeading = block.kind == ReaderTtsBlockKind.HEADING ||
                (block.kind == ReaderTtsBlockKind.OTHER && isLikelyHeading(text))
            val paragraphPause = if (isHeading) 700L else 450L
            val sentences = splitIntoSentences(text)
            var localSearchStart = 0

            for ((idx, sentence) in sentences.withIndex()) {
                val spoken = sentence.trim()
                if (spoken.isBlank()) continue

                val localStart = text.indexOf(spoken, localSearchStart).coerceAtLeast(0)
                val localEnd = (localStart + spoken.length).coerceAtMost(text.length)
                localSearchStart = localEnd

                val pauseAfter = when {
                    idx == sentences.lastIndex -> paragraphPause
                    spoken.endsWith(",") -> 180L
                    spoken.endsWith(";") || spoken.endsWith(":") -> 220L
                    else -> 360L
                }

                if (spoken.length <= maxChars) {
                    result += ReaderTtsSegment(
                        text = spoken,
                        pauseAfterMs = pauseAfter,
                        rawStart = mapBlockOffsetToRaw(block, localStart, text.length),
                        rawEnd = mapBlockOffsetToRaw(block, localEnd, text.length),
                        blockIndex = blockIndex,
                    )
                } else {
                    var start = 0
                    while (start < spoken.length) {
                        val end = findSplitPoint(spoken, start, maxChars)
                        val chunk = spoken.substring(start, end).trim()
                        if (chunk.isNotBlank()) {
                            val chunkLocalStart = localStart + start
                            val chunkLocalEnd = localStart + end
                            result += ReaderTtsSegment(
                                text = chunk,
                                pauseAfterMs = if (end >= spoken.length) pauseAfter else 220L,
                                rawStart = mapBlockOffsetToRaw(block, chunkLocalStart, text.length),
                                rawEnd = mapBlockOffsetToRaw(block, chunkLocalEnd, text.length),
                                blockIndex = blockIndex,
                            )
                        }
                        start = end
                    }
                }
            }
        }
        return result
    }

    /**
     * Heuristic fallback for block types that are not explicit h1-h6 headings.
     */
    private fun isLikelyHeading(text: String): Boolean {
        if (text.length > 80) return false
        if (text.endsWith(".") || text.endsWith("!") || text.endsWith("?")) return false
        if (text == text.uppercase() && text.length > 2) return true
        return text.length < 50
    }

    /**
     * Split a structural block into speech-sized punctuation units.
     */
    private fun splitIntoSentences(text: String): List<String> {
        val result = mutableListOf<String>()
        var start = 0
        var i = 0
        while (i < text.length) {
            val c = text[i]
            val boundary = c == '.' || c == '!' || c == '?' || c == ',' || c == ';' || c == ':'
            if (boundary) {
                // Keep closing quotation/bracket characters with the punctuation.
                var end = i + 1
                while (end < text.length && text[end] in "\"'”’»)]}") end++
                // Only split when punctuation is followed by whitespace/end. This
                // avoids breaking decimals, initials and abbreviations such as 3.14.
                val nextIsBoundary = end >= text.length || text[end].isWhitespace()
                if (nextIsBoundary) {
                    val part = text.substring(start, end).trim()
                    if (part.isNotBlank()) result += part
                    start = end
                    while (start < text.length && text[start].isWhitespace()) start++
                    i = start
                    continue
                }
            }
            i++
        }
        if (start < text.length) {
            val tail = text.substring(start).trim()
            if (tail.isNotBlank()) result += tail
        }
        return result
    }

    /** Find a safe split point for Android TTS utterance size limits. */
    private fun findSplitPoint(text: String, start: Int, maxLen: Int): Int {
        val end = minOf(start + maxLen, text.length)
        if (end >= text.length) return end
        for (i in end downTo start) {
            when (text[i]) {
                '.', '!', '?', ',', ';', ':' -> return i + 1
            }
        }
        return end
    }

    /**
     * Block text is whitespace-normalized while rawText follows DOM text-node
     * order. A proportional mapping is therefore used until Phase 3 introduces
     * exact text-node locators. The mapping is deliberately bounded to the
     * block's source range and is already much safer than searching for strings
     * across the whole chapter.
     */
    private fun mapBlockOffsetToRaw(block: ReaderTtsBlock, offset: Int, textLength: Int): Int {
        if (textLength <= 0) return block.rawStart
        val ratio = offset.coerceIn(0, textLength).toDouble() / textLength.toDouble()
        return (block.rawStart + ((block.rawEnd - block.rawStart) * ratio).toInt())
            .coerceIn(block.rawStart, block.rawEnd)
    }

    /**
     * Resolve the WebView's current DOM text offset to the structural TTS unit
     * that contains it. If the offset lands in whitespace between blocks, use
     * the next readable block instead of falling back to chapter zero.
     */
    private fun findSegmentIndex(segments: List<ReaderTtsSegment>, offset: Int): Int {
        if (segments.isEmpty() || offset <= 0) return 0
        val containing = segments.indexOfFirst { offset <= it.rawEnd && offset >= it.rawStart }
        if (containing >= 0) return containing
        val next = segments.indexOfFirst { it.rawStart >= offset }
        return if (next >= 0) next else segments.lastIndex
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
        private const val PAUSE_PREFIX = "livre-pause-"
    }
}
