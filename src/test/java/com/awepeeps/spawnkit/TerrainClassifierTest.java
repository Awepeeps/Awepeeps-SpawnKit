package com.awepeeps.spawnkit;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.bukkit.Material;
import org.junit.jupiter.api.Test;

/**
 * The classifier table. The "natural" side is the exact block list the real failing footprint and
 * its surroundings contained, plus the other blocks world generation puts on or near the surface.
 * The "refuse" side is everything that only a player or a structure produces.
 *
 * <p>The production classifier also consults the vanilla tags, but a plain unit test has no server
 * registry to read them from, so the tag questions answer {@code false} here and these assertions
 * exercise the explicit rules (which must cover the whole list on their own). The tag path is
 * asserted separately with a stub.
 */
class TerrainClassifierTest {
  /** Exactly the distinct blocks found in the 1.3.0 scan volume of the real failing footprint. */
  private static final List<Material> REAL_FOOTPRINT = List.of(
      Material.AIR, Material.DIRT, Material.GRASS_BLOCK, Material.STONE, Material.DIORITE,
      Material.OAK_LEAVES, Material.OAK_LOG, Material.LEAF_LITTER, Material.BUSH,
      Material.SHORT_GRASS);

  /** Other surface blocks world generation places, none of which may ever refuse a site. */
  private static final List<Material> GENERATED_SURFACE = List.of(
      Material.TALL_GRASS, Material.FERN, Material.LARGE_FERN, Material.SHORT_DRY_GRASS,
      Material.TALL_DRY_GRASS, Material.DEAD_BUSH, Material.DANDELION, Material.POPPY,
      Material.CORNFLOWER, Material.OXEYE_DAISY, Material.LILAC, Material.SUNFLOWER,
      Material.ROSE_BUSH, Material.PEONY, Material.PINK_PETALS, Material.WILDFLOWERS,
      Material.FIREFLY_BUSH, Material.OPEN_EYEBLOSSOM, Material.CLOSED_EYEBLOSSOM,
      Material.TORCHFLOWER, Material.PITCHER_PLANT, Material.BIRCH_LOG, Material.BIRCH_LEAVES,
      Material.SPRUCE_LOG, Material.SPRUCE_LEAVES, Material.JUNGLE_LOG, Material.JUNGLE_LEAVES,
      Material.ACACIA_LOG, Material.DARK_OAK_LOG, Material.CHERRY_LOG, Material.PALE_OAK_LOG,
      Material.MANGROVE_LOG, Material.MANGROVE_ROOTS, Material.MUDDY_MANGROVE_ROOTS,
      Material.AZALEA, Material.FLOWERING_AZALEA, Material.AZALEA_LEAVES,
      Material.FLOWERING_AZALEA_LEAVES, Material.VINE, Material.GLOW_LICHEN,
      Material.CAVE_VINES, Material.CAVE_VINES_PLANT, Material.HANGING_ROOTS,
      Material.BROWN_MUSHROOM, Material.RED_MUSHROOM, Material.PUMPKIN, Material.MELON,
      Material.SUGAR_CANE, Material.BAMBOO, Material.CACTUS, Material.CACTUS_FLOWER,
      Material.SWEET_BERRY_BUSH, Material.COCOA, Material.BEE_NEST, Material.MOSS_CARPET,
      Material.BIG_DRIPLEAF, Material.SMALL_DRIPLEAF, Material.SPORE_BLOSSOM,
      Material.SEAGRASS, Material.TALL_SEAGRASS, Material.KELP, Material.LILY_PAD,
      Material.SNOW, Material.POWDER_SNOW, Material.SNOW_BLOCK, Material.ICE,
      Material.PACKED_ICE, Material.BLUE_ICE, Material.FROSTED_ICE,
      Material.GRASS_BLOCK, Material.DIRT, Material.COARSE_DIRT, Material.ROOTED_DIRT,
      Material.PODZOL, Material.MYCELIUM, Material.MUD, Material.CLAY, Material.GRAVEL,
      Material.SAND, Material.RED_SAND, Material.SUSPICIOUS_SAND, Material.STONE,
      Material.GRANITE, Material.DIORITE, Material.ANDESITE, Material.TUFF,
      Material.DEEPSLATE, Material.CALCITE, Material.DRIPSTONE_BLOCK,
      Material.POINTED_DRIPSTONE, Material.BEDROCK, Material.SANDSTONE,
      Material.RED_SANDSTONE, Material.TERRACOTTA, Material.ORANGE_TERRACOTTA,
      Material.MOSS_BLOCK, Material.AMETHYST_BLOCK, Material.OBSIDIAN, Material.MAGMA_BLOCK,
      Material.BASALT, Material.SMOOTH_BASALT, Material.BLACKSTONE,
      Material.COAL_ORE, Material.IRON_ORE, Material.COPPER_ORE, Material.DEEPSLATE_IRON_ORE,
      Material.WATER, Material.LAVA);

