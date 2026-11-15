package com.example.eodigaji.network

import org.junit.Assert.assertEquals
import org.junit.Test

class ApiKeyProviderTest {

    @Test
    fun encodeAndDecode_roundTripsCorrectly() {
        val testKey = "sample_test_api_key_xyz_12345"
        val salt = byteArrayOf(
            0x57.toByte(), 0x3A.toByte(), 0x7F.toByte(), 0x22.toByte(),
            0x4E.toByte(), 0x69.toByte(), 0x1B.toByte(), 0x54.toByte(),
            0x2C.toByte(), 0x3D.toByte(), 0x71.toByte(), 0x48.toByte(),
            0x63.toByte(), 0x1A.toByte(), 0x5D.toByte(), 0x29.toByte()
        )

        val encoded = ApiKeyProvider.encode(testKey, salt)
        val decoded = ApiKeyProvider.decode(encoded, salt)

        assertEquals(testKey, decoded)
    }

    @Test
    fun decode_emptyBytes_returnsEmptyString() {
        val salt = byteArrayOf(0x01, 0x02)
        assertEquals("", ApiKeyProvider.decode(byteArrayOf(), salt))
        assertEquals("", ApiKeyProvider.decode(byteArrayOf(0x01), byteArrayOf()))
    }
}
