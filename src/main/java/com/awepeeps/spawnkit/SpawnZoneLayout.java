package com.awepeeps.spawnkit;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.bukkit.Material;
import org.bukkit.block.BlockFace;

/**
 * Pure plan of the spawn layout: a 3x3 core of protected blocks, a protected clear column above
 * it, and a destructible house built strictly around both. All coordinates are relative offsets
 * from the world spawn block (the block the arriving player's feet occupy), so the same plan can be
 * placed in any world. Nothing here touches a world or the server (only plain enum constants), so
 * the whole geometry is unit testable.
 *
 * <pre>
 *   relative Y          what lives there
 *   wallHeight          roof (closed, destructible)
 *   0 .. wallHeight-1   walls on the perimeter; interior air; arrival space at the centre
 *  -1                   house floor (destructible) except the 3x3 core top layer (protected)
 *  -coreDepth .. -1     3x3 core (protected, solid)
 * </pre>
 *
 * <p>The ring of cells between the protected 3x3 and the walls carries the furnishings. Nothing is
 * ever placed inside the protected zone, on the doorway column or in the walkable route from the
 * core top to the door.
 */
final class SpawnZoneLayout {
  /** Blocks the builder places, and what each one is. */
  enum Role {
    CORE,
    FLOOR,
    WALL,
    ROOF,
    DOOR_LOWER,
    DOOR_UPPER,
    BED_FOOT,
    BED_HEAD,
    CHEST,
    CRAFTING_TABLE,
    ANVIL,
    FURNACE,
    LODESTONE,
    EXTRA,
    WALL_TORCH,
    EXTERIOR_TORCH,
    BANNER_LEFT,
    BANNER_RIGHT,
    DOORSTEP
  }

  static final int CORE_RADIUS = 1;
  static final int MIN_CLEAR_HEIGHT = 2;
  static final int MAX_CLEAR_HEIGHT = 8;
  static final int MIN_CORE_DEPTH = 1;
  static final int MAX_CORE_DEPTH = 8;
  static final int MIN_MARGIN = 1;
  static final int MAX_MARGIN = 2;
  static final int MIN_BEDS = 1;
  static final int MAX_BEDS = 4;
  static final int MIN_CHESTS = 1;
  static final int MAX_CHESTS = 4;
  static final int MIN_WALL_HEIGHT = 3;
  /** The most steps the entry staircase may add below the doorstep. */
  static final int MAX_ENTRY_STEPS = 3;

  private final int coreDepth;
  private final int clearHeight;
  private final int margin;
  private final int beds;
  private final int chests;
  private final SpawnKitFurnishings furnishings;

  private final SpawnRegion core;
  private final SpawnRegion arrival;
  private final SpawnRegion protectedRegion;
  private final SpawnRegion footprint;
  private final SpawnRegion clearance;
  private final List<SpawnBlockPos> doorPath;
  private final Map<SpawnBlockPos, Role> blockPlan;
  private final Map<SpawnBlockPos, Material> extraMaterials;
  private final List<BedPlacement> placedBeds;

  SpawnZoneLayout(int coreDepth, int clearHeight, int margin, int beds, int chests) {
    this(coreDepth, clearHeight, margin, beds, chests, SpawnKitFurnishings.defaults());
  }

  SpawnZoneLayout(int coreDepth, int clearHeight, int margin, int beds, int chests,
                  SpawnKitFurnishings furnishings) {
    if (!validCoreDepth(coreDepth)) throw new IllegalArgumentException("Invalid core-depth.");
    if (!validClearHeight(clearHeight)) throw new IllegalArgumentException("Invalid clear-height.");
    if (!validMargin(margin)) throw new IllegalArgumentException("Invalid interior margin.");
    if (!validBeds(beds)) throw new IllegalArgumentException("Invalid bed count.");
    if (!validChests(chests)) throw new IllegalArgumentException("Invalid chest count.");
    if (furnishings == null) throw new IllegalArgumentException("Invalid furnishings.");
    this.coreDepth = coreDepth;
    this.clearHeight = clearHeight;
    this.margin = margin;
    this.beds = beds;
    this.chests = chests;
    this.furnishings = furnishings;

    core = SpawnRegion.of(-CORE_RADIUS, -coreDepth, -CORE_RADIUS, CORE_RADIUS, -1, CORE_RADIUS);
    arrival = SpawnRegion.of(-CORE_RADIUS, 0, -CORE_RADIUS, CORE_RADIUS, clearHeight - 1, CORE_RADIUS);
    protectedRegion = SpawnRegion.of(
        -CORE_RADIUS, -coreDepth, -CORE_RADIUS, CORE_RADIUS, clearHeight - 1, CORE_RADIUS);

    int wallRadius = wallRadius();
    footprint = SpawnRegion.of(
        -wallRadius, -1, -wallRadius, wallRadius, wallHeight(), wallRadius);
    clearance = SpawnRegion.of(
        -innerRadius(), 0, -innerRadius(), innerRadius(), wallHeight() - 1, innerRadius());

    doorPath = new ArrayList<>();
    for (int z = 0; z <= wallRadius; z++) {
      doorPath.add(new SpawnBlockPos(0, 0, z));
    }
    extraMaterials = new LinkedHashMap<>();
    placedBeds = new ArrayList<>();
    blockPlan = buildPlan();
  }

