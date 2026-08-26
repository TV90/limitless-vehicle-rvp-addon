# Active ECM Implementation Task — Prompt for an AI Coding Agent

> Hand this prompt (plus the design doc) to another AI (e.g. Muse Shark) to implement the feature.
> Design doc: `docs/plan/RVP_Active_ECM_(Active_EW)_Research_and_Implementation.md`
> Chinese design doc (same content): `docs/plan/RVP主动ECM（Active EW）调研与实现方案.md`

---

## ROLE

You are a senior Minecraft Forge 1.20.1 mod developer working on `ywzj_rvp`, a sub-mod ("addon") of the mod `ywzj_vehicle`. Implement the **Active ECM (Active EW)** feature exactly as specified below and in the referenced design document. The design doc has already been fully researched; do not re-research or redesign — follow it.

## PROJECT CONTEXT

- Mod ID: `ywzj_rvp`. Minecraft 1.20.1, Forge (Gradle 8.8, Java 17 runtime).
- Working directory: `D:\ywzj\ywzj\ywzj_rvp`
- It depends on the `ywzj_vehicle` mod (vanilla codebase). Path to its sources: `D:\ywzj\ywzj\ywzj_vehicle`
- Build command: run `./gradlew build` in the project root. Fix all compile errors until it passes.
- Project rules (from `AGENTS.md` at repo root — READ IT FIRST):
  1. **DO NOT modify `ywzj_vehicle` sources.** New logic goes in `ywzj_rvp`.
  2. **Every field and method must carry a clear Chinese comment** explaining purpose/units/defaults.
  3. **MIXIN DISCIPLINE (critical)**: Do NOT add any new Mixin unless every alternative is exhausted and the user approves. In particular the classes `RadarUnit`, `WeaponUnit`, `AbstractVehicle`, `WarningReceiver` are on a blacklist — **zero new Mixins on them** (dirty Mixins on these classes previously caused dedicated-server startup crashes from `@OnlyIn(CLIENT)` class references). Only use their public methods (`setLockedEntity`/`getLockedEntity`/`getDetectedEntities`/`targets.put`/...) or add fields to RVP-owned classes.
  4. Public (common) code must never reference `@OnlyIn(CLIENT)` types; client logic goes in the `client` package and is bridged through `RVP_ClientActionsAccess` if a server path needs it.
  5. Read `docs/README.md` and the design doc's "Related docs" before writing code.

## THE FEATURE (summary — the design doc is authoritative)

An **Active ECM** device: a bone-module (like ECM_PASSIVE/DIRCM) on vehicles. Player presses a key → releases a sustained jamming state (like chaff/flare, but a device state). All effects are **server-authoritative**.

1. **Key**: add a separate `FIRE_ECM` key mapping, default key = LEFT_ALT (same as chaff key). Client sends `C2SFireEcm(vehicleId)`; server validates bone alive + cooldown → `RVP_EcmActiveManager.onFire(player, vehicle)`.
2. **Sound**: register `ECM_JAMMER` (asset `assets/ywzj_rvp/sounds/misc/ecm_jammer.ogg` already exists; add `sounds.json` entry + lang subtitle). Server broadcasts via `level.playSound(...)`.
3. **State**: `RVP_EcmActiveState` per active vehicle: `activeRemainTicks` / `cooldownRemainTicks` / `armPriorityRemainTicks`; persisted cross-world via a SavedData (required).
4. **Decoys**: on release spawn 6 `RVP_EcmDecoyEntity` (reuse the passive-ECM entity + extract shared decoy registry so `tryDivertSeeker` also considers active decoys). Lifetime configurable. NCTR: default pool + optional override.
5. **Missile jamming** (within `ammo_jam_radius`, EXCLUDING missiles fired by self or same faction — use `RVP_EcmIff.areVehiclesFriendly`):
   - ARH / AIR in relay phase (`!isAutonomousSeekerOn() && hasDesignation`): cut relay by making `rvp$hasActiveSeekerSupportForDesignatedTarget()` return false while `ecmActiveJamRemainTick>0`. ARH additionally sets `ecmActiveNoReacquire=true` so its own radar re-scan is short-circuited (cannot re-acquire → 200-tick lost-lock self-destruct); AIR does NOT set it (may coast + own IR seeker re-acquire).
   - SARH: `RVP_RuntimeSarhGuidanceSource` treats as no illumination (clearTarget) while jammed.
   - HITL_TV / HITL_CLOS_TV with `hitl_signal_source==RADIO`: force `hitlLinkBlocked` while jammed (reuse the existing block→severed state machine).
   - GPS missile/bomb: one-time random 2D offset of `targetPos` by `gps_offset_meters` (±20 m default).
   - All handled by adding fields `ecmActiveJamRemainTick`, `ecmActiveNoReacquire` to `RVP_BaseBullet` + short-circuits in `RVP_MissileEntity` / guidance sources (all RVP-owned, zero Mixin).
