package com.wheelseye.devicegateway.repository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * Redis Cache Cleanup Utility
 * 
 * Cleans up legacy session data that causes deserialization errors.
 * Run this once to clear old incompatible session data.
 * 
 * Usage: java -jar app.jar --spring.profiles.active=cleanup
 */
@Slf4j
@Component
@Profile("cleanup")
@RequiredArgsConstructor
public class RedisCacheCleanupUtility implements CommandLineRunner {

    private final RedisTemplate<String, Object> redisTemplate;

    @Override
    public void run(String... args) throws Exception {
        log.info("🧹 Starting Redis cache cleanup...");

        try {
            // Clear all session-related keys
            clearSessionKeys();
            clearIndexKeys();
            clearActiveSessions();

            log.info("✅ Redis cache cleanup completed successfully!");
            log.info("🔄 Restart application in normal mode to use clean cache");

        } catch (Exception e) {
            log.error("❌ Error during Redis cleanup: {}", e.getMessage(), e);
        }
    }

    private void clearSessionKeys() {
        try {
            Set<String> sessionKeys = redisTemplate.keys("session:*");
            if (sessionKeys != null && !sessionKeys.isEmpty()) {
                redisTemplate.delete(sessionKeys);
                log.info("🗑️ Cleared {} session keys", sessionKeys.size());
            }
        } catch (Exception e) {
            log.error("❌ Error clearing session keys: {}", e.getMessage());
        }
    }

    private void clearIndexKeys() {
        try {
            Set<String> imeiKeys = redisTemplate.keys("imei-index:*");
            if (imeiKeys != null && !imeiKeys.isEmpty()) {
                redisTemplate.delete(imeiKeys);
                log.info("🗑️ Cleared {} IMEI index keys", imeiKeys.size());
            }

            Set<String> channelKeys = redisTemplate.keys("channel-index:*");
            if (channelKeys != null && !channelKeys.isEmpty()) {
                redisTemplate.delete(channelKeys);
                log.info("🗑️ Cleared {} channel index keys", channelKeys.size());
            }
        } catch (Exception e) {
            log.error("❌ Error clearing index keys: {}", e.getMessage());
        }
    }

    private void clearActiveSessions() {
        try {
            Boolean deleted = redisTemplate.delete("active-sessions");
            if (Boolean.TRUE.equals(deleted)) {
                log.info("🗑️ Cleared active sessions set");
            }
        } catch (Exception e) {
            log.error("❌ Error clearing active sessions: {}", e.getMessage());
        }
    }
}