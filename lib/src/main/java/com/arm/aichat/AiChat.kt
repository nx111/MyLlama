package com.arm.aichat

import android.content.Context
import com.arm.aichat.internal.InferenceEngineImpl

/**
 * Main entry point for the local llama.cpp inference library.
 */
object AiChat {
    /**
     * Get the inference engine single instance.
     */
    fun getInferenceEngine(context: Context) = InferenceEngineImpl.getInstance(context)
}
