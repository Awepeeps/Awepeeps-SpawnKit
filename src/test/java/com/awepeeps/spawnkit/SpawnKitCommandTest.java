package com.awepeeps.spawnkit;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import org.bukkit.command.CommandSender;
import org.junit.jupiter.api.Test;

/**
 * The command's own argument validation, exercised without a server. The early-rejection paths
 * never touch the manager or a world, so a recording {@link CommandSender} proxy is enough.
 */
class SpawnKitCommandTest {
  private static final List<String> messages = new ArrayList<>();

  /** A sender that records every sendMessage call; all other calls answer a harmless default. */
  private static CommandSender recordingSender() {
    messages.clear();
    return (CommandSender) Proxy.newProxyInstance(
        SpawnKitCommandTest.class.getClassLoader(), new Class<?>[] {CommandSender.class},
        (proxy, method, args) -> {
          if (method.getName().equals("sendMessage") && args != null && args.length > 0) {
            if (args[0] instanceof String text) {
              messages.add(text);
            } else if (args[0] instanceof String[] lines) {
              messages.add(String.join(" ", lines));
            }
            return null;
          }
          Class<?> type = method.getReturnType();
          if (type == boolean.class) return false;
          if (type == int.class) return 0;
          if (type == long.class) return 0L;
          return null;
        });
  }

  private static SpawnKitCommand command() {
    return new SpawnKitCommand(null);
  }

  @Test
  void buildWithSomethingOtherThanConfirmIsRejected() {
    assertTrue(command().onCommand(recordingSender(), null, "spawnkit",
        new String[] {"build", "no"}));
    assertEquals(1, messages.size());
    assertTrue(messages.get(0).contains("Add 'confirm'"), messages.toString());
  }

  @Test
  void theBareCommandShowsTheUsage() {
    assertTrue(command().onCommand(recordingSender(), null, "spawnkit",
        new String[0]));
    assertEquals(1, messages.size());
    assertTrue(messages.get(0).contains("Usage:"), messages.toString());
  }

  @Test
  void unknownArgumentsShowTheUsage() {
    assertTrue(command().onCommand(recordingSender(), null, "spawnkit",
        new String[] {"build", "confirm", "nonsense"}));
    assertEquals(1, messages.size());
    assertTrue(messages.get(0).contains("Usage:"), messages.toString());
    assertTrue(messages.get(0).contains("force"), messages.toString());
  }

  @Test
  void itemsWithoutForceIsRejectedBeforeAnyBuild() {
    assertTrue(command().onCommand(recordingSender(), null, "spawnkit",
        new String[] {"build", "confirm", "items"}));
    assertEquals(1, messages.size());
    assertTrue(messages.get(0).contains("'items' only applies together with 'force'"),
        messages.toString());
  }
}
