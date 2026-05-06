# VortexMobs

VortexMobs is an adaptive combat AI system for Minecraft servers. Instead of shipping fixed mobs with flat difficulty, it trains a server-specific combat profile from how players actually win fights.

If your players kite with bows, mobs bias toward gap-closing, longer pursuit, and anti-focus-fire pressure. If they turtle with shields or abuse high ground, the brain shifts into counterplay. Every server develops its own meta pressure curve.

## What ships in this repo

- `vortexmobs-server`: one shared Paper, Purpur, Folia, and Spigot implementation compiled against the Spigot API surface, then emitted as release-named jars.
- `vortexmobs-fabric-1_21_11`: Fabric build for the stable `1.21.11` line.
- `vortexmobs-fabric-26_1_2`: Fabric build for the stable `26.1.2` line.
- `vortexmobs-core`: shared adaptive-learning engine and balancing model.

## Core ideas

- Unique AI per server: each server gets its own `ServerGenome` seed and persistent combat brain.
- Learns from player tactics: projectile kills, high-ground abuse, focus fire, burst damage, shield reliance, and crowd pressure all feed the model.
- Harder over time: the more one-sided the fights become, the more the server brain unlocks mobility, aggression, and boss abilities.
- Public-API only: the current implementation uses stable public APIs rather than fragile NMS or mixins so the product can span Paper, Purpur, Folia, Spigot, and Fabric cleanly.

## Adaptive boss

VortexMobs ships an adaptive boss path as the first prestige feature. The boss evolves through stages and unlocks:

- `DASH`: hard punishes ranged kiting.
- `ROAR`: punishes stacked players with a short disruption window.
- `INTERCEPTORS`: summons support mobs when the server meta over-invests into ranged deletion.

## Commands

- Server: `/vortexmobs brain`, `/vortexmobs spawnboss`, `/vortexmobs resetbrain confirm`, `/vortexmobs reload`
- Fabric: `/vortexmobs brain`, `/vortexmobs spawnboss`, `/vortexmobs resetbrain confirm`

## Build

1. Bootstrap the Gradle wrapper with Gradle `8.11.1`.
2. Run `gradlew.bat build`.
3. Collect jars with `gradlew.bat copyAllJars` or generate the release archive with `gradlew.bat zipRelease`.

Deliverables are copied into each module's `jar/` folder and also the root `jar/` folder. The full release bundle is written as `jar/VortexMobs-release-<version>.zip`.

Root release outputs:

- `jar/VortexMobs-paper-folia-<version>.jar`
- `jar/VortexMobs-purpur-<version>.jar`
- `jar/VortexMobs-spigot-<version>.jar`
- `jar/VortexMobs-fabric-1.21.x-<version>.jar`
- `jar/VortexMobs-fabric-26.x-<version>.jar`
- `jar/VortexMobs-release-<version>.zip`

The Paper, Purpur, Folia, and Spigot jars are intentionally the same implementation binary under platform-specific names because the code stays on the common Spigot API and detects Folia scheduling reflectively at runtime. Fabric remains version-split because the upstream Minecraft and Fabric APIs diverge between `1.21.x` and `26.x`.

## Smart upgrade path

This initial release is structured so the next updates can be layered in without breaking save data:

- biome-specific sub-brains
- raid and dungeon encounter fingerprints
- network-sync brain replication via Redis or SQL
- admin dashboard and heatmaps
- elite mutation pools and season resets
- per-mob-family learning lanes rather than one global lane

The current data format is versioned to make those upgrades additive.