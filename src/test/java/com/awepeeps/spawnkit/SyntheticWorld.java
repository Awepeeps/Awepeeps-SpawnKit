package com.awepeeps.spawnkit;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.bukkit.HeightMap;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;

/**
 * A tiny read-only {@link World} stand-in over an in-memory block map, with real heightmap
 * semantics, so the production {@link WorldBlockReader} can be exercised without a server. It is a
 * dynamic proxy, deliberately not a full {@code World}: only the read methods the spawn scanner
 * touches are answered, and every other call throws.
 *
 * <p>Used to pin the production heightmap choice ({@link WorldBlockReader#topY} must ask for
 * {@link HeightMap#WORLD_SURFACE}) and to drive the two-pass build regression through the real
 * reader rather than the {@link MapBlockReader} test double, which already had the fixed semantics
 * and so could hide a production regression.
 */
final class SyntheticWorld implements BlockReader {
  private final Map<Long, Material> blocks = new HashMap<>();
  private final Set<Long> protectedBlocks = new HashSet<>();
  private final List<HeightMap> heightMapQueries = new ArrayList<>();

  static final int MIN_Y = -64;
  static final int MAX_Y = 320;

  SyntheticWorld put(int x, int y, int z, Material material) {
    blocks.put(key(x, y, z), material);
    return this;
  }

  /** Marks a block as a container (or other block entity) that a forced build must not replace. */
  SyntheticWorld protect(int x, int y, int z) {
    protectedBlocks.add(key(x, y, z));
    return this;
  }

  /** The heightmaps production code has asked this world for, in call order. */
  List<HeightMap> heightMapQueries() {
    return List.copyOf(heightMapQueries);
  }

  private static long key(int x, int y, int z) {
    return ((long) (x & 0x3FFFFFF) << 38) | ((long) (z & 0x3FFFFFF) << 12) | (y & 0xFFF);
  }

  // ---- BlockReader (the fixed production semantics) ------------------------------------------

  @Override
  public int minHeight() {
    return MIN_Y;
  }

  @Override
  public int maxHeight() {
    return MAX_Y;
  }

  @Override
  public boolean isLoaded(int blockX, int blockZ) {
    return true;
  }

  @Override
  public Material blockAt(int x, int y, int z) {
    return blocks.getOrDefault(key(x, y, z), Material.AIR);
  }

  /** {@code HeightMap.WORLD_SURFACE}: the highest non-air block, ground cover included. */
  @Override
  public int topY(int blockX, int blockZ) {
    for (int y = MAX_Y - 1; y >= MIN_Y; y--) {
      if (!SpawnZonePolicy.isAir(blockAt(blockX, y, blockZ))) return y;
    }
    return MIN_Y;
  }

  /** {@code HeightMap.MOTION_BLOCKING_NO_LEAVES}: the highest motion-blocking non-leaf block. */
  @Override
  public int surfaceY(int blockX, int blockZ) {
    return motionBlocking(blockX, blockZ, true);
  }

  @Override
  public boolean insideWorldBorder(int blockX, int blockZ) {
    return true;
  }

  @Override
  public boolean forceReplaceable(int x, int y, int z) {
    return !protectedBlocks.contains(key(x, y, z));
  }

  /**
   * {@code HeightMap.MOTION_BLOCKING}: the highest block that blocks motion or holds fluid. This is
   * the pre-fix production behaviour, kept so a regression test can reproduce the missed ground
   * cover.
   */
  int motionBlockingTopY(int blockX, int blockZ) {
    return motionBlocking(blockX, blockZ, false);
  }

  private int motionBlocking(int blockX, int blockZ, boolean noLeaves) {
    for (int y = MAX_Y - 1; y >= MIN_Y; y--) {
      Material material = blockAt(blockX, y, blockZ);
      if (SpawnZonePolicy.isAir(material)) continue;
      if (noLeaves && material.name().endsWith("_LEAVES")) continue;
      if (blocksMotion(material)) return y;
    }
    return MIN_Y;
  }

  /** The vanilla "blocks motion" test, approximated for the materials these tests use. */
  private static boolean blocksMotion(Material material) {
    if (SpawnZonePolicy.isAir(material)) return false;
    return switch (material) {
      case LEAF_LITTER, BUSH, SHORT_GRASS, TALL_GRASS, FERN, LARGE_FERN, DANDELION, POPPY,
          BLUE_ORCHID, PINK_PETALS, SWEET_BERRY_BUSH, OAK_SAPLING, VINE, TORCH, WALL_TORCH -> false;
      default -> true;
    };
  }