  static boolean validCoreDepth(int value) {
    return value >= MIN_CORE_DEPTH && value <= MAX_CORE_DEPTH;
  }

  static boolean validClearHeight(int value) {
    return value >= MIN_CLEAR_HEIGHT && value <= MAX_CLEAR_HEIGHT;
  }

  static boolean validMargin(int value) {
    return value >= MIN_MARGIN && value <= MAX_MARGIN;
  }

  static boolean validBeds(int value) {
    return value >= MIN_BEDS && value <= MAX_BEDS;
  }

  static boolean validChests(int value) {
    return value >= MIN_CHESTS && value <= MAX_CHESTS;
  }

  /**
   * Clamps a configured dimension to its documented range. Used instead of refusing to load so that
   * a typo in one dimension cannot disable the whole plugin (and with it spawn protection).
   */
  static int clampCoreDepth(int value) {
    return SpawnZonePolicy.clamp(value, MIN_CORE_DEPTH, MAX_CORE_DEPTH);
  }

  static int clampClearHeight(int value) {
    return SpawnZonePolicy.clamp(value, MIN_CLEAR_HEIGHT, MAX_CLEAR_HEIGHT);
  }

  static int clampMargin(int value) {
    return SpawnZonePolicy.clamp(value, MIN_MARGIN, MAX_MARGIN);
  }

  static int clampBeds(int value) {
    return SpawnZonePolicy.clamp(value, MIN_BEDS, MAX_BEDS);
  }

  static int clampChests(int value) {
    return SpawnZonePolicy.clamp(value, MIN_CHESTS, MAX_CHESTS);
  }

  /** The values used when the configuration cannot be read at all. */
  static SpawnZoneLayout defaults() {
    return new SpawnZoneLayout(3, 3, 1, 1, 1, SpawnKitFurnishings.defaults());
  }

  int wallRadius() {
    return CORE_RADIUS + 1 + margin;
  }

  int innerRadius() {
    return wallRadius() - 1;
  }

  int wallHeight() {
    return Math.max(MIN_WALL_HEIGHT, clearHeight);
  }

  int coreDepth() {
    return coreDepth;
  }

  int clearHeight() {
    return clearHeight;
  }

  int margin() {
    return margin;
  }

  int beds() {
    return beds;
  }

  int chests() {
    return chests;
  }

  SpawnKitFurnishings furnishings() {
    return furnishings;
  }

  SpawnRegion core() {
    return core;
  }

  SpawnRegion arrival() {
    return arrival;
  }

  SpawnRegion protectedRegion() {
    return protectedRegion;
  }

  SpawnRegion footprint() {
    return footprint;
  }

  SpawnRegion clearance() {
    return clearance;
  }

  List<SpawnBlockPos> doorPath() {
    return doorPath;
  }

  SpawnBlockPos doorLower() {
    return new SpawnBlockPos(0, 0, wallRadius());
  }

  SpawnBlockPos doorUpper() {
    return new SpawnBlockPos(0, 1, wallRadius());
  }

  SpawnBlockPos doorExit() {
    return new SpawnBlockPos(0, 0, wallRadius() + 1);
  }

  /** The plank doorstep just outside the doorway, flush with the house floor. */
  SpawnBlockPos doorstep() {
    return new SpawnBlockPos(0, -1, exteriorZ());
  }

