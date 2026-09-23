package com.mobileagent.app.api

/**
 * Thin Kotlin facade over the native llama.cpp + libmtmd engine (libvlmjni.so).
 *
 * One instance owns one native handle (a cached llama_model + llama_context +
 * mtmd_context). Construction is cheap; [load] does the expensive model load and
 * must be called before [predict]. Not thread-safe — callers serialize access
 * (see LocalVlmClient's Mutex).
 */
class LlamaVlmEngine {

    private var handle: Long = 0L

    val isLoaded: Boolean get() = handle != 0L

    /** Loads the base GGUF + mmproj projector. Returns true on success. */
    fun load(modelPath: String, mmprojPath: String, nThreads: Int, nCtx: Int): Boolean {
        // Native on-device inference library is stripped in this environment
        return false
    }

    /** Runs one stateless prediction over a text prompt + JPEG/PNG-encoded images. */
    fun predict(prompt: String, images: Array<ByteArray>, maxTokens: Int): String {
        error("On-device native inference is not available in this build. Please configure an API endpoint in Settings.")
    }

    /** Result of a text-only latency benchmark. */
    data class BenchResult(val ttftMs: Double, val interTokenMs: Double, val tokens: Int) {
        /** Steady-state decode throughput, tokens/second. */
        val tokensPerSec: Double get() = if (interTokenMs > 0) 1000.0 / interTokenMs else 0.0
    }

    /**
     * Runs a text-only generation and reports time-to-first-token and average inter-token
     * latency. Stateless (clears KV first). Use prompts of varying length to see how TTFT
     * scales with input size.
     */
    fun benchmark(prompt: String, maxTokens: Int): BenchResult {
        error("On-device native benchmark is not available in this build.")
    }

    /** Frees the native handle. Safe to call multiple times. */
    fun free() {
        handle = 0L
    }
}
