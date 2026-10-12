# Awepeeps Spawn Kit

Configurable Paper plugin for setting up a one-time primary Overworld spawn.
The consumer independently chooses whether to place a central lodestone and
BlueMap marker, build a small torch-lit shelter, and add a starter chest.
The consumer owns the date window and chest contents.

Nothing is issued by default. The plugin defaults to disabled, with no date
window, marker, house, and chest disabled, and no items. If enabled, the
server-provided chest items must be valid and fit within one chest. Invalid
dates, timezones, materials, amounts, or oversized kits fail closed.

## Server-provided configuration

The server operator configures `plugins/AwepeepsSpawnKit/config.yml`:

```yaml
spawn-kit:
  enabled: false
  starts-on: ""
  expires-on: ""
  timezone: ""
  spawn-marker:
    enabled: false
    name: "World Spawn"
  house:
    enabled: false
    chest:
      enabled: false
      items: []
```

To use it, the server changes `enabled`, chooses the inclusive last issue date
and timezone, and provides the item list:

```yaml
spawn-kit:
  enabled: true
  starts-on: "<inclusive-start-date>"
  expires-on: "<inclusive-date>"
  timezone: "<IANA-timezone>"
  spawn-marker:
    enabled: true
    name: "World Spawn"
  house:
    enabled: true
    chest:
      enabled: true
      items:
        - material: <MINECRAFT_MATERIAL>
          amount: <positive-integer>
```

The date window is inclusive in the configured timezone. The plugin activates
at local midnight on `starts-on` and stops at local midnight after `expires-on`.
After changing configuration, restart the server. Materials are Bukkit
`Material` names. Amounts must be positive integers; amounts above an item's
stack size are split into multiple stacks. All configured items must fit within
27 chest slots. The marker toggle places a lodestone at the shelter's center
and publishes a named POI to BlueMap when that optional plugin is installed.
The shelter is a compact, solid-roofed, windowless oak-and-cobblestone house
with a door, one white bed, and interior/exterior torches. A chest and its
contents are independently enabled. The plugin includes no server-specific
items, dates, or season names. See
[`examples/consumer-config.yml`](examples/consumer-config.yml) for a generic
example with neutral items and dates that should be replaced for each server.

## Placement and reset behavior

For a shelter, the plugin searches already-loaded terrain within a bounded
radius around the primary Overworld spawn. It requires a flat, safe, clear
footprint and does not load chunks or overwrite structures. If no safe location
is available, it retries later. The consuming server should set
`spawn-protection` to `2` in `server.properties` for a 5-by-5 protected block
area centered on the resulting spawn point; the plugin does not modify server
properties.

An issuance tombstone is stored per world UUID in
`plugins/AwepeepsSpawnKit/issued-kits.yml`; the chest also receives an ownership
marker. Restarting does not rebuild the house, restock a depleted chest, or
replace a removed structure. For a new disposable world, the deployment reset
must preserve the issuance file in its pre-wipe backup and remove it only after
every world directory has been deleted successfully. This supports servers
that reuse a world UUID after a reset.

## Build and tests

Requires JDK 25. Run:

```sh
bash ./gradlew --no-daemon clean test build
```

The reproducible jar is written to
`build/libs/awepeeps-spawn-kit-1.1.0.jar`.