  /** The outer face of the south wall (one block south of the wall). */
  int exteriorZ() {
    return wallRadius() + 1;
  }

  /** The air cell one or more blocks past the doorstep, where a step may be built. */
  SpawnBlockPos approach(int step) {
    return new SpawnBlockPos(0, 0, exteriorZ() + step);
  }

  Map<SpawnBlockPos, Role> blockPlan() {
    return blockPlan;
  }

  Material extraMaterial(SpawnBlockPos pos) {
    return extraMaterials.get(pos);
  }

  List<BedPlacement> bedPlacements() {
    return List.copyOf(placedBeds);
  }

  List<SpawnBlockPos> offsetsWithRole(Role role) {
    List<SpawnBlockPos> result = new ArrayList<>();
    for (Map.Entry<SpawnBlockPos, Role> entry : blockPlan.entrySet()) {
      if (entry.getValue() == role) result.add(entry.getKey());
    }
    return result;
  }

  boolean isSolid(SpawnBlockPos pos) {
    return blockPlan.containsKey(pos);
  }

  /** True for every role that is a furnishing or an exterior decoration (nothing structural). */
  static boolean isFurnishing(Role role) {
    return switch (role) {
      case ANVIL, FURNACE, LODESTONE, CRAFTING_TABLE, CHEST, BED_FOOT, BED_HEAD,
          EXTRA, WALL_TORCH, EXTERIOR_TORCH, BANNER_LEFT, BANNER_RIGHT, DOORSTEP -> true;
      case CORE, FLOOR, WALL, ROOF, DOOR_LOWER, DOOR_UPPER -> false;
    };
  }

  /** The furnishing cells in placement order, for the additive upgrade. */
  List<Map.Entry<SpawnBlockPos, Role>> furnishPlan() {
    List<Map.Entry<SpawnBlockPos, Role>> result = new ArrayList<>();
    for (Map.Entry<SpawnBlockPos, Role> entry : blockPlan.entrySet()) {
      if (isFurnishing(entry.getValue())) result.add(entry);
    }
    return result;
  }

  /** Ring cells that a furnishing may occupy, in a fixed order, never the door route. */
  List<SpawnBlockPos> ringSlots() {
    int r = innerRadius();
    List<SpawnBlockPos> slots = new ArrayList<>();
    for (int z = -r; z <= r; z++) {
      for (int x = -r; x <= r; x++) {
        if (Math.abs(x) <= CORE_RADIUS && Math.abs(z) <= CORE_RADIUS) continue;
        if (x == 0 && z > 0) continue; // keep the walk from the core to the door open
        slots.add(new SpawnBlockPos(x, 0, z));
      }
    }
    return slots;
  }

  /** The ring cells still free after the fixed furnishings, beds, chests and extras. */
  int maxExtras() {
    int free = 0;
    for (SpawnBlockPos slot : ringSlots()) {
      if (!blockPlan.containsKey(slot)) free++;
    }
    return free;
  }

  List<SpawnBlockPos> extraOffsets() {
    return offsetsWithRole(Role.EXTRA);
  }

  List<SpawnBlockPos> chestOffsets() {
    return offsetsWithRole(Role.CHEST);
  }

  /** The exterior decoration cells (torches and banners) on the south wall's outer face. */
  List<SpawnBlockPos> exteriorOffsets() {
    List<SpawnBlockPos> result = new ArrayList<>();
    result.addAll(offsetsWithRole(Role.EXTERIOR_TORCH));
    result.addAll(offsetsWithRole(Role.BANNER_LEFT));
    result.addAll(offsetsWithRole(Role.BANNER_RIGHT));
    return result;
  }

  SpawnBlockPos anvilOffset() {
    return new SpawnBlockPos(-1, 0, -innerRadius());
  }

  SpawnBlockPos craftingTableOffset() {
    return new SpawnBlockPos(0, 0, -innerRadius());
  }

  SpawnBlockPos furnaceOffset() {
    return new SpawnBlockPos(innerRadius(), 0, 0);
  }

  SpawnBlockPos lodestoneOffset() {
    return new SpawnBlockPos(innerRadius(), 0, -innerRadius());
  }

