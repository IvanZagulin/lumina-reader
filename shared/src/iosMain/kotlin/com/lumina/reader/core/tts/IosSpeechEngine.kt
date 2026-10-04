@file:OptIn(ExperimentalForeignApi::class)

package com.lumina.reader.core.tts

import com.lumina.reader.platform.LanguageTag
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCSignatureOverride
import platform.AVFAudio.AVSpeechBoundary
import platform.AVFAudio.AVSpeechSynthesisVoice
import platform.AVFAudio.AVSpeechSynthesizer
import platform.AVFAudio.AVSpeechSynthesizerDelegateProtocol
import platform.AVFAudio.AVSpeechUtterance
import platform.AVFAudio.AVSpeechUtteranceDefaultSpeechRate
import platform.AVFAudio.AVSpeechUtteranceMaximumSpeechRate
import platform.AVFAudio.AVSpeechUtteranceMinimumSpeechRate
import platform.darwin.NSObject
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue

/**
 * [SpeechEngine] over AVSpeechSynthesizer, the iPhone's counterpart of
 * AndroidSpeechEngine. Every callback is re-posted to the main queue, where
 * [TtsPlayer] lives.
 *
 * AVSpeechSynthesizer has no asynchronous start-up and no per-utterance error,
 * so [SpeechEngineCallbacks.onInit] is reported from the constructor (the
 * player accepts an init result that arrives before the factory returns) and
 * [speak] always succeeds.
 */
internal class IosSpeechEngine(private val callbacks: SpeechEngineCallbacks) : SpeechEngine {

    private val synthesizer = AVSpeechSynthesizer()

    // The synthesizer holds its delegate weakly; this field keeps it alive.
    private val delegate = SynthesizerDelegate(onFinished = ::onFinished)

    private var released = false

    /** Applied to every new utterance: AVSpeechUtterance carries rate, pitch and voice itself. */
    private var rate: Float = AVSpeechUtteranceDefaultSpeechRate
    private var pitch: Float = 1f
    private var voice: AVSpeechSynthesisVoice? = null

    /** The utterance being spoken and its player id; a finish of any other one is stale. */
    private var current: AVSpeechUtterance? = null
    private var currentId: String? = null

    init {
        synthesizer.delegate = delegate
        callbacks.onInit(true)
    }

    override fun speak(text: String, utteranceId: String): Boolean {
        if (released) return false
        val utterance = AVSpeechUtterance(string = text)
        utterance.rate = rate
        utterance.pitchMultiplier = pitch
        voice?.let { utterance.voice = it }
        // Replaces whatever is being spoken (QUEUE_FLUSH on Android). The
        // replaced utterance reports didCancel, which is ignored like
        // Android's onStop: the player already moved on.
        synthesizer.stopSpeakingAtBoundary(AVSpeechBoundary.AVSpeechBoundaryImmediate)
        current = utterance
        currentId = utteranceId
        synthesizer.speakUtterance(utterance)
        return true
    }

    override fun stop() {
        current = null
        currentId = null
        synthesizer.stopSpeakingAtBoundary(AVSpeechBoundary.AVSpeechBoundaryImmediate)
    }

    override fun setSpeechRate(rate: Float) {
        this.rate = avSpeechRate(rate)
    }

    override fun setPitch(pitch: Float) {
        // pitchMultiplier has Android's scale (1 is the normal voice) but only 0.5…2.
        this.pitch = pitch.coerceIn(MIN_PITCH_MULTIPLIER, MAX_PITCH_MULTIPLIER)
    }

    override fun setLanguage(language: LanguageTag): TtsLanguageSupport {
        val found = voiceFor(language) ?: return TtsLanguageSupport.NOT_SUPPORTED
        voice = found
        return TtsLanguageSupport.AVAILABLE
    }

    override fun shutdown() {
        released = true
        current = null
        currentId = null
        synthesizer.delegate = null
        synthesizer.stopSpeakingAtBoundary(AVSpeechBoundary.AVSpeechBoundaryImmediate)
    }

    private fun onFinished(utterance: AVSpeechUtterance) {
        dispatch_async(dispatch_get_main_queue()) {
            if (released || utterance !== current) return@dispatch_async
            val id = currentId ?: return@dispatch_async
            current = null
            currentId = null
            callbacks.onUtteranceDone(id)
        }
    }

    private companion object {
        const val MIN_PITCH_MULTIPLIER = 0.5f
        const val MAX_PITCH_MULTIPLIER = 2f
    }
}

/**
 * The voice for [language]. A bare language ("en", which the detector
 * returns for Latin text) names no voice on every iOS version, so any voice
 * of that language is taken then.
 */
private fun voiceFor(language: LanguageTag): AVSpeechSynthesisVoice? {
    AVSpeechSynthesisVoice.voiceWithLanguage(language.bcp47)?.let { return it }
    val primary = language.bcp47.substringBefore('-').substringBefore('_')
    return AVSpeechSynthesisVoice.speechVoices()
        .filterIsInstance<AVSpeechSynthesisVoice>()
        .firstOrNull { it.language.substringBefore('-').equals(primary, ignoreCase = true) }
}

/**
 * [TtsPlayer]'s speech rate (Android's scale: 1 is normal, the sheet offers
 * 0.5…3) on AVSpeechUtterance's scale, where normal is
 * AVSpeechUtteranceDefaultSpeechRate (0.5) inside 0…1. Slower rates scale the
 * default down; faster ones spread 1…3 over the rest of the range.
 */
internal fun avSpeechRate(rate: Float): Float {
    val default = AVSpeechUtteranceDefaultSpeechRate
    val max = AVSpeechUtteranceMaximumSpeechRate
    val value = if (rate <= 1f) {
        default * rate
    } else {
        default + (max - default) * ((rate - 1f) / (FASTEST_SHEET_RATE - 1f))
    }
    return value.coerceIn(AVSpeechUtteranceMinimumSpeechRate, max)
}

/** The fastest speed of the player sheet, mapped to AVSpeechUtteranceMaximumSpeechRate. */
private const val FASTEST_SHEET_RATE = 3f

/**
 * Forwards the end of an utterance. didStart is there for symmetry with
 * Android's onStart and does nothing; didCancel only follows our own stop
 * and is left out, as on Android.
 */
private class SynthesizerDelegate(
    private val onFinished: (AVSpeechUtterance) -> Unit
) : NSObject(), AVSpeechSynthesizerDelegateProtocol {

    // Every delegate method has the same Kotlin signature; the annotation lets
    // Kotlin tell the Objective-C selectors apart.
    @ObjCSignatureOverride
    override fun speechSynthesizer(synthesizer: AVSpeechSynthesizer, didStartSpeechUtterance: AVSpeechUtterance) = Unit

    @ObjCSignatureOverride
    override fun speechSynthesizer(synthesizer: AVSpeechSynthesizer, didFinishSpeechUtterance: AVSpeechUtterance) {
        onFinished(didFinishSpeechUtterance)
    }
}
