# Single Payload Jar (Arthas-like) Design

**Date:** 2026-03-06

## Problem

Java-Sleuth historically carried multiple “payload” mental models (core fat-jar vs container fat-jar). Some legacy
compatibility logic remained in the bootstrap agent (and scripts/docs), which created misleading “half-supported”
paths:

- Runtime code still suggested “fallback to core fat-jar” when the container jar is missing.
- Build output and docs/scripts did not consistently produce or reference the same artifact set.

This increases maintenance burden and operator confusion.

## Goal

Achieve the same operator experience as Arthas:

1. **One official payload jar** to be loaded by the bootstrap agent (no silent switching between architectures).
2. **Fail fast** when the payload jar is missing, with explicit guidance.
3. **Distribution uses stable filenames** and colocates jars, so runtime lookup is deterministic.

## Official Runtime Artifacts (Distribution)

Stable filenames in a single directory (Arthas-like):

- `java-sleuth-launcher.jar` (operator-side launcher)
- `java-sleuth-agent.jar` (bootstrap agent, `-javaagent`)
- `java-sleuth-container.jar` (**the only payload**, loaded in isolated ClassLoader)
- `java-sleuth-bootstrap-bridge.jar` (bootstrap-visible bridge/spy classes appended to bootstrap search)

## Runtime Lookup Rules

**Bootstrap agent must only ever load the container payload**.

Lookup order for payload jar:

1. Explicit override via agent args: `containerJar=<path>` (applied to sysprops).
2. Explicit override via system property/env: `-Dsleuth.agent.container.jar=<path>` / `SLEUTH_AGENT_CONTAINER_JAR`.
3. Distribution default: look for `java-sleuth-container.jar` next to the bootstrap agent jar.
4. Dev fallback: scan common build directories (controlled by `-Dsleuth.locator.allowCwdScan`).

No core-jar fallback.

## Packaging

Add a Maven `packaging` module that builds a zip/dir distribution and renames versioned build artifacts to stable
filenames (similar to Arthas `packaging` assembly).

## Non-Goals

- Removing internal modules (`core`, `container`) or changing module naming.
- Keeping “core fat-jar” as an officially supported payload path.