  // ---- World proxy ---------------------------------------------------------------------------

  /** A read-only {@link World} view of this grid, for the production {@link WorldBlockReader}. */
  World world() {
    InvocationHandler handler = (proxy, method, args) -> {
      switch (method.getName()) {
        case "getMinHeight":
          return MIN_Y;
        case "getMaxHeight":
          return MAX_Y;
        case "isChunkLoaded":
          return true;
        case "getHighestBlockYAt": {
          int x = (Integer) args[0];
          int z = (Integer) args[1];
          HeightMap heightMap = (HeightMap) args[2];
          heightMapQueries.add(heightMap);
          return switch (heightMap) {
            case WORLD_SURFACE -> topY(x, z);
            case MOTION_BLOCKING -> motionBlockingTopY(x, z);
            case MOTION_BLOCKING_NO_LEAVES -> surfaceY(x, z);
            default -> topY(x, z);
          };
        }
        case "getBlockAt": {
          int x = (Integer) args[0];
          int y = (Integer) args[1];
          int z = (Integer) args[2];
          return blockProxy(x, y, z);
        }
        case "getWorldBorder":
          return worldBorderProxy();
        case "equals":
          return proxy == args[0];
        case "hashCode":
          return System.identityHashCode(proxy);
        case "toString":
          return "SyntheticWorld";
        default:
          throw new UnsupportedOperationException("SyntheticWorld does not implement " + method);
      }
    };
    return (World) Proxy.newProxyInstance(
        World.class.getClassLoader(), new Class<?>[] {World.class}, handler);
  }

  private Block blockProxy(int x, int y, int z) {
    InvocationHandler handler = (proxy, method, args) -> switch (method.getName()) {
      case "getType" -> blockAt(x, y, z);
      case "getState" -> containerState(key(x, y, z));
      case "equals" -> proxy == args[0];
      case "hashCode" -> System.identityHashCode(proxy);
      case "toString" -> "SyntheticBlock(" + x + "," + y + "," + z + ")";
      default -> throw new UnsupportedOperationException(
          "SyntheticWorld block does not implement " + method);
    };
    return (Block) Proxy.newProxyInstance(
        Block.class.getClassLoader(), new Class<?>[] {Block.class}, handler);
  }

  /**
   * A container state whose inventory is empty unless the block was {@link #protect}ed, so the
   * production {@link WorldBlockReader#forceReplaceable} decision can be exercised.
   */
  private org.bukkit.block.BlockState containerState(long cell) {
    org.bukkit.inventory.Inventory inventory = (org.bukkit.inventory.Inventory)
        Proxy.newProxyInstance(org.bukkit.inventory.Inventory.class.getClassLoader(),
            new Class<?>[] {org.bukkit.inventory.Inventory.class},
            (proxy, method, args) -> switch (method.getName()) {
              case "isEmpty" -> !protectedBlocks.contains(cell);
              case "equals" -> proxy == args[0];
              case "hashCode" -> System.identityHashCode(proxy);
              case "toString" -> "SyntheticInventory";
              default -> throw new UnsupportedOperationException(
                  "SyntheticWorld inventory does not implement " + method);
            });
    return (org.bukkit.block.BlockState) Proxy.newProxyInstance(
        org.bukkit.block.Container.class.getClassLoader(),
        new Class<?>[] {org.bukkit.block.Container.class},
        (proxy, method, args) -> switch (method.getName()) {
          case "getInventory" -> inventory;
          case "equals" -> proxy == args[0];
          case "hashCode" -> System.identityHashCode(proxy);
          case "toString" -> "SyntheticContainer";
          default -> throw new UnsupportedOperationException(
              "SyntheticWorld container does not implement " + method);
        });
  }

  private org.bukkit.WorldBorder worldBorderProxy() {
    InvocationHandler handler = (proxy, method, args) -> switch (method.getName()) {
      case "isInside" -> true;
      case "equals" -> proxy == args[0];
      case "hashCode" -> System.identityHashCode(proxy);
      case "toString" -> "SyntheticWorldBorder";
      default -> throw new UnsupportedOperationException(
          "SyntheticWorld border does not implement " + method);
    };
    return (org.bukkit.WorldBorder) Proxy.newProxyInstance(
        org.bukkit.WorldBorder.class.getClassLoader(),
        new Class<?>[] {org.bukkit.WorldBorder.class}, handler);
  }
}
