package com.awepeeps.spawnkit;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;

final class SpawnKitContents {
  private final List<Stack> stacks;

  SpawnKitContents(List<Map<?, ?>> entries, Function<String, ItemDefinition> resolver) {
    if (entries == null) throw new IllegalArgumentException("spawn-kit.items must be a list.");
    List<Stack> parsed = new ArrayList<>();
    int slotsRequired = 0;
    for (int index = 0; index < entries.size(); index++) {
      Map<?, ?> entry = entries.get(index);
      if (entry == null) throw invalidEntry(index, "must be a mapping.");
      Object materialValue = entry.get("material");
      if (!(materialValue instanceof String materialName)) {
        throw invalidEntry(index, "material must be a Minecraft material name.");
      }
      ItemDefinition item = resolver.apply(materialName.trim());
      if (item == null || !item.item()) {
        throw invalidEntry(index, "material must name an obtainable item.");
      }
      int amount = positiveInteger(entry.get("amount"), index);
      slotsRequired += (amount + item.maxStackSize() - 1) / item.maxStackSize();
      if (slotsRequired > 27) {
        throw new IllegalArgumentException("Configured items require more than one chest (27 slots).");
      }
      parsed.add(new Stack(item.material(), amount));
    }
    stacks = List.copyOf(parsed);
  }

  List<Stack> stacks() {
    return stacks;
  }

  boolean isEmpty() {
    return stacks.isEmpty();
  }

  static void fill(org.bukkit.inventory.Inventory inventory, List<Stack> stacks) {
    for (Stack stack : stacks) {
      int remaining = stack.amount();
      while (remaining > 0) {
        int batch = Math.min(remaining, stack.material().getMaxStackSize());
        var leftovers = inventory.addItem(new ItemStack(stack.material(), batch));
        if (!leftovers.isEmpty()) {
          throw new IllegalStateException("Validated spawn kit did not fit in one chest.");
        }
        remaining -= batch;
      }
    }
  }

  private static int positiveInteger(Object value, int index) {
    if (!(value instanceof Number number)) {
      throw invalidEntry(index, "amount must be a positive integer.");
    }
    double numeric = number.doubleValue();
    int amount = number.intValue();
    if (!Double.isFinite(numeric) || numeric != amount || amount <= 0 || amount > 1728) {
      throw invalidEntry(index, "amount must be an integer between 1 and 1728.");
    }
    return amount;
  }

  private static boolean isAir(Material material) {
    return material == Material.AIR
        || material == Material.CAVE_AIR
        || material == Material.VOID_AIR;
  }

  static ItemDefinition resolveMaterial(String materialName) {
    Material material = Material.matchMaterial(materialName);
    if (material == null || isAir(material)) return null;
    return new ItemDefinition(material, material.getMaxStackSize(), material.isItem());
  }

  private static IllegalArgumentException invalidEntry(int index, String reason) {
    return new IllegalArgumentException("spawn-kit.items[" + index + "] " + reason);
  }

  record Stack(Material material, int amount) {}
  record ItemDefinition(Material material, int maxStackSize, boolean item) {}
}
