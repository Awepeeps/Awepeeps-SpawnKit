package com.awepeeps.spawnkit;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.GameMode;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.BlockState;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockBurnEvent;
import org.bukkit.event.block.BlockDispenseEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockFadeEvent;
import org.bukkit.event.block.BlockFertilizeEvent;
import org.bukkit.event.block.BlockFormEvent;
import org.bukkit.event.block.BlockFromToEvent;
import org.bukkit.event.block.BlockGrowEvent;
import org.bukkit.event.block.BlockIgniteEvent;
import org.bukkit.event.block.BlockMultiPlaceEvent;
import org.bukkit.event.block.BlockPhysicsEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.block.BlockSpreadEvent;
import org.bukkit.event.block.FluidLevelChangeEvent;
import org.bukkit.event.block.SpongeAbsorbEvent;
import org.bukkit.event.block.EntityBlockFormEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.world.StructureGrowEvent;

/**
 * Enforces the protected spawn zone: the solid core and the clear arrival column above it. The
 * protected region is precomputed per world (one box, so every lookup is O(1) and allocation free).
 * The destructible house is intentionally not covered.
 */
final class SpawnZoneProtection implements Listener {
  private final Map<UUID, SpawnRegion> regions = new ConcurrentHashMap<>();

  void setRegion(World world, SpawnRegion region) {
    regions.put(world.getUID(), region);
  }

  void clearRegion(World world) {
    regions.remove(world.getUID());
  }

  SpawnRegion region(World world) {
    return regions.get(world.getUID());
  }

  boolean isProtected(Block block) {
    SpawnRegion region = regions.get(block.getWorld().getUID());
    return region != null && region.contains(block.getX(), block.getY(), block.getZ());
  }

  private boolean deny(Cancellable event, SpawnZonePolicy.GuardEvent kind, Block block,
                       boolean creativeOperator) {
    if (block != null && SpawnZonePolicy.denied(kind, isProtected(block), creativeOperator)) {
      event.setCancelled(true);
      return true;
    }
    return false;
  }

  private boolean anyProtected(World world, List<Block> blocks) {
    SpawnRegion region = regions.get(world.getUID());
    if (region == null) return false;
    for (Block block : blocks) {
      if (region.contains(block.getX(), block.getY(), block.getZ())) return true;
    }
    return false;
  }

  private boolean anyProtectedState(World world, List<BlockState> states) {
    SpawnRegion region = regions.get(world.getUID());
    if (region == null) return false;
    for (BlockState state : states) {
      if (region.contains(state.getX(), state.getY(), state.getZ())) return true;
    }
    return false;
  }

  private static boolean creativeOperator(Player player) {
    return player != null && player.getGameMode() == GameMode.CREATIVE && player.isOp();
  }

  @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
  public void onBreak(BlockBreakEvent event) {
    deny(event, SpawnZonePolicy.GuardEvent.BLOCK_BREAK, event.getBlock(),
        creativeOperator(event.getPlayer()));
  }

  @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
  public void onPlace(BlockPlaceEvent event) {
    deny(event, SpawnZonePolicy.GuardEvent.BLOCK_PLACE, event.getBlockPlaced(),
        creativeOperator(event.getPlayer()));
  }

  @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
  public void onMultiPlace(BlockMultiPlaceEvent event) {
    deny(event, SpawnZonePolicy.GuardEvent.BLOCK_MULTI_PLACE, event.getBlockPlaced(),
        creativeOperator(event.getPlayer()));
  }

  @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
  public void onBucketEmpty(PlayerBucketEmptyEvent event) {
    Block clicked = event.getBlock();
    boolean creativeOperator = creativeOperator(event.getPlayer());
    if (deny(event, SpawnZonePolicy.GuardEvent.PLAYER_BUCKET_EMPTY, clicked, creativeOperator)) {
      return;
    }
    if (clicked != null) {
      deny(event, SpawnZonePolicy.GuardEvent.PLAYER_BUCKET_EMPTY,
          clicked.getRelative(event.getBlockFace()), creativeOperator);
    }
  }

  @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
  public void onBurn(BlockBurnEvent event) {
    deny(event, SpawnZonePolicy.GuardEvent.BLOCK_BURN, event.getBlock(), false);
  }