  /** Everything a player or a generated structure produces: each must refuse the site. */
  private static final List<Material> REFUSED = List.of(
      Material.CHEST, Material.TRAPPED_CHEST, Material.BARREL, Material.FURNACE,
      Material.BLAST_FURNACE, Material.SMOKER, Material.HOPPER, Material.DROPPER,
      Material.DISPENSER, Material.SHULKER_BOX, Material.WHITE_SHULKER_BOX, Material.SPAWNER,
      Material.BEEHIVE, Material.CRAFTING_TABLE, Material.ANVIL, Material.LODESTONE,
      Material.OAK_PLANKS, Material.SPRUCE_PLANKS, Material.OAK_STAIRS, Material.OAK_SLAB,
      Material.OAK_FENCE, Material.OAK_FENCE_GATE, Material.OAK_DOOR, Material.OAK_TRAPDOOR,
      Material.OAK_SIGN, Material.GLASS, Material.WHITE_WOOL, Material.BRICKS,
      Material.STONE_BRICKS, Material.COBBLESTONE, Material.MOSSY_COBBLESTONE,
      Material.STRIPPED_OAK_LOG, Material.STRIPPED_OAK_WOOD, Material.WHITE_BED,
      Material.RAIL, Material.POWERED_RAIL, Material.TORCH, Material.WALL_TORCH,
      Material.REDSTONE_TORCH, Material.LEVER, Material.STONE_BUTTON, Material.FARMLAND,
      Material.WHEAT, Material.CARROTS, Material.POTATOES, Material.BEETROOTS,
      Material.PUMPKIN_STEM, Material.MELON_STEM, Material.GLOWSTONE, Material.IRON_BLOCK,
      Material.DIAMOND_BLOCK, Material.ENCHANTING_TABLE, Material.BOOKSHELF, Material.HAY_BLOCK,
      Material.SEA_LANTERN, Material.PRISMARINE);

  @Test
  void theRealFailingFootprintIsEntirelyAccepted() {
    for (Material material : REAL_FOOTPRINT) {
      SpawnZonePolicy.BlockKind kind = SpawnZonePolicy.classify(material);
      assertNotEquals(SpawnZonePolicy.BlockKind.OCCUPIED, kind, material + " must not refuse the site");
      assertTrue(SpawnZonePolicy.isReplaceableByBuild(material), material + " must be replaceable");
    }
    // The two blocks that actually broke 1.3.0 are clearable vegetation, not "occupied".
    assertEquals(SpawnZonePolicy.BlockKind.VEGETATION, SpawnZonePolicy.classify(Material.LEAF_LITTER));
    assertEquals(SpawnZonePolicy.BlockKind.VEGETATION, SpawnZonePolicy.classify(Material.BUSH));
  }

  @Test
  void everyGeneratedSurfaceBlockIsNaturalOrClearable() {
    for (Material material : GENERATED_SURFACE) {
      SpawnZonePolicy.BlockKind kind = SpawnZonePolicy.classify(material);
      assertNotEquals(SpawnZonePolicy.BlockKind.OCCUPIED, kind, material + " must not refuse the site");
    }
  }

  @Test
  void everyPlayerOrStructureBlockIsRefused() {
    for (Material material : REFUSED) {
      assertEquals(SpawnZonePolicy.BlockKind.OCCUPIED, SpawnZonePolicy.classify(material),
          material + " must refuse the site");
      assertFalse(SpawnZonePolicy.isClearableVegetation(material), material + " must not be cleared");
      assertFalse(SpawnZonePolicy.isReplaceableByBuild(material), material + " must not be replaced");
    }
  }

  @Test
  void aGeneratedBeeNestGoesWithItsTreeButACraftedBeehiveIsRefused() {
    assertTrue(SpawnZonePolicy.isTreePart(Material.BEE_NEST));
    assertEquals(SpawnZonePolicy.BlockKind.VEGETATION, SpawnZonePolicy.classify(Material.BEE_NEST));
    assertEquals(SpawnZonePolicy.BlockKind.OCCUPIED, SpawnZonePolicy.classify(Material.BEEHIVE));
  }

  @Test
  void treeTrunkAndAttachmentsAreRecognised() {
    assertTrue(SpawnZonePolicy.isTreeTrunk(Material.OAK_LOG));
    assertTrue(SpawnZonePolicy.isTreeTrunk(Material.PALE_OAK_LOG));
    assertTrue(SpawnZonePolicy.isTreeTrunk(Material.MANGROVE_WOOD));
    assertFalse(SpawnZonePolicy.isTreeTrunk(Material.STRIPPED_OAK_LOG));
    assertTrue(SpawnZonePolicy.isTreePart(Material.OAK_LEAVES));
    assertTrue(SpawnZonePolicy.isTreePart(Material.VINE));
    assertTrue(SpawnZonePolicy.isTreePart(Material.COCOA));
    assertFalse(SpawnZonePolicy.isTreePart(Material.SHORT_GRASS));
    assertFalse(SpawnZonePolicy.isTreePart(Material.OAK_PLANKS));
  }

  @Test
  void theVanillaTagsAreConsultedWhenTheServerProvidesThem() {
    // A stub standing in for the server's tag registry: it claims the LEAVES tag contains a stripped
    // log and the DIRT tag contains planks, which the explicit rules alone would refuse.
    SpawnZonePolicy.TerrainTags tags = (tag, material) ->
        (tag == SpawnZonePolicy.TerrainTag.LEAVES && material == Material.STRIPPED_OAK_LOG)
            || (tag == SpawnZonePolicy.TerrainTag.DIRT && material == Material.OAK_PLANKS);
    assertEquals(SpawnZonePolicy.BlockKind.VEGETATION,
        SpawnZonePolicy.classify(Material.STRIPPED_OAK_LOG, tags));
    assertEquals(SpawnZonePolicy.BlockKind.NATURAL,
        SpawnZonePolicy.classify(Material.OAK_PLANKS, tags));

    // Without tags the explicit rules still refuse both.
    assertEquals(SpawnZonePolicy.BlockKind.OCCUPIED,
        SpawnZonePolicy.classify(Material.STRIPPED_OAK_LOG, SpawnZonePolicy.TerrainTags.NONE));
    assertEquals(SpawnZonePolicy.BlockKind.OCCUPIED,
        SpawnZonePolicy.classify(Material.OAK_PLANKS, SpawnZonePolicy.TerrainTags.NONE));
  }
}
