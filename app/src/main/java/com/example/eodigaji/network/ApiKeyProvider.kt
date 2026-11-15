package com.example.eodigaji.network

import com.example.eodigaji.BuildConfig

/**
 * 컴파일 타임에 XOR 난독화된 API Key 바이트 배열을 런타임 메모리에서 복원하는 프로바이더.
 * APK 디컴파일 시 정적 문자열로 API Key가 노출되는 것을 방지합니다.
 */
object ApiKeyProvider {

    fun getApiKey(): String {
        val enc = BuildConfig.API_KEY_ENC
        val salt = BuildConfig.API_KEY_SALT
        return decode(enc, salt)
    }

    internal fun decode(enc: ByteArray, salt: ByteArray): String {
        if (enc.isEmpty() || salt.isEmpty()) return ""
        val bytes = ByteArray(enc.size) { i ->
            val s = salt[i % salt.size].toInt()
            val b = enc[i].toInt()
            ((b xor ((i * 7) and 0xFF)) xor s).toByte()
        }
        return String(bytes, Charsets.UTF_8)
    }

    internal fun encode(raw: String, salt: ByteArray): ByteArray {
        val bytes = raw.toByteArray(Charsets.UTF_8)
        return ByteArray(bytes.size) { i ->
            val s = salt[i % salt.size].toInt()
            val b = bytes[i].toInt()
            ((b xor s) xor ((i * 7) and 0xFF)).toByte()
        }
    }
}
