package com.example.eodigaji.util

import android.content.SharedPreferences
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

object BlindNames {
    private val POOL = listOf(
        "숨겨진 평온", "비밀의 쉼표", "조용한 나침반", "이름 없는 여백", "미지의 풍경",
        "잠깐의 외도", "우연한 낭만", "작은 해방감", "오늘의 궤도", "낯선 다정함",
        "고요한 숨결", "은빛 모퉁이", "바람의 편지", "푸른 발자국", "시간의 골목",
        "느린 수평선", "노을의 문장", "별빛 정거장", "구름의 안부", "달빛 산책로",
        "초록의 쉼터", "물결의 기억", "새벽의 온도", "햇살의 틈새", "어스름 언덕",
        "한적한 모래", "잔잔한 여운", "투명한 오후", "맑은 정적", "포근한 구석"
    )

    fun pick(recent: List<String>, preferredFallback: String? = null): String {
        if (!preferredFallback.isNullOrBlank() && !recent.contains(preferredFallback)) {
            return preferredFallback
        }
        val candidates = POOL.filterNot { recent.contains(it) }
        return if (candidates.isNotEmpty()) {
            candidates[Random.nextInt(candidates.size)]
        } else {
            preferredFallback?.takeIf { it.isNotBlank() } ?: POOL[Random.nextInt(POOL.size)]
        }
    }

    /**
     * 현재 좌표(lat1, lng1)에서 목적지(lat2, lng2)까지의 8방위 계산
     */
    fun calculateCompassDirection(lat1: Double, lng1: Double, lat2: Double, lng2: Double): String {
        val lat1Rad = Math.toRadians(lat1)
        val lat2Rad = Math.toRadians(lat2)
        val dLngRad = Math.toRadians(lng2 - lng1)

        val y = sin(dLngRad) * cos(lat2Rad)
        val x = cos(lat1Rad) * sin(lat2Rad) - sin(lat1Rad) * cos(lat2Rad) * cos(dLngRad)
        val bearing = (Math.toDegrees(atan2(y, x)) + 360.0) % 360.0

        return when {
            bearing >= 337.5 || bearing < 22.5 -> "북쪽"
            bearing < 67.5 -> "북동쪽"
            bearing < 112.5 -> "동쪽"
            bearing < 157.5 -> "남동쪽"
            bearing < 202.5 -> "남쪽"
            bearing < 247.5 -> "남서쪽"
            bearing < 292.5 -> "서쪽"
            else -> "북서쪽"
        }
    }
}

object RecentBlindNames {
    private const val KEY = "recent_blind_names"
    private const val MAX_SIZE = 10

    fun load(prefs: SharedPreferences): List<String> {
        val raw = prefs.getString(KEY, null) ?: return emptyList()
        return raw.split("|").filter { it.isNotBlank() }
    }

    fun save(prefs: SharedPreferences, name: String) {
        val current = load(prefs).toMutableList()
        current.remove(name)
        current.add(0, name)
        val trimmed = current.take(MAX_SIZE)
        prefs.edit().putString(KEY, trimmed.joinToString("|")).apply()
    }
}
