package com.awepeeps.spawnkit;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

/** Deferral handling: what makes a refusal deterministic, how it is deduplicated, and the message. */
class SpawnKitPolicyDeferralTest {
  @Test
  void deterministicDefectsStopRetryingButTransientOnesDoNot() {
    assertTrue(SpawnKitPolicy.isDeterministicDefect(
        "3 block(s) in the footprint are not natural terrain: LEAF_LITTER at (0, 65, 0)"));
    assertTrue(SpawnKitPolicy.isDeterministicDefect("lava beneath the footprint"));
    assertTrue(SpawnKitPolicy.isDeterministicDefect("terrain is too uneven to level"));
    assertTrue(SpawnKitPolicy.isDeterministicDefect("void beneath the footprint"));
    assertTrue(SpawnKitPolicy.isDeterministicDefect("footprint outside the world border"));
    assertTrue(SpawnKitPolicy.isDeterministicDefect("no headroom or too close to the world floor"));
    assertFalse(SpawnKitPolicy.isDeterministicDefect("spawn chunks were not loaded"),
        "a chunk-load deferral is transient and must keep retrying");
    assertFalse(SpawnKitPolicy.isDeterministicDefect(null));
  }

  @Test
  void aDeferralIsDeduplicatedByItsReason() {
    String reason = "Spawn zone build deferred: 3 block(s) in the footprint are not natural"
        + " terrain: LEAF_LITTER at (0, 65, 0).";
    // The same reason is logged once; a different reason is logged again.
    assertEquals(SpawnKitPolicy.deferralKey(reason), SpawnKitPolicy.deferralKey(reason));
    assertNotEquals(SpawnKitPolicy.deferralKey(reason),
        SpawnKitPolicy.deferralKey(reason + " More."));
    // A count written as =N (a volatile value) is ignored so it cannot spam the log.
    assertEquals(SpawnKitPolicy.deferralKey("core-depth=5 rejected"),
        SpawnKitPolicy.deferralKey("core-depth=9 rejected"));
  }

  @Test
  void alreadyCurrentReportsThatNothingChanged() {
    String message = SpawnKitPolicy.alreadyCurrent(3);
    assertTrue(message.toLowerCase().contains("already at the current spawn layout"), message);
    assertTrue(message.contains("version 3"), message);
    assertTrue(message.toLowerCase().contains("nothing was changed"), message);
  }
}
