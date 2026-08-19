import java.util.concurrent.ConcurrentHashMap

// This is a fixed-window rate limiter. It keeps all state in memory, in
// this one process. There is no external store, for example Redis.
// It matches the limit tiers app.py sets with flask_limiter on
// /api/register and /api/login.
class RateLimiter(private val limit: Int, private val windowMillis: Long) {
    internal class Window(var count: Int, var windowStart: Long)

    // This is internal, not private. AuthRouteTest.kt clears it directly
    // between tests, through Gradle's main/test friend compilation. This is
    // a test hook only. It does not widen the public API.
    internal val buckets = ConcurrentHashMap<String, Window>()

    @Synchronized
    fun tryAcquire(key: String): Boolean {
        val now = System.currentTimeMillis()
        val window = buckets.getOrPut(key) { Window(0, now) }

        if (now - window.windowStart >= windowMillis) {
            window.count = 0
            window.windowStart = now
        }

        if (window.count >= limit) {
            return false
        }

        window.count++
        return true
    }
}

const val RATE_LIMIT_MESSAGE = "Too many requests. Please try again later."

// These limits match app.py: 5 per minute and 20 per hour on register.
// 10 per minute total, and 5 per minute per username, on login.
val registerLimiterPerMinute = RateLimiter(limit = 5, windowMillis = 60_000)
val registerLimiterPerHour = RateLimiter(limit = 20, windowMillis = 3_600_000)
val loginLimiterPerMinute = RateLimiter(limit = 10, windowMillis = 60_000)
val loginLimiterPerUsernamePerMinute = RateLimiter(limit = 5, windowMillis = 60_000)
