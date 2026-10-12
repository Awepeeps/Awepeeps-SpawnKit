package com.awepeeps.spawnkit;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

/**
 * Policy for the test-only kit gating (date window) plus a couple of shared helpers used when
 * asynchronously loading terrain. Pure so it can be unit tested without a server.
 */
final class SpawnKitPolicy {
  private SpawnKitPolicy() {}

  static boolean isActive(
      boolean enabled, String startDate, String expiryDate, String timezone, Instant now) {
    if (!enabled || startDate == null || expiryDate == null || timezone == null) return false;
    try {
      LocalDate start = LocalDate.parse(startDate.trim());
      LocalDate expiry = LocalDate.parse(expiryDate.trim());
      ZoneId zone = ZoneId.of(timezone.trim());
      return !now.isBefore(start.atStartOfDay(zone).toInstant())
          && now.isBefore(expiry.plusDays(1).atStartOfDay(zone).toInstant());
    } catch (java.time.DateTimeException | ArithmeticException error) {
      return false;
    }
  }

  static ChunkBounds chunkBounds(int centerX, int centerZ, int extent) {
    return new ChunkBounds(
        (centerX - extent) >> 4, (centerX + extent) >> 4,
        (centerZ - extent) >> 4, (centerZ + extent) >> 4);
  }

  static int retryDelaySeconds(int failedAttempts) {
    return (int) Math.min(30 * 60L, 30L << Math.min(Math.max(0, failedAttempts), 6));
  }

  record ChunkBounds(int minX, int maxX, int minZ, int maxZ) {}
}
