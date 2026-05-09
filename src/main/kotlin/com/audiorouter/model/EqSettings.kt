package com.audiorouter.model

import kotlinx.serialization.Serializable

/**
 * 10-band graphic EQ settings for a single channel.
 *
 * Bands map to mbeq LADSPA plugin (plugin ID 1197) frequencies:
 * 50, 100, 155, 311, 622, 1250, 2500, 5000, 10000, 20000 Hz
 * (mbeq indices 0, 1, 2, 4, 6, 8, 10, 12, 13, 14; remaining bands left at 0 dB)
 *
 * @property enabled Whether EQ processing is active for this channel.
 * @property gains   10 gain values in dB, range -12..+12, index 0 = lowest frequency.
 */
@Serializable
data class EqSettings(
    val enabled: Boolean = false,
    val gains: List<Float> = List(BAND_COUNT) { 0f }
) {
    companion object {
        const val BAND_COUNT = 10
        const val GAIN_MIN = -12f
        const val GAIN_MAX = 12f

        /** Human-readable frequency labels for each of the 10 bands. */
        val BAND_LABELS = listOf("50", "100", "155", "311", "622", "1.25k", "2.5k", "5k", "10k", "20k")

        /** Mapping from UI band index (0–9) to mbeq band index (0–14). */
        val UI_TO_MBEQ = intArrayOf(0, 1, 2, 4, 6, 8, 10, 12, 13, 14)
    }

    /** Converts the 10 UI gains to the 15-value mbeq control string (unused bands = 0). */
    fun toMbeqControlString(): String {
        val mbeq = FloatArray(15) { 0f }
        gains.forEachIndexed { i, gain -> if (i < UI_TO_MBEQ.size) mbeq[UI_TO_MBEQ[i]] = gain }
        return mbeq.joinToString(",")
    }

    fun withGain(bandIndex: Int, gainDb: Float): EqSettings =
        copy(gains = gains.toMutableList().also { it[bandIndex] = gainDb.coerceIn(GAIN_MIN, GAIN_MAX) })

    fun flat(): EqSettings = copy(gains = List(BAND_COUNT) { 0f })
}