  @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
  public void onSpread(BlockSpreadEvent event) {
    deny(event, SpawnZonePolicy.GuardEvent.BLOCK_SPREAD, event.getBlock(), false);
  }

  @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
  public void onForm(BlockFormEvent event) {
    deny(event, SpawnZonePolicy.GuardEvent.BLOCK_FORM, event.getBlock(), false);
  }

  @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
  public void onFade(BlockFadeEvent event) {
    deny(event, SpawnZonePolicy.GuardEvent.BLOCK_FADE, event.getBlock(), false);
  }

  @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
  public void onGrow(BlockGrowEvent event) {
    deny(event, SpawnZonePolicy.GuardEvent.BLOCK_GROW, event.getBlock(), false);
  }

  @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
  public void onFertilize(BlockFertilizeEvent event) {
    if (anyProtectedState(event.getBlock().getWorld(), event.getBlocks())) {
      event.setCancelled(true);
    }
  }

  @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
  public void onFromTo(BlockFromToEvent event) {
    if (deny(event, SpawnZonePolicy.GuardEvent.BLOCK_FROM_TO, event.getToBlock(), false)) {
      return;
    }
    deny(event, SpawnZonePolicy.GuardEvent.BLOCK_FROM_TO, event.getBlock(), false);
  }

  @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
  public void onFluidLevelChange(FluidLevelChangeEvent event) {
    deny(event, SpawnZonePolicy.GuardEvent.FLUID_LEVEL_CHANGE, event.getBlock(), false);
  }

  @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
  public void onIgnite(BlockIgniteEvent event) {
    deny(event, SpawnZonePolicy.GuardEvent.BLOCK_IGNITE, event.getBlock(), false);
  }

  @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
  public void onDispense(BlockDispenseEvent event) {
    deny(event, SpawnZonePolicy.GuardEvent.BLOCK_DISPENSE, event.getBlock(), false);
  }

  @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
  public void onPhysics(BlockPhysicsEvent event) {
    deny(event, SpawnZonePolicy.GuardEvent.BLOCK_PHYSICS, event.getBlock(), false);
  }

  @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
  public void onPistonExtend(BlockPistonExtendEvent event) {
    if (pistonTouches(event.getBlock(), event.getDirection(), event.getBlocks())) {
      event.setCancelled(true);
    }
  }

  @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
  public void onPistonRetract(BlockPistonRetractEvent event) {
    if (pistonTouches(event.getBlock(), event.getDirection(), event.getBlocks())) {
      event.setCancelled(true);
    }
  }

  private boolean pistonTouches(Block piston, BlockFace direction, List<Block> moved) {
    World world = piston.getWorld();
    SpawnRegion region = regions.get(world.getUID());
    if (region == null) return false;
    if (region.contains(piston.getRelative(direction).getX(),
        piston.getRelative(direction).getY(), piston.getRelative(direction).getZ())) {
      return true;
    }
    List<SpawnBlockPos> positions = new ArrayList<>(moved.size());
    for (Block block : moved) {
      positions.add(new SpawnBlockPos(block.getX(), block.getY(), block.getZ()));
    }
    return SpawnZonePolicy.pistonTouchesRegion(region, positions,
        direction.getModX(), direction.getModY(), direction.getModZ());
  }

  @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
  public void onBlockExplode(BlockExplodeEvent event) {
    event.blockList().removeIf(this::isProtected);
  }

  @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
  public void onEntityExplode(EntityExplodeEvent event) {
    event.blockList().removeIf(this::isProtected);
  }

  @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
  public void onEntityChangeBlock(EntityChangeBlockEvent event) {
    deny(event, SpawnZonePolicy.GuardEvent.ENTITY_CHANGE_BLOCK, event.getBlock(), false);
  }

  @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
  public void onEntityBlockForm(EntityBlockFormEvent event) {
    deny(event, SpawnZonePolicy.GuardEvent.ENTITY_BLOCK_FORM, event.getBlock(), false);
  }

  @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
  public void onStructureGrow(StructureGrowEvent event) {
    if (anyProtectedState(event.getWorld(), event.getBlocks())) {
      event.setCancelled(true);
    }
  }

  @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
  public void onSpongeAbsorb(SpongeAbsorbEvent event) {
    if (anyProtectedState(event.getBlock().getWorld(), event.getBlocks())) {
      event.setCancelled(true);
    }
  }
}