6. **Vehicle jamming** (within `vehicle_jam_radius`, hostile per `RVP_EcmIff`, same-faction excluded):
   - Force-break radar locks of **all** enemy vehicles regardless of what they locked: `radar.setLockedEntity(null)` for all RadarUnit/WeaponUnit + clear pending + set lock-prohibition via `RVP_ChaffJamState.setCooldown`. Model after `RVP_ChaffJamHelper.breakLock`.
   - RWR fake locks: **no entities**. Server sends `S2CEcmFakeLock(vehicleId, fakeRadarTypes[], durationRemainTick)` every ~10 ticks for `fake_lock_duration_ticks`; client handler writes `warningReceiver.targets.put(negativeFakeId, new WarnTarget(RADAR_LOCK, fakeRadarType, now))` with incrementing negative ids. Count = rand(min,max), each label from the config pool, not all identical.
7. **ARM interaction**:
   - `RVP_EcmActiveManager` keeps `ARM_PRIORITY` (vehicleId → untilTick, window = `arm_priority_ticks`).
   - In `RVP_RuntimeArmGuidanceSource.selectEmitter`, BEFORE preselection matching: if any emitter's vehicle is in the ARM_PRIORITY window → return it (highest priority). IFF: skip when ARM owner is same faction as ECM.
   - Memory guidance: while jammed, add per-tick random ±`arm_memory_jitter_meters` (7) jitter to the memory impact point (local variable only, don't pollute `lastGuidancePos`).
   - **ECM pseudo-pulse channel**: in `AntiRadiationSeekerHelper.collectPulseDescriptors`, after the vehicle loop, append pseudo-pulse descriptors for active-ECM vehicles within range+FOV (`emitterRadarIndex = -1` sentinel, jammer position, locked-grade strong PDW, `lockedEmission=true`). In `scanVisibleEmitters` allow `emitterRadarIndex < 0` (skip radarUnit resolution, build emitter with `radarUnit=null`). Add null guard in `getDefaultMemoryTick` (return default e.g. 20 when null). This makes a radar-less pure jammer lockable by ARM, ARM-only (RWR/radar don't pass through this pipeline).
8. **Config** `BoneEcmActiveConfig` (record + parse, `bone_modules.<bone>.ecm_active`), `BoneModuleType.ECM_ACTIVE`, `RVP_VehicleHitboxFactorManager.resolveEcmActiveDevices()`. All fields per the design doc §4 (decoy_count=6, dual radii, gps_offset_meters=20, fake_lock min/max/duration/sources, radar_unlock, arm_priority_ticks=100, arm_memory_jitter_meters=7; decoy_radius hardcoded).
9. **HUD**: extend `RVP_EcmHudOverlay` with an Active ECM status line (Jamming Xs / Charging Ys / Ready), synced via `S2CEcmActiveHudSync`.
10. Register new network packets (`C2SFireEcm`, `S2CEcmActiveHudSync`, `S2CEcmFakeLock`) in `RVP_Network`.

## WORK ORDER (follow this order; build + fix after each milestone if practical)

- P1: Config class, `ECM_ACTIVE` enum, resolveEcmActiveDevices, `C2SFireEcm` + network registration, `RVP_EcmActiveState`, `FIRE_ECM` key + client consumption, sound registration/playback, HUD line.
- P2: shared decoy registry extraction + spawn 6 decoys on release + lifetime + NCTR default/override.
- P3: missile jam fields + short-circuits (ARH/AIR relay, ARH no-reacquire, SARH illumination cut, HITL-radio block, GPS offset) + IFF pre-filter.
- P4: vehicle jam (hostile determination, radar unlock + prohibition, RWR no-entity fake locks).
- P5: ARM interaction (priority override, memory jitter, pseudo-pulse channel).
- P6: SavedData persistence, integration with passive ECM, docs update.

## HARD CONSTRAINTS (violations are rejects)

- **Zero new Mixins.** Do not modify `ywzj_vehicle`. All new/changed logic in `ywzj_rvp`.
- Blacklist classes (`RadarUnit`/`WeaponUnit`/`AbstractVehicle`/`WarningReceiver`): public methods only.
- Every new field/method needs a Chinese comment (units/defaults).
- Do not hardcode weapon IDs in entity/render code; no legacy JSON migrations.
- Follow the existing code conventions you find in `ywzj_rvp` (look at `BoneEcmPassiveConfig`, `RVP_EcmPassiveManager`, `RVP_DircmRuntimeManager`, `RVP_CountermeasureRuntimeManager`, `RVP_Keys`, `RVP_ClientEvents`, `RVP_Network`, `RVP_Sounds`, `AntiRadiationSeekerHelper`, `RVP_RuntimeArmGuidanceSource`).

## OUTPUT LANGUAGE (CRITICAL — overrides tokenizer)

- **Code comments MUST be in Chinese.** Every field / method / class you add must carry a clear Chinese comment (purpose, units, defaults), exactly as required by `AGENTS.md`. Do NOT write English comments even though this prompt and the design doc are in English.
- **Chat output MUST be in Chinese.** All messages, explanations, summaries and status updates you send to the user must be in Chinese.

## DELIVERABLES

1. All source changes implemented and compiling: `./gradlew build` → BUILD SUCCESSFUL.
2. Server smoke test: run `./gradlew runServer`, wait for `Done (Xs)!` line, then `stop` — no crash, no FATAL, no RuntimeDistCleaner errors. (Client-only UI logic is exempt from the runServer path, but server-side managers/packets must not break server startup.)
3. A brief English summary of what was implemented, the files touched, any deviations from the design doc (with reasons), and any config JSON example for a vehicle.
4. Update the design doc's status line if you complete a milestone.
