package com.pimax.whisperflow.local

import android.util.Log
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Whisper vocabulary and tokenizer.
 *
 * Loads vocabulary from the binary filters_vocab file format used by whisperIME/DocWolle models.
 * Handles decoding token IDs to text and provides access to special tokens.
 *
 * Binary file format:
 * - Magic: 0x5553454e ("USEN")
 * - nMel: int32
 * - nFft: int32
 * - filterData: float32[nMel * nFft]
 * - nVocab: int32
 * - For each vocab entry: len (int32), word (bytes[len])
 */
class WhisperVocab private constructor(
    val filters: MelFilters,
    private val tokenToWord: Map<Int, ByteArray>,
    private val isMultilingual: Boolean
) {
    companion object {
        private const val TAG = "WhisperVocab"
        private const val MAGIC = 0x5553454e // "USEN" in little-endian

        // Base token values (English-only model)
        private const val BASE_TOKEN_EOT = 50256
        private const val BASE_TOKEN_SOT = 50257
        private const val BASE_TOKEN_TRANSLATE = 50358
        private const val BASE_TOKEN_TRANSCRIBE = 50359
        private const val BASE_TOKEN_PREV = 50360
        private const val BASE_TOKEN_SOLM = 50361
        private const val BASE_TOKEN_NOT = 50362
        private const val BASE_TOKEN_BEG = 50363

        // Vocab sizes
        private const val N_VOCAB_ENGLISH = 51864
        private const val N_VOCAB_MULTILINGUAL = 51865

        // Language codes in Whisper's order (indices 0-98)
        private val LANGUAGE_CODES = listOf(
            "en", "zh", "de", "es", "ru", "ko", "fr", "ja", "pt", "tr",
            "pl", "ca", "nl", "ar", "sv", "it", "id", "hi", "fi", "vi",
            "he", "uk", "el", "ms", "cs", "ro", "da", "hu", "ta", "no",
            "th", "ur", "hr", "bg", "lt", "la", "mi", "ml", "cy", "sk",
            "te", "fa", "lv", "bn", "sr", "az", "sl", "kn", "et", "mk",
            "br", "eu", "is", "hy", "ne", "mn", "bs", "kk", "sq", "sw",
            "gl", "mr", "pa", "si", "km", "sn", "yo", "so", "af", "oc",
            "ka", "be", "tg", "sd", "gu", "am", "yi", "lo", "uz", "fo",
            "ht", "ps", "tk", "nn", "mt", "sa", "lb", "my", "bo", "tl",
            "mg", "as", "tt", "haw", "ln", "ha", "ba", "jw", "su"
        )

        /**
         * Load vocabulary and mel filters from binary file.
         *
         * @param inputStream Input stream to the filters_vocab_*.bin file
         * @param multilingual Whether this is a multilingual model (affects token offsets)
         * @return WhisperVocab instance
         * @throws IllegalArgumentException if file format is invalid
         */
        fun load(inputStream: InputStream, multilingual: Boolean = true): WhisperVocab {
            val bytes = inputStream.readBytes()
            val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.nativeOrder())

            // Validate magic number
            val magic = buffer.int
            if (magic != MAGIC) {
                throw IllegalArgumentException(
                    "Invalid vocab file: bad magic number 0x${magic.toString(16)}, expected 0x${MAGIC.toString(16)}"
                )
            }
            Log.d(TAG, "Magic number validated: 0x${magic.toString(16)}")

            // Load mel filters
            val nMel = buffer.int
            val nFft = buffer.int
            Log.d(TAG, "Mel filters: nMel=$nMel, nFft=$nFft")

            val filterData = FloatArray(nMel * nFft)
            for (i in filterData.indices) {
                filterData[i] = buffer.float
            }
            val filters = MelFilters(nMel, nFft, filterData)

            // Load vocabulary
            val nVocab = buffer.int
            Log.d(TAG, "Vocabulary size: $nVocab")

            val tokenToWord = mutableMapOf<Int, ByteArray>()

            // Read base vocabulary from file
            for (i in 0 until nVocab) {
                val len = buffer.int
                val wordBytes = ByteArray(len)
                buffer.get(wordBytes)
                tokenToWord[i] = wordBytes
            }

            // Add special tokens
            val totalVocab = if (multilingual) N_VOCAB_MULTILINGUAL else N_VOCAB_ENGLISH

            // Correct token IDs based on model type
            val tokenEot = if (multilingual) 50257 else 50256
            val tokenSot = if (multilingual) 50258 else 50257
            val tokenPrev = if (multilingual) 50360 else 50260
            val tokenSolm = if (multilingual) 50361 else 50261
            val tokenNot = if (multilingual) 50362 else 50262
            val tokenBeg = if (multilingual) 50363 else 50263

            for (i in nVocab until totalVocab) {
                val word = when (i) {
                    tokenEot -> "[_EOT_]"
                    tokenSot -> "[_SOT_]"
                    tokenPrev -> "[_PREV_]"
                    tokenSolm -> "[_SOLM_]"
                    tokenNot -> "[_NOT_]"
                    tokenBeg -> "[_BEG_]"
                    else -> if (i > tokenBeg) {
                        "[_TT_${i - tokenBeg}]"
                    } else {
                        "[_extra_token_$i]"
                    }
                }
                tokenToWord[i] = word.toByteArray(Charsets.UTF_8)
            }

            Log.d(TAG, "Loaded ${tokenToWord.size} tokens (multilingual=$multilingual)")

            return WhisperVocab(filters, tokenToWord, multilingual)
        }
    }

    // Special token IDs — direct values for each model type
    // Multilingual models have 99 language tokens (50259-50357) between SOT and task tokens
    // English-only models have task tokens immediately after SOT
    val tokenEOT: Int
    val tokenSOT: Int
    val tokenTranslate: Int
    val tokenTranscribe: Int
    val tokenPrev: Int
    val tokenSolm: Int
    val tokenNot: Int
    val tokenBeg: Int

    // Language token range (multilingual only)
    val languageTokenStart: Int
    val languageTokenEnd: Int

    init {
        if (isMultilingual) {
            tokenEOT = 50257
            tokenSOT = 50258
            languageTokenStart = 50259
            languageTokenEnd = 50357
            tokenTranslate = 50358
            tokenTranscribe = 50359
            tokenPrev = 50360
            tokenSolm = 50361
            tokenNot = 50362
            tokenBeg = 50363
        } else {
            tokenEOT = 50256
            tokenSOT = 50257
            languageTokenStart = -1  // No language tokens in English-only
            languageTokenEnd = -2    // Empty range
            tokenTranslate = 50258
            tokenTranscribe = 50259
            tokenPrev = 50260
            tokenSolm = 50261
            tokenNot = 50262
            tokenBeg = 50263
        }
    }

    /**
     * Decode a single token ID to bytes.
     */
    fun getTokenBytes(tokenId: Int): ByteArray? = tokenToWord[tokenId]

    /**
     * Decode a single token ID to string.
     */
    fun getTokenString(tokenId: Int): String? {
        return tokenToWord[tokenId]?.toString(Charsets.UTF_8)
    }

    /**
     * Check if token is a special token (should be skipped in output).
     */
    fun isSpecialToken(tokenId: Int): Boolean {
        return tokenId >= tokenEOT
    }

    /**
     * Check if token is a language token.
     */
    fun isLanguageToken(tokenId: Int): Boolean {
        return tokenId in languageTokenStart..languageTokenEnd
    }

    /**
     * Get the language token ID for a given language code (e.g., "en", "de", "es").
     * Returns null if the language code is not found or model is not multilingual.
     */
    fun languageTokenFor(langCode: String): Int? {
        if (!isMultilingual) return null
        val code = langCode.lowercase().trim()
        if (code == "auto" || code.isEmpty()) return null
        val index = LANGUAGE_CODES.indexOf(code)
        if (index < 0) return null
        return languageTokenStart + index
    }

    /**
     * Check if token is the transcribe task token.
     */
    fun isTranscribeToken(tokenId: Int): Boolean = tokenId == tokenTranscribe

    /**
     * Check if token is the translate task token.
     */
    fun isTranslateToken(tokenId: Int): Boolean = tokenId == tokenTranslate

    /**
     * Decode a sequence of token IDs to text.
     *
     * @param tokens Array of token IDs from model output
     * @param skipSpecial Whether to skip special tokens (default: true)
     * @return Decoded text string
     */
    fun decode(tokens: IntArray, skipSpecial: Boolean = true): String {
        val resultBytes = mutableListOf<Byte>()

        for (token in tokens) {
            // Stop at end-of-text token
            if (token == tokenEOT) break

            // Skip special tokens if requested
            if (skipSpecial && isSpecialToken(token)) continue

            // Get token bytes and append
            tokenToWord[token]?.let { bytes ->
                resultBytes.addAll(bytes.toList())
            }
        }

        return resultBytes.toByteArray().toString(Charsets.UTF_8)
    }

    /**
     * Decode tokens with metadata about detected language and task.
     *
     * @param tokens Array of token IDs from model output
     * @return DecodeResult with text, detected language, and task
     */
    fun decodeWithMetadata(tokens: IntArray): DecodeResult {
        var detectedLanguage: String? = null
        var task: Task? = null
        val resultBytes = mutableListOf<Byte>()

        for (token in tokens) {
            when {
                token == tokenEOT -> break

                isLanguageToken(token) -> {
                    // Extract language code from token
                    detectedLanguage = LANGUAGE_CODES.getOrNull(token - languageTokenStart)
                    Log.d(TAG, "Detected language token: $token -> $detectedLanguage")
                }

                token == tokenTranscribe -> {
                    task = Task.TRANSCRIBE
                    Log.d(TAG, "Task: TRANSCRIBE")
                }

                token == tokenTranslate -> {
                    task = Task.TRANSLATE
                    Log.d(TAG, "Task: TRANSLATE")
                }

                !isSpecialToken(token) -> {
                    tokenToWord[token]?.let { bytes ->
                        resultBytes.addAll(bytes.toList())
                    }
                }
            }
        }

        return DecodeResult(
            text = resultBytes.toByteArray().toString(Charsets.UTF_8),
            language = detectedLanguage,
            task = task
        )
    }

    /**
     * Result of decoding with metadata.
     */
    data class DecodeResult(
        val text: String,
        val language: String?,
        val task: Task?
    )

    /**
     * Whisper task types.
     */
    enum class Task {
        TRANSCRIBE,
        TRANSLATE
    }
}
