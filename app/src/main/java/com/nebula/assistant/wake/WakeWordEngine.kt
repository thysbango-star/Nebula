package com.nebula.assistant.wake

import android.content.Context
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.KeywordSpotter
import com.k2fsa.sherpa.onnx.KeywordSpotterConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineStream
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import java.io.Closeable

/**
 * Offline "Hey Nebula" detector built on sherpa-onnx keyword spotting.
 *
 * The model is a tiny (≈5 MB) English streaming recognizer trained on GigaSpeech.
 * The phrase itself lives in assets/kws/keywords.txt as model tokens, so adding
 * more wake phrases later is just a matter of adding lines there.
 *
 * Thread-safe: [accept] and [close] can be called from different threads.
 */
class WakeWordEngine(context: Context, threshold: Float) : Closeable {

    private val spotter: KeywordSpotter
    private var stream: OnlineStream
    private var closed = false

    init {
        val config = KeywordSpotterConfig(
            featConfig = FeatureConfig(sampleRate = SAMPLE_RATE, featureDim = 80),
            modelConfig = OnlineModelConfig(
                transducer = OnlineTransducerModelConfig(
                    encoder = "kws/encoder.int8.onnx",
                    decoder = "kws/decoder.int8.onnx",
                    joiner = "kws/joiner.int8.onnx",
                ),
                tokens = "kws/tokens.txt",
                modelType = "zipformer2",
                numThreads = 1,
                provider = "cpu",
                debug = false,
            ),
            maxActivePaths = 4,
            keywordsFile = "kws/keywords.txt",
            keywordsScore = 1.5f,
            keywordsThreshold = threshold,
            numTrailingBlanks = 1,
        )
        spotter = KeywordSpotter(context.assets, config)
        stream = spotter.createStream()
    }

    /** Feed 16 kHz mono samples in [-1, 1]. Returns the keyword name when the wake word is heard. */
    @Synchronized
    fun accept(samples: FloatArray): String? {
        if (closed) return null
        stream.acceptWaveform(samples, SAMPLE_RATE)
        while (spotter.isReady(stream)) {
            spotter.decode(stream)
            val keyword = spotter.getResult(stream).keyword
            if (keyword.isNotBlank()) {
                spotter.reset(stream)
                return keyword
            }
        }
        return null
    }

    /** Start fresh (e.g. after a pause) so old audio can't trigger a wake. */
    @Synchronized
    fun resetStream() {
        if (closed) return
        stream.release()
        stream = spotter.createStream()
    }

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        stream.release()
        spotter.release()
    }

    companion object {
        const val SAMPLE_RATE = 16_000
    }
}
