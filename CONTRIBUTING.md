# Contributing

## Opening a change

- Open a pull request against `main`. Keep one PR to one logical change.
- Wait for the **Build** workflow to pass before requesting review.
- Explain the behaviour change (what a player or operator would notice) in the
  PR description, not just the code change.
- Work in an isolated worktree when changing the spawn layout; see
  [`.agents/skills/spawnkit-spawn-layout-change/SKILL.md`](.agents/skills/spawnkit-spawn-layout-change/SKILL.md)
  for the workflow.

## Build and test

- Direct build and test (JDK 25, from the repo root):
  `bash ./gradlew --no-daemon clean test build`.
- CI runs two builds of the same commit and fails on any byte difference, then
  prints the jar SHA-256. A pull request that makes the build
  non-deterministic cannot be accepted.
- Keep the jar reproducible: anyone rebuilding the exact merge commit must get
  a jar with the SHA-256 published by the merged Build run. Releases follow
  [RELEASING.md](RELEASING.md).

## House rules

- Add a `CHANGELOG.md` entry for every user-visible change.
- If the config changes, update the config table in `README.md` and the
  bundled `src/main/resources/config.yml` defaults together.
- Keep docs accurate: `README.md` and [AGENTS.md](AGENTS.md) must describe the
  behaviour that actually ships.
- Preserve the documented invariants (nothing in the arrival space, the house
  is never protected or rebuilt, `issued-kits.yml` is written before any block
  change, additive-only layout upgrades); see [AGENTS.md](AGENTS.md).
- This is a **public** repository: never add host paths, server or world names,
  coordinates, seeds, instance IDs, logs, config dumps, backups, secrets, or
  personal author data — in code, docs, tests, or commit messages.

## Bugs and security

- Bugs: open a GitHub issue with a minimal, generic reproduction.
- Security: report privately per [SECURITY.md](SECURITY.md), never in an issue.
