package com.audiorouter.service

import kotlin.math.sqrt
import kotlin.test.*

class LevelMonitorTest {

    // ── computeStereoRms ──────────────────────────────────────────────────

    @Test
    fun `returns zero pair when validBytes is less than 4`() {
        val result = computeStereoRms(ByteArray(8), 3)
        assertEquals(0f to 0f, result)
    }

    @Test
    fun `returns zero pair for silent buffer`() {
        val result = computeStereoRms(ByteArray(16), 16)
        assertEquals(0f to 0f, result)
    }

    @Test
    fun `computes correct RMS for full-scale positive left only`() {
        // s16le full positive = 0x7FFF = 32767; bytes [0xFF, 0x7F]
        // Right channel = 0
        val buffer = ByteArray(4).apply {
            this[0] = 0xFF.toByte()  // L low byte
            this[1] = 0x7F.toByte()  // L high byte → 32767
            this[2] = 0x00           // R low byte
            this[3] = 0x00           // R high byte → 0
        }
        val (rmsL, rmsR) = computeStereoRms(buffer, 4)
        // 32767/32768 ≈ 0.99997
        assertTrue(rmsL > 0.99f, "Expected rmsL near 1.0 but got $rmsL")
        assertEquals(0f, rmsR)
    }

    @Test
    fun `computes correct RMS for full-scale positive right only`() {
        val buffer = ByteArray(4).apply {
            this[0] = 0x00
            this[1] = 0x00
            this[2] = 0xFF.toByte()
            this[3] = 0x7F.toByte()
        }
        val (rmsL, rmsR) = computeStereoRms(buffer, 4)
        assertEquals(0f, rmsL)
        assertTrue(rmsR > 0.99f, "Expected rmsR near 1.0 but got $rmsR")
    }

    @Test
    fun `averages across multiple frames`() {
        // Two frames: frame1 full-scale L, frame2 silent L
        // Expected rmsL = sqrt((1^2 + 0^2) / 2) ≈ 0.707
        val buffer = ByteArray(8).apply {
            // frame 1: L=32767 (full), R=0
            this[0] = 0xFF.toByte(); this[1] = 0x7F.toByte()
            this[2] = 0x00; this[3] = 0x00
            // frame 2: L=0, R=0
            this[4] = 0x00; this[5] = 0x00
            this[6] = 0x00; this[7] = 0x00
        }
        val (rmsL, _) = computeStereoRms(buffer, 8)
        val expected = sqrt(0.5f)
        assertTrue(kotlin.math.abs(rmsL - expected) < 0.01f, "Expected ~$expected but got $rmsL")
    }

    @Test
    fun `validBytes limits frames read`() {
        // 8-byte buffer but only 4 valid — only 1 frame should be read
        val buffer = ByteArray(8).apply {
            this[0] = 0xFF.toByte(); this[1] = 0x7F.toByte() // frame1: L full
            this[2] = 0x00; this[3] = 0x00
            // bytes 4-7 are garbage that should be ignored
            this[4] = 0xFF.toByte(); this[5] = 0xFF.toByte()
            this[6] = 0xFF.toByte(); this[7] = 0xFF.toByte()
        }
        val (rmsL, _) = computeStereoRms(buffer, 4)
        assertTrue(rmsL > 0.99f)
    }

    @Test
    fun `handles negative s16le samples correctly`() {
        // s16le minimum = 0x0080 = -32768 in little-endian
        val buffer = ByteArray(4).apply {
            this[0] = 0x00; this[1] = 0x80.toByte() // -32768 as s16le
            this[2] = 0x00; this[3] = 0x00
        }
        val (rmsL, rmsR) = computeStereoRms(buffer, 4)
        // |-32768 / 32768| = 1.0 exactly, coerced to 1.0
        assertEquals(1f, rmsL)
        assertEquals(0f, rmsR)
    }

    @Test
    fun `result is clamped to range 0 to 1`() {
        val buffer = ByteArray(4).apply {
            this[0] = 0xFF.toByte(); this[1] = 0x7F.toByte()
            this[2] = 0xFF.toByte(); this[3] = 0x7F.toByte()
        }
        val (rmsL, rmsR) = computeStereoRms(buffer, 4)
        assertTrue(rmsL in 0f..1f)
        assertTrue(rmsR in 0f..1f)
    }
}