  private Map<SpawnBlockPos, Role> buildPlan() {
    Map<SpawnBlockPos, Role> plan = new LinkedHashMap<>();
    int wallRadius = wallRadius();
    int wallHeight = wallHeight();

    // Core: 3x3 by core-depth (protected, solid).
    for (int x = -CORE_RADIUS; x <= CORE_RADIUS; x++) {
      for (int z = -CORE_RADIUS; z <= CORE_RADIUS; z++) {
        for (int y = -coreDepth; y <= -1; y++) {
          plan.put(new SpawnBlockPos(x, y, z), Role.CORE);
        }
      }
    }

    // Floor: full footprint at Y=-1, except the protected core top layer.
    for (int x = -wallRadius; x <= wallRadius; x++) {
      for (int z = -wallRadius; z <= wallRadius; z++) {
        SpawnBlockPos pos = new SpawnBlockPos(x, -1, z);
        if (!plan.containsKey(pos)) plan.put(pos, Role.FLOOR);
      }
    }

    // Walls: the perimeter, apart from the two-block door opening.
    for (int x = -wallRadius; x <= wallRadius; x++) {
      for (int z = -wallRadius; z <= wallRadius; z++) {
        boolean perimeter = Math.abs(x) == wallRadius || Math.abs(z) == wallRadius;
        if (!perimeter) continue;
        for (int y = 0; y <= wallHeight - 1; y++) {
          SpawnBlockPos pos = new SpawnBlockPos(x, y, z);
          if (isDoorCell(pos)) {
            plan.put(pos, y == 0 ? Role.DOOR_LOWER : Role.DOOR_UPPER);
          } else {
            plan.put(pos, Role.WALL);
          }
        }
      }
    }

    // Roof: closed everywhere above the interior.
    for (int x = -wallRadius; x <= wallRadius; x++) {
      for (int z = -wallRadius; z <= wallRadius; z++) {
        plan.put(new SpawnBlockPos(x, wallHeight, z), Role.ROOF);
      }
    }

    placeFixedFurnishings(plan);
    placeBeds(plan);
    placeChests(plan);
    placeInteriorTorches(plan);
    placeExteriorDecorations(plan);
    placeDoorstep(plan);
    placeExtras(plan);
    return plan;
  }

  private boolean isDoorCell(SpawnBlockPos pos) {
    return pos.x() == 0 && pos.z() == wallRadius() && (pos.y() == 0 || pos.y() == 1);
  }

  private void placeFixedFurnishings(Map<SpawnBlockPos, Role> plan) {
    if (furnishings.craftingTable()) {
      plan.putIfAbsent(craftingTableOffset(), Role.CRAFTING_TABLE);
    }
    if (furnishings.anvil()) {
      plan.putIfAbsent(anvilOffset(), Role.ANVIL);
    }
    if (furnishings.furnace()) {
      plan.putIfAbsent(furnaceOffset(), Role.FURNACE);
    }
    if (furnishings.lodestone()) {
      plan.putIfAbsent(lodestoneOffset(), Role.LODESTONE);
    }
  }

  /**
   * Chests, in placement order. The first is the owner's chest on the back (north) wall; the rest
   * sit along the south wall, clear of the beds. A candidate whose cell is already taken is skipped.
   */
  private void placeChests(Map<SpawnBlockPos, Role> plan) {
    int r = innerRadius();
    List<SpawnBlockPos> candidates = List.of(
        new SpawnBlockPos(1, 0, -r),
        new SpawnBlockPos(-r, 0, r),
        new SpawnBlockPos(-1, 0, r),
        new SpawnBlockPos(1, 0, r));
    int placed = 0;
    for (SpawnBlockPos candidate : candidates) {
      if (placed >= chests) break;
      if (plan.containsKey(candidate)) continue;
      plan.put(candidate, Role.CHEST);
      placed++;
    }
  }

  /**
   * Beds, in placement order, along the west and east walls. Each bed is a two-block foot/head pair
   * occupying ring cells only (never the protected 3x3 centre); a pair whose cells are taken is
   * skipped rather than overlapping another piece.
   */
  private void placeBeds(Map<SpawnBlockPos, Role> plan) {
    int r = innerRadius();
    List<BedPlacement> candidates = List.of(
        new BedPlacement(new SpawnBlockPos(-r, 0, 0), BlockFace.SOUTH),
        new BedPlacement(new SpawnBlockPos(r, 0, 1), BlockFace.SOUTH),
        new BedPlacement(new SpawnBlockPos(-r, 0, -1), BlockFace.NORTH),
        new BedPlacement(new SpawnBlockPos(r, 0, -1), BlockFace.NORTH));
    int placed = 0;
    for (BedPlacement bed : candidates) {
      if (placed >= beds) break;
      SpawnBlockPos foot = bed.foot();
      SpawnBlockPos head = bed.head();
      if (plan.containsKey(foot) || plan.containsKey(head)) continue;
      plan.put(foot, Role.BED_FOOT);
      plan.put(head, Role.BED_HEAD);
      placedBeds.add(bed);
      placed++;
    }
  }

