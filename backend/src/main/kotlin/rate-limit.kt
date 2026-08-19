import java.util.concurrent.ConcurrentHashMap

// simple in-process fixed-window rate limiter -- mirrors the flask_limiter
// tiers app.py already has on /api/register and /api/login
class RateLimiter(private val limit: Int, private val windowMillis: Long) {
    private class Window(var count: Int, var windowStart: Long)

    private val buckets = ConcurrentHashMap<String, Window>()

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

// matches app.py: 5/min + 20/hour on register, 10/min global + 5/min per-username on login
val registerLimiterPerMinute = RateLimiter(limit = 5, windowMillis = 60_000)
val registerLimiterPerHour = RateLimiter(limit = 20, windowMillis = 3_600_000)
val loginLimiterPerMinute = RateLimiter(limit = 10, windowMillis = 60_000)
val loginLimiterPerUsernamePerMinute = RateLimiter(limit = 5, windowMillis = 60_000)
