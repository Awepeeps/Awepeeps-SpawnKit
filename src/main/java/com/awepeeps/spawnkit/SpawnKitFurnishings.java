package com.awepeeps.spawnkit;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import org.bukkit.DyeColor;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;

/**
 * The configurable set of house furnishings, read from
 * {@code spawn-kit.house.furnishings}. Pure data: it never touches a world, so the validation and
 * the defaults can be unit tested.
 *
 * <p>Every toggle defaults to {@code true} and every existing config keeps working, because a
 * missing section means "all defaults". Nothing here ever throws: an unknown or disallowed extra or
 * an invalid banner colour is skipped with a single warning and the defaults are used instead, so a
 * typo can never disable the plugin (and with it spawn protection).
 */
final class SpawnKitFurnishings {
  static final String DEFAULT_LEFT_BANNER = "WHITE";
  static final String DEFAULT_RIGHT_BANNER = "RED";

  /**
   * The single-block utilities an operator may add through {@code extras}. Deliberately small and
   * harmless: no chests or shulker boxes (which could be confused with the issued supply chest),
   * no gravity blocks other than the anvil, no redstone, no fluids and nothing that needs special
   * placement rules. {@code BARREL} and {@code ENDER_CHEST} are allowed on purpose.
   */
  static final List<Material> EXTRA_ALLOW_LIST = List.of(
      Material.SMITHING_TABLE, Material.GRINDSTONE, Material.STONECUTTER,
      Material.CARTOGRAPHY_TABLE, Material.LOOM, Material.BARREL, Material.ENDER_CHEST,
      Material.FLETCHING_TABLE, Material.COMPOSTER, Material.LECTERN, Material.JUKEBOX,
      Material.BREWING_STAND, Material.RESPAWN_ANCHOR);

  private final boolean anvil;
  private final boolean furnace;
  private final boolean lodestone;
  private final boolean craftingTable;
  private final boolean exteriorBanners;
  private final boolean exteriorTorches;
  private final List<Material> extras;
  private final String leftBanner;
  private final String rightBanner;

  SpawnKitFurnishings(boolean anvil, boolean furnace, boolean lodestone,
                              boolean craftingTable, boolean exteriorBanners,
                              boolean exteriorTorches, List<Material> extras,
                              String leftBanner, String rightBanner) {
    this.anvil = anvil;
    this.furnace = furnace;
    this.lodestone = lodestone;
    this.craftingTable = craftingTable;
    this.exteriorBanners = exteriorBanners;
    this.exteriorTorches = exteriorTorches;
    this.extras = List.copyOf(extras);
    this.leftBanner = leftBanner;
    this.rightBanner = rightBanner;
  }

  /** Everything on, no extras, white banner on the left and red on the right. */
  static SpawnKitFurnishings defaults() {
    return new SpawnKitFurnishings(true, true, true, true, true, true, List.of(),
        DEFAULT_LEFT_BANNER, DEFAULT_RIGHT_BANNER);
  }

  /**
   * Reads the {@code furnishings} section. A missing section returns the defaults; a section that is
   * present but omits a key falls back to that key's default.
   */
  static SpawnKitFurnishings parse(ConfigurationSection section, Consumer<String> warn) {
    if (section == null) return defaults();
    List<Material> extras = new ArrayList<>();
    List<String> skipped = new ArrayList<>();
    for (String raw : section.getStringList("extras")) {
      Material material = raw == null ? null
          : Material.matchMaterial(raw.trim().toUpperCase(Locale.ROOT));
      if (material == null || !EXTRA_ALLOW_LIST.contains(material)) {
        skipped.add(String.valueOf(raw));
        continue;
      }
      extras.add(material);
    }
    if (!skipped.isEmpty()) {
      warn.accept("Spawn Kit furnishings.extras: skipped " + skipped.size()
          + " unsupported entr" + (skipped.size() == 1 ? "y" : "ies") + " " + skipped
          + "; allowed: " + EXTRA_ALLOW_LIST + ".");
    }
    return new SpawnKitFurnishings(
        section.getBoolean("anvil", true),
        section.getBoolean("furnace", true),
        section.getBoolean("lodestone", true),
        section.getBoolean("crafting-table", true),
        section.getBoolean("exterior-banners", true),
        section.getBoolean("exterior-torches", true),
        extras,
        bannerColour(section, "banners.left", DEFAULT_LEFT_BANNER, warn),
        bannerColour(section, "banners.right", DEFAULT_RIGHT_BANNER, warn));
  }

  private static String bannerColour(ConfigurationSection section, String key, String fallback,
                                     Consumer<String> warn) {
    String raw = section.getString(key, fallback);
    if (raw == null) return fallback;
    String normalised = raw.trim().toUpperCase(Locale.ROOT);
    if (isValidBannerColour(normalised)) return normalised;
    warn.accept("Spawn Kit furnishings." + key + "=" + raw
        + " is not a dye colour; using " + fallback + ".");
    return fallback;
  }

  /** True when {@code colour} names a dye colour that has a wall banner material. */
  static boolean isValidBannerColour(String colour) {
    if (colour == null || colour.isBlank()) return false;
    try {
      DyeColor.valueOf(colour.trim().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException error) {
      return false;
    }
    return Material.matchMaterial(colour.trim().toUpperCase(Locale.ROOT) + "_WALL_BANNER") != null;
  }

  /** The wall banner material for a validated colour name. */
  static Material bannerMaterial(String colour) {
    return Material.matchMaterial(colour + "_WALL_BANNER");
  }

  boolean anvil() {
    return anvil;
  }

  boolean furnace() {
    return furnace;
  }

  boolean lodestone() {
    return lodestone;
  }

  boolean craftingTable() {
    return craftingTable;
  }

  boolean exteriorBanners() {
    return exteriorBanners;
  }

  boolean exteriorTorches() {
    return exteriorTorches;
  }

  List<Material> extras() {
    return extras;
  }

  String leftBanner() {
    return leftBanner;
  }

  String rightBanner() {
    return rightBanner;
  }
}