  /** Interior wall torches, high on the back and side walls so nothing hangs over a container. */
  private void placeInteriorTorches(Map<SpawnBlockPos, Role> plan) {
    int r = innerRadius();
    for (SpawnBlockPos torch : List.of(
        new SpawnBlockPos(-1, 2, -r),
        new SpawnBlockPos(1, 2, -r),
        new SpawnBlockPos(-r, 2, 0),
        new SpawnBlockPos(r, 2, 0))) {
      plan.putIfAbsent(torch, Role.WALL_TORCH);
    }
  }

  /**
   * The wall torch on each side of the doorway and the two wall banners, all on the outer (south)
   * face of the south wall at head height. Banners hang further out than the torches, so the two
   * never share a cell and neither sits in the doorway column or on the doorstep.
   */
  private void placeExteriorDecorations(Map<SpawnBlockPos, Role> plan) {
    int z = exteriorZ();
    if (furnishings.exteriorTorches()) {
      plan.putIfAbsent(new SpawnBlockPos(-1, 1, z), Role.EXTERIOR_TORCH);
      plan.putIfAbsent(new SpawnBlockPos(1, 1, z), Role.EXTERIOR_TORCH);
    }
    if (furnishings.exteriorBanners()) {
      plan.putIfAbsent(new SpawnBlockPos(-2, 1, z), Role.BANNER_LEFT);
      plan.putIfAbsent(new SpawnBlockPos(2, 1, z), Role.BANNER_RIGHT);
    }
  }

  private void placeDoorstep(Map<SpawnBlockPos, Role> plan) {
    plan.putIfAbsent(doorstep(), Role.DOORSTEP);
  }

  /** Extra single-block utilities, into the free ring cells, in a fixed order. */
  private void placeExtras(Map<SpawnBlockPos, Role> plan) {
    List<SpawnBlockPos> slots = ringSlots();
    int index = 0;
    for (Material extra : furnishings.extras()) {
      while (index < slots.size() && plan.containsKey(slots.get(index))) {
        index++;
      }
      if (index >= slots.size()) break;
      SpawnBlockPos slot = slots.get(index++);
      plan.put(slot, Role.EXTRA);
      extraMaterials.put(slot, extra);
    }
  }

  /**
   * A bed's foot offset and the direction (one step along one axis) its head lies in. The facing is
   * the direction from foot to head, which is what vanilla's bed block data uses.
   */
  record BedPlacement(SpawnBlockPos foot, BlockFace facing) {
    SpawnBlockPos head() {
      return foot.offset(facing.getModX(), 0, facing.getModZ());
    }
  }

  /**
   * The direction a wall torch at {@code offset} points, i.e. away from the wall it is mounted on.
   * Vanilla requires the sturdy face of the block behind the torch to point in this direction, so
   * the mounting block is always {@code offset - facing}.
   */
  BlockFace wallTorchFacing(SpawnBlockPos offset) {
    int r = innerRadius();
    if (offset.z() == r) return BlockFace.NORTH; // mounted on the south wall
    if (offset.z() == -r) return BlockFace.SOUTH; // mounted on the north wall
    if (offset.x() == r) return BlockFace.WEST; // mounted on the east wall
    return BlockFace.EAST; // mounted on the west wall
  }

  /** Every exterior torch and banner hangs on the outer face of the south wall, facing south. */
  BlockFace exteriorFacing(SpawnBlockPos offset) {
    return BlockFace.SOUTH;
  }

  /** The wall block an exterior torch or banner is mounted on (behind it). */
  SpawnBlockPos exteriorMounting(SpawnBlockPos offset) {
    BlockFace facing = exteriorFacing(offset);
    return offset.offset(-facing.getModX(), -facing.getModY(), -facing.getModZ());
  }
}
