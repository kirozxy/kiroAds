package com.kiro.sdk.ads

import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

/**
 * Types of advertisements supported by the pool.
 */
enum class AdType {
    INTERSTITIAL,
    REWARDED,
    APP_OPEN,
    NATIVE
}

/**
 * Wrapper for cached ads in the pool.
 *
 * @param adUnitId The AdMob Ad Unit ID used to request the ad.
 * @param adObject The loaded AdMob ad instance (e.g. InterstitialAd, RewardedAd).
 * @param loadedTimeMs The system timestamp (in milliseconds) when the ad finished loading.
 */
data class CachedAd(
    val adUnitId: String,
    val adObject: Any,
    val loadedTimeMs: Long
)

/**
 * Centralized, thread-safe cache pool to store preloaded advertisements.
 * Helps prevent ad waste and ensures instant show times.
 */
object KiroAdPool {
    // AdMob ads expire after 4 hours.
    private const val AD_EXPIRATION_TIME_MS = 4 * 60 * 60 * 1000L

    // Thread-safe map holding the ad pool list for each AdType.
    private val pools = ConcurrentHashMap<AdType, MutableList<CachedAd>>()

    /**
     * Stores a successfully loaded ad in the corresponding pool.
     *
     * @param adType The type of advertisement.
     * @param adUnitId The AdMob Ad Unit ID.
     * @param adObject The loaded ad instance.
     */
    fun putAd(adType: AdType, adUnitId: String, adObject: Any) {
        val cachedAd = CachedAd(adUnitId, adObject, System.currentTimeMillis())
        val pool = pools.getOrPut(adType) {
            Collections.synchronizedList(mutableListOf<CachedAd>())
        }
        pool.add(cachedAd)
    }

    /**
     * Retrieves and removes an available ad from the pool.
     * Searches for an exact Ad Unit ID match first, falling back to the oldest
     * non-expired ad of the same type (FIFO).
     *
     * @param adType The type of advertisement.
     * @param targetAdUnitId The requested Ad Mob Unit ID.
     * @return The cached ad instance if available, or null.
     */
    fun getAd(adType: AdType, targetAdUnitId: String): Any? {
        val pool = pools[adType] ?: return null

        // Prune expired ads to respect AdMob policies before retrieving.
        pruneExpiredAds(pool)

        if (pool.isEmpty()) return null

        synchronized(pool) {
            // Priority 1: Find an exact Ad Unit ID match.
            val exactMatch = pool.firstOrNull { it.adUnitId == targetAdUnitId }
            if (exactMatch != null) {
                pool.remove(exactMatch)
                return exactMatch.adObject
            }

            // Priority 2: Fallback to the oldest available ad of this type (FIFO).
            if (pool.isNotEmpty()) {
                val genericAd = pool.removeAt(0)
                return genericAd.adObject
            }
        }
        return null
    }

    /**
     * Checks if there is a cached ad matching the requested Ad Unit ID in the pool.
     *
     * @param adType The type of advertisement.
     * @param targetAdUnitId The requested Ad Unit ID.
     * @return True if a matching ad is found, false otherwise.
     */
    fun hasAd(adType: AdType, targetAdUnitId: String): Boolean {
        val pool = pools[adType] ?: return false
        pruneExpiredAds(pool)
        synchronized(pool) {
            return pool.any { it.adUnitId == targetAdUnitId }
        }
    }

    /**
     * Checks if there is any cached ad available in the pool for the specified type.
     *
     * @param adType The type of advertisement.
     * @return True if any ad is found in the pool, false otherwise.
     */
    fun hasAdOfAnyId(adType: AdType): Boolean {
        val pool = pools[adType] ?: return false
        pruneExpiredAds(pool)
        synchronized(pool) {
            return pool.isNotEmpty()
        }
    }

    /**
     * Retrieves and removes the oldest available non-expired ad of the given type (FIFO),
     * regardless of its Ad Unit ID.
     *
     * Useful for 2-floor waterfall fallback when neither the high nor low floor ID matches
     * but the pool still holds a valid ad from a previous load.
     *
     * @param adType The type of advertisement.
     * @return The cached ad instance if available, or null.
     */
    fun getAnyAd(adType: AdType): Any? {
        val pool = pools[adType] ?: return null
        pruneExpiredAds(pool)
        synchronized(pool) {
            if (pool.isNotEmpty()) {
                return pool.removeAt(0).adObject
            }
        }
        return null
    }

    /**
     * Prunes expired advertisements (older than 4 hours) from the specified list.
     */
    private fun pruneExpiredAds(pool: MutableList<CachedAd>) {
        val now = System.currentTimeMillis()
        synchronized(pool) {
            pool.removeAll { now - it.loadedTimeMs > AD_EXPIRATION_TIME_MS }
        }
    }

    /**
     * Clears all advertisements currently cached in the pool.
     * Useful when ads are disabled (e.g. after a premium purchase).
     */
    fun clearAll() {
        pools.clear()
    }
}
