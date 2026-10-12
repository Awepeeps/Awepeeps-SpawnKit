# Changelog

## 1.1.4

- Load and temporarily ticket spawn-search chunks asynchronously, then release
  tickets after setup.
- Accept and clear whitelisted surface vegetation and snow while preserving
  structures, containers, and unsafe terrain.
- Keep the world spawn unchanged and place the supply chest outside its
  configured protection square.
- Publish the BlueMap spawn marker independently of shelter setup and avoid
  repeating unchanged deferral diagnostics.
