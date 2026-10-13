package com.awepeeps.spawnkit;

import java.util.List;
import java.util.Locale;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

/** Operator command to build or migrate the spawn layout for the current world. */
final class SpawnKitCommand implements CommandExecutor, TabCompleter {
  private static final String USAGE = "Usage: /%s build confirm [force] [items] [supplies]";

  private final SpawnKitManager manager;

  SpawnKitCommand(SpawnKitManager manager) {
    this.manager = manager;
  }

  @Override
  public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
    if (args.length < 2 || !args[0].equalsIgnoreCase("build")) {
      sender.sendMessage(USAGE.formatted(label));
      return true;
    }
    if (!args[1].equalsIgnoreCase("confirm")) {
      sender.sendMessage("Add 'confirm' to build: /" + label + " build confirm [force] [items]"
          + " [supplies]");
      return true;
    }
    boolean supplies = false;
    boolean force = false;
    boolean items = false;
    for (int i = 2; i < args.length; i++) {
      switch (args[i].toLowerCase(Locale.ROOT)) {
        case "supplies" -> supplies = true;
        case "force" -> force = true;
        case "items" -> items = true;
        default -> {
          sender.sendMessage(USAGE.formatted(label));
          return true;
        }
      }
    }
    if (items && !force) {
      sender.sendMessage("'items' only applies together with 'force': /" + label
          + " build confirm force items");
      return true;
    }
    World world = resolveWorld(sender);
    if (world == null) {
      sender.sendMessage("No Overworld is loaded; nothing was built.");
      return true;
    }
    if (!sender.hasPermission("aweepsspawnkit.admin")) {
      sender.sendMessage("You do not have permission to build the spawn layout.");
      return true;
    }
    sender.sendMessage(manager.buildNow(world, supplies, force, items));
    return true;
  }

  private World resolveWorld(CommandSender sender) {
    if (sender instanceof Player player) {
      return player.getWorld();
    }
    return org.bukkit.Bukkit.getWorlds().stream()
        .filter(candidate -> candidate.getEnvironment() == World.Environment.NORMAL)
        .findFirst().orElse(null);
  }

  @Override
  public List<String> onTabComplete(CommandSender sender, Command command, String alias,
                                    String[] args) {
    if (args.length == 1) return List.of("build");
    if (args.length == 2) return List.of("confirm");
    if (args.length == 3) return List.of("force", "items", "supplies");
    if (args.length == 4) return List.of("items", "supplies");
    if (args.length == 5) return List.of("supplies");
    return List.of();
  }
}
