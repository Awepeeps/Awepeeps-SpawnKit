# Awepeeps Spawn Kit

Configurable Paper plugin for placing one shared, one-time kit chest near the
primary Overworld spawn. The plugin provides the safe placement and issuance
mechanism; the consuming Minecraft server supplies the contents, on/off choice,
and inclusive start/end date window in its own plugin configuration.

Nothing is issued by default. The plugin defaults to disabled, with no date
window and no items. If enabled, its server-provided item list must be valid
and fit within one chest. Invalid dates, timezones, materials, amounts, or
oversized kits fail closed.

## Server-provided configuration

The server operator configures `plugins/AwepeepsSpawnKit/config.yml`:

```yaml
spawn-kit:
  enabled: false
  starts-on: ""
  expires-on: ""
  timezone: ""
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
  items:
    - material: <MINECRAFT_MATERIAL>
      amount: <positive-integer>
```

The date window is inclusive in the configured timezone. The plugin activates
at local midnight on `starts-on` and stops at local midnight after `expires-on`.
After changing configuration, restart the server. Materials are Bukkit
`Material` names. Amounts must be positive integers; amounts above an item's
stack size are split into multiple stacks. All configured items must fit within
27 chest slots. No content, dates, season names, or GUI is built into the
plugin; the server config owns its issue window. See
[`examples/consumer-config.yml`](examples/consumer-config.yml) for a generic
example with neutral items and dates that should be replaced for each server.

## Placement and reset behavior

The plugin searches already-loaded terrain within a bounded radius around the
primary Overworld spawn. It does not load chunks, overwrite blocks, place on
hazardous/unsafe ground, intrude into spawn protection, or place beside an
existing chest. If no safe location is available, it retries later.

An issuance tombstone is stored per world UUID in
`plugins/AwepeepsSpawnKit/issued-kits.yml`; the chest also receives an ownership
marker. Restarting will not refill a depleted chest or replace a removed one.
For a new disposable world, the deployment reset must preserve the issuance
file in its pre-wipe backup and remove it only after every world directory has
been deleted successfully. This supports servers that reuse a world UUID after
a reset.

## Build and tests

Requires JDK 25. Run:

```sh
bash ./gradlew --no-daemon clean test build
```

The reproducible jar is written to
`build/libs/awepeeps-spawn-kit-1.0.0.jar`.
