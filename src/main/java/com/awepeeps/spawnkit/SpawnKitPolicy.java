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

  /**
   * Whether a site refusal is caused by terrain that will not change by waiting (world-generated
   * blocks, shape, border, height) rather than a transient condition (chunks or terrain not loaded
   * yet). A deterministic refusal is logged in full once and the timer retry is stopped.
   */
  static boolean isDeterministicDefect(String defect) {
    return defect != null && !defect.equals("spawn chunks were not loaded");
  }

  /**
   * The key a deferral summary is deduplicated by: a count written as {@code =<digits>} is ignored,
   * so a reason that only changes its counts is logged once.
   */
  static String deferralKey(String summary) {
    return summary.replaceAll("=\\d+", "");
  }

  /** The message shown when an operator builds a world that is already at the current layout. */
  static String alreadyCurrent(int layoutVersion) {
    return "This world is already at the current spawn layout (version " + layoutVersion
        + "); nothing was changed.";
  }

  record ChunkBounds(int minX, int maxX, int minZ, int maxZ) {}
}
