package com.hikari.app.dub

import java.lang.reflect.Method

/**
 * The voice-fingerprint model (a speaker-embedding network, run by
 * sherpa-onnx). It is reached by reflection on purpose: if the sherpa-onnx
 * speaker classes are ever missing from a build, [create] just returns null
 * and the voice scan carries on with pitch alone - the app still compiles and
 * the dub still works.
 */
internal class SpeakerEmbedder private constructor(
    private val extractor: Any,
    private val createStream: Method,
    private val createArgs: Array<Any>,
    private val streamAccept: Method,
    private val streamFinished: Method,
    private val streamRelease: Method?,
    private val isReady: Method,
    private val compute: Method,
    private val release: Method?,
) : Embedder {

    override fun embed(pcm: FloatArray): FloatArray? {
        val stream = createStream.invoke(extractor, *createArgs) ?: return null
        try {
            streamAccept.invoke(stream, pcm, 16000)
            streamFinished.invoke(stream)
            val ready = isReady.invoke(extractor, stream)
            if (ready is Boolean && !ready) return null
            return compute.invoke(extractor, stream) as? FloatArray
        } finally {
            try {
                streamRelease?.invoke(stream)
            } catch (t: Throwable) {
            }
        }
    }

    override fun close() {
        try {
            release?.invoke(extractor)
        } catch (t: Throwable) {
        }
    }

    companion object {
        private const val PKG = "com.k2fsa.sherpa.onnx."

        fun create(modelPath: String, threads: Int): SpeakerEmbedder? {
            return try {
                val cfgCls = Class.forName(PKG + "SpeakerEmbeddingExtractorConfig")
                val extCls = Class.forName(PKG + "SpeakerEmbeddingExtractor")

                // config: first text = the model file, second text = "cpu", number = threads, flag = false
                val cfgCtor = cfgCls.constructors.filter { !it.isSynthetic }
                    .maxByOrNull { it.parameterTypes.size } ?: return null
                val cfgArgs = arrayOfNulls<Any>(cfgCtor.parameterTypes.size)
                var texts = 0
                for (i in cfgCtor.parameterTypes.indices) {
                    val t = cfgCtor.parameterTypes[i]
                    if (t == String::class.java) {
                        cfgArgs[i] = if (texts == 0) modelPath else "cpu"
                        texts++
                    } else if (t == Int::class.javaPrimitiveType) {
                        cfgArgs[i] = threads
                    } else if (t == Boolean::class.javaPrimitiveType) {
                        cfgArgs[i] = false
                    } else {
                        return null
                    }
                }
                val cfg = cfgCtor.newInstance(*cfgArgs)

                // extractor: (config) or (assetManager, config); the asset manager stays null
                val extCtor = extCls.constructors.filter { !it.isSynthetic }.firstOrNull { c ->
                    val p = c.parameterTypes
                    p.isNotEmpty() && p[p.size - 1] == cfgCls
                } ?: return null
                val extArgs = arrayOfNulls<Any>(extCtor.parameterTypes.size)
                extArgs[extArgs.size - 1] = cfg
                val extractor = extCtor.newInstance(*extArgs)

                fun find(cls: Class<*>, name: String, params: Int): Method? =
                    cls.methods.firstOrNull { it.name == name && it.parameterTypes.size == params && !it.isSynthetic }

                val create0 = find(extCls, "createStream", 0)
                val createStream = create0 ?: find(extCls, "createStream", 1) ?: return null
                val createArgs: Array<Any> = if (create0 != null) emptyArray() else arrayOf("")
                val streamCls = createStream.returnType
                val accept = streamCls.methods.firstOrNull {
                    it.name == "acceptWaveform" && it.parameterTypes.size == 2 && !it.isSynthetic
                } ?: return null
                val finished = find(streamCls, "inputFinished", 0) ?: return null
                val relStream = find(streamCls, "release", 0)
                val ready = find(extCls, "isReady", 1) ?: return null
                val compute = find(extCls, "compute", 1) ?: return null
                val rel = find(extCls, "release", 0)
                SpeakerEmbedder(extractor, createStream, createArgs, accept, finished, relStream, ready, compute, rel)
            } catch (t: Throwable) {
                null
            }
        }
    }
}
