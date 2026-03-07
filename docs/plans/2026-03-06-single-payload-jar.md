# Single Payload Jar (Arthas-like) - Implementation Notes

**Date:** 2026-03-06

## Summary

This change set makes Java-Sleuth behave like Arthas from an operator perspective:

- Bootstrap agent only loads one official payload (`java-sleuth-container.jar`).
- When missing, agent fails fast with explicit hints (no silent fallback to another artifact).
- Distribution artifacts use stable filenames and are colocated via a new `packaging` module.

## Key Changes

- `JarLocator` supports stable sibling names:
  - `java-sleuth-agent.jar`
  - `java-sleuth-container.jar`
- Bootstrap agent removes core fallback and supports stable bridge jar name:
  - `java-sleuth-bootstrap-bridge.jar`
- Added `packaging/` Maven module producing `zip`/`dir` distribution with stable jar names.
- Updated scripts and docs to use `sleuth.agent.container.jar` and container artifact paths.

