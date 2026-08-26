# RVP Active ECM (Active EW) — Research & Implementation Plan

> Status: **Research & design, not yet implemented**
> Date: 2026-08-26
> Basis: Code research of RVP's existing Countermeasures (chaff/flare), Passive ECM (ECM_PASSIVE), DIRCM, missile guidance system, RWR warning chain, and radar lock
> Related docs: `RVP被动电子战防御措施方案_20260825.md` (explicitly states "active EW will be a separate later plan"), `RVP_DIRCM定向红外对抗方案_20260823.md`, `RVP干扰物重构数据模型/`

---

## 1. Requirement Breakdown

| # | Requirement | Description |
| --- | --- | --- |
| R1 | **Active release, state-level** | Same family as chaff/flare: key press → enter a sustained state → cooldown |
| R2 | **Key = chaff key** | Reuse the `FIRE_CHAFF` (LEFT_ALT) trigger chain, same key triggers |
| R3 | **Sound ecm_jammer.ogg** | Asset already exists (`assets/ywzj_rvp/sounds/misc/ecm_jammer.ogg`), not yet registered |
| R4 | **Decoys ×6** | Instantly spawn 6 decoys with the same mechanism as Passive ECM (`RVP_EcmDecoyEntity`), configurable lifetime |
| R5 | **Missile jamming (in range)** | ARH / SARH / both radio HITL types / GPS missiles & bombs / mid-course AIR & ARH |
| R6 | **Vehicle jamming (in range)** | Break hostile vehicle radar locks + fake 5-10 RWR lock sources (configurable radartypes, default pool of 10, sources must not all be identical) |
| R7 | **Range separation** | Ammo-jam radius and vehicle-jam radius configured independently |
| R8 | **ARM attraction & degradation** (added by directive) | Within 5 s of release, becomes the **highest-priority target in anti-radiation missile (ARM) seekers' field of view** (**priority above any preselected target**), but the ECM interference makes ARM **memory impact points jitter randomly ±7 m each time** — "easy to lock on, hard to hit" |

**Missile-jamming detail (R5 expanded)**:

| Missile type | Jam effect | Detail |
| --- | --- | --- |
| ARH (mid-course, seeker not yet on) | Block radar relay guidance, **cannot re-establish track** | After relay data is lost, must not re-acquire via its own radar scan → dives per "lost lock **200 tick** self-destruct" logic (directive: 60→200) |
| AIR (mid-course, seeker not yet on) | Block radar relay guidance, **may coast and activate its own AIR seeker** | After relay is cut, coast + turn on own seeker to search; if it finds a target it may re-track |
| SARH | Block illumination-source guidance | Loses guidance when illumination is lost |
| HITL_TV / HITL_CLOS_TV (`hitl_signal_source=RADIO`) | Cut the radio link | Reuse existing `hitlLinkBlocked/hitlLinkSevered` state machine |
| GPS missile / bomb | Random offset of GPS impact point | Radius ±20 m (configurable), **one-time offset** (directive confirmed) |
| ARM (anti-radiation, R8) | See §7.5 | Attracted as highest priority + memory impact point jitter ±7 m |

> **Common constraint (added by directive)**: ECM **must not jam missiles fired by itself or by the same faction**. Judgment: skip when `shooterVehicle == ecmVehicle` or `RVP_EcmIff.areVehiclesFriendly(ecmVehicle, shooterVehicle)` (§7 pre-filter in the jam loop).

**Vehicle-jamming detail (R6 expanded)**:
- Faction judgment = GunnerBrain/CIWS semantics (same source as `RVP_EcmIff.areVehiclesFriendly`, `GunnerTargeting.isFriendlyAmmoOwner`, `GunnerBrain.isHostileTo`)
- Do not jam same-faction vehicles
- **Radar-unlock scope (directive clarified)**: **all** enemy vehicles' radar locks within jam range are force-broken **regardless of what they have locked** (whether they locked the ECM vehicle itself or some other target)
- RWR fake lock: inject 5-10 `RADAR_LOCK` warning sources into the victim's `WarningReceiver.targets`; radartype randomly picked from the config pool, each source independently random and not all identical; duration configurable; automatically triggers the vanilla "lock looping alarm sound"

---

## 2. Current-State Research Summary (Key Mechanisms & Reuse Points)

### 2.1 State-level Countermeasures (reference for R2/R3)
| Item | Location | Key point |
| --- | --- | --- |
| Key registration | `client/RVP_Keys.java:61` | `FIRE_CHAFF = key("fire_chaff", KEYSYM, GLFW_KEY_LEFT_ALT)` |
| Key consumption | `client/RVP_ClientEvents.java:129` | `while(FIRE_CHAFF.consumeClick()) ywzj_rvp$fireCountermeasure(CHAFF)` |
| C2S trigger | `countermeasure/network/C2SFireCountermeasure.java:41` | Validate player/vehicle → `RVP_CountermeasureRuntimeManager.onFire` |
| Server state machine | `countermeasure/server/RVP_CountermeasureRuntimeManager.java` | Per-vehicle UUID three state machines (remaining/firing/reloadProgress), `onTick()` advances each tick |
| Sound registration | `all/RVP_Sounds.java:25-27` + `assets/ywzj_rvp/sounds.json` | `register("countermeasure_flare"/"countermeasure_chaff")`, sound points to `ywzj_rvp:misc/*.ogg` |
| Sound playback | `RVP_CountermeasureRuntimeManager.spawnRound:244-257` | `vehicle.level().playSound(null, x,y,z, sound, SoundSource.NEUTRAL, 1.0F, 1.0F)` server broadcast |
| Bone-module damage | `vehicle/BoneModuleType.java:25-32` + `RVP_BoneModuleStateTable.isModuleActive` | Enums already include `ECM_PASSIVE/DIRCM/COUNTERMEASURE/JAMMER` etc.; **Active ECM needs a new `ECM_ACTIVE`** |

### 2.2 Passive ECM decoys (reference for R4)
| Item | Location | Key point |
| --- | --- | --- |
| Decoy entity | `entity/ecm/RVP_EcmDecoyEntity.java` | Invisible radar phantom (`shouldRender=false`), `ownerVehicleId`+`nctrName`+drift+lifetime destroy |
| Spawn logic | `ecm/RVP_EcmPassiveManager.java:232-285` `spawnDecoys/spawnOneDecoy` | Count by distance band, random radius, only in loaded chunks; lifetime=`max(activeDurationTicks, cooldownTicks)` |
| Dedup/registry | `RVP_EcmPassiveManager.DECOY_IDS` | vehicleId→decoy-entity-id list; `tryDivertSeeker` uses it for ARH/SARH seeker diversion |
| IFF | `ecm/RVP_EcmIff.java` | `areVehiclesFriendly/isHostileIllumination/isDecoyHostileTo`, includes gunner faction + placer chain + Team |
| Config | `vehicle/BoneEcmPassiveConfig.java` | `record` + `static parse(JsonElement)`, `ecm_passive` sub-object; includes `bands[]` (distance band → count/radius) |

> Active ECM decoys: **reuse the `RVP_EcmDecoyEntity` entity and spawn approach**; recommended to extract the `DECOY_IDS` registry and `spawnOneDecoy` into a shared tool (passive & active both register into the same per-vehicle decoy registry, so `tryDivertSeeker` also considers active decoys — consistent with "decoys like Passive ECM").

### 2.3 Missile guidance system (core of R5)
| Item | Location | Key point |
| --- | --- | --- |
| Guidance type enum | `guidance/RVP_EnumGuidanceType.java:6-24` | `NONE,IOG,MCLOS,SALH,SACLOS,LBR,LH,TV,HITL_TV,HITL_CLOS_TV,ATV,GPS,IR,AIR,ARH,SARH,ARM` |
| Phase model | `guidance/RVP_GuidancePhase.java` | Only `MAIN/TERMINAL` two phases (old `stages[]` doc is outdated) |
| Relay-phase check | `entity/projectile/RVP_MissileEntity.java:240-286` `tickActiveSeekerTargetManagement` | `isAutonomousSeekerOn()==false` (`activeRadarOn`) = relay phase; `distance to target ≤ activeRadarActivationRange OR !hasDesignation → setAutonomousSeekerOn(true)` |
| Relay data source | `RVP_MissileEntity.java:478-518` `rvp$hasActiveSeekerSupportForDesignatedTarget` | Vehicle root-radar lock / external relay-radar lock, **not an S2C packet** |
| ARH execution | `guidance/runtime/RVP_RuntimeActiveSeekerGuidance.java:14-92` | Relay phase uses `rvp$getActiveSeekerDesignatedTargetEntity()`; after seeker-on uses scanning (ARH scans radar targets / AIR scans IR) |
| SARH execution | `guidance/runtime/RVP_RuntimeSarhGuidanceSource.java:17-31` | Relies on `RVP_RadarRoleHelper.getEffectiveRfLockedEntity()` illumination source; no illumination → clearTarget |
| HITL radio link | `RVP_MissileEntity.java:569-618` `tickHitlRadioLink` | Only for `hitl_signal_source==RADIO`; line-of-sight/occlusion→`hitlLinkBlocked`, cumulative 40 tick→`hitlLinkSevered`+`hitlEnabled=false`+`clearTarget` |
| GPS impact point | `guidance/runtime/RVP_RuntimeGpsGuidanceSource.java:18-28` + `entity/projectile/RVP_BaseBullet.java:1243-1289` | Existing `gpsSpreadRadius` Gaussian dispersion (`gps_spread_radius`, default 0, BOMB-only); `applyGpsTargetDispersion`/`ensureGpsTargetOffset` |
| Jam impact points | `RVP_BaseBullet.java:377-431` | Existing `jammingExpireTick`, `dircmJammed/dircmJamRemainTick`, electro-optical jammer `jamming*` fields (`jammingStrength/jammingOffsetAngleDeg/...`) |
| Passive-spoof hook | `guidance/runtime/RVP_RuntimeSeekerSupport.java:66-73` | `validateEntity` calls `RVP_EcmPassiveManager.tryDivertSeeker()` (ARH/SARH only) |
| DIRCM jam paradigm | `dircm/RVP_DircmRuntimeManager.java:372-421,503-537` | Set `dircmJammed`+countdown field → guidance dropped + forced deflection; `tickJammedProjectile` decrements per tick, restores when zero |

> **Key conclusion**: relay phase = `isAutonomousSeekerOn()==false`. To block relay, simply make `rvp$hasActiveSeekerSupportForDesignatedTarget()` return false during the jam. The ARH/AIR difference is "whether it can re-scan-and-acquire after seeker-on":
> - AIR (IR): the AIR branch of `RVP_RuntimeActiveSeekerGuidance` scans IR, **naturally re-acquires after coast** → satisfies the requirement;
> - ARH (radar): scans radar after seeker-on → needs to **explicitly forbid re-scan** (add an `ecmActiveNoReacquire` flag short-circuiting the ARH scan branch). All within RVP classes: add fields + checks, **zero Mixin**.

### 2.4 RWR and radar lock (core of R6)
| Item | Location | Key point |
| --- | --- | --- |
| RWR lock-source table | vanilla `org.ywzj.vehicle.vehicle.passenger.WarningReceiver.targets` (`ConcurrentHashMap<Integer, WarnTarget>`) | `WarnTarget(warnType, info, receivedTime)`; `info`=radartype string |
| Warning window | `WarningReceiver.tick:66` | Entry cleared when `receivedTime+500<now` (500 ms window) → **fake locks need periodic re-injection to persist** |
| Alarm auto-trigger | `WarningReceiver.tick` | targets containing `RADAR_LOCK` → auto start/stop looping `RADAR_LOCK_WARN` (high-frequency lock alarm ✓) |
| radartype source | vanilla `RadarUnit.getRadarType()` (JSON `radar_type`) | Displayed verbatim, no mapping table → faking only needs to fake the string |
| Direct-injection paradigm | RVP `client/.../RVP_ClientWarnRelay.java:45-47` | Server S2C → client **directly `warningReceiver.targets.put(id, new WarnTarget(...))`**, bypassing the ±45° pitch clamp |
| Display | `client/gui/RVP_RadarOverlay.java:184-219` | Iterates targets → gets source entity to compute bearing line + `info`; **skipped if source entity not found** → fake sources need an entity/position |
| Radar unlock API | `radar/RVP_RadarRoleHelper.java:160` `clearAllRadarLocks`; vanilla `RadarUnit.setLockedEntity(null)` | Reference `RVP_ChaffJamHelper.tryJamLock/breakLock` (`:105-153`, includes clearing WeaponUnit lock + clearing pending + `RVP_ChaffJamState` lock-prohibition period) |

> **Fake-lock-source display problem**: `RVP_RadarOverlay` uses the source entity id to get a position for the bearing line; if the source entity cannot be fetched the entry is skipped. To show bearing lines for 5-10 fake locks you'd need entity anchors → one option: server spawns 5-10 **short-lived invisible radar-source entities** (modeled after `RVP_EcmDecoyEntity`, a new `RVP_EcmFakeLockEntity`, non-colliding, not participating in CIWS) around the victim, and S2C injects `WarnTarget`s referencing these entity ids + random radartypes. (Later superseded by the no-entity approach — see §8.3.)

### 2.5 Config / key / sound / state-machine paradigms (reference)
- Vehicle-level device config: `BoneEcmPassiveConfig/BoneDircmConfig/BoneJammerConfig` (`record` + `static parse`, `bone_modules.<bone>.<device>` sub-object); resolution chain `RVP_VehicleHitboxFactorManager.resolveEcmDevices()/resolveDircmDevices()/resolveJammerDevices()`; aliveness `RVP_BoneModuleStateTable.isModuleActive(vehicleId, boneName, type)`
- Key/consume/C2S: see §2.1 (`RVP_Keys` + `RVP_ClientEvents` + `RVP_Network` registration)
- Sound: `RVP_Sounds` DeferredRegister + `sounds.json` + server `level.playSound` broadcast
- State machine + persistence: `RVP_DircmRuntimeManager.tick()` (channel state tick countdown + `S2C` broadcast to passengers + TRACKING) + `RVP_DircmStateSavedData` (cross-world persistence)
- Lock-prohibition period: `RVP_ChaffJamState.setCooldown/isInCooldown` (by UUID + gameTime)

---

## 3. Overall Architecture

**Server-authoritative**: all jam judgments, decoy spawning, missile jamming, radar unlocking, and RWR faking happen on the server; the client only does key→C2S, playback/rendering, HUD.

```
Player presses ECM key (default LEFT_ALT, a separate key sharing the chaff key position)
  └─ RVP_ClientEvents (FIRE_ECM.consumeClick) → C2SFireEcm(vehicleId)
      └─ RVP_EcmActiveManager.onFire(server)
          ├─ validate bone alive + cooldown → play ecm_jammer sound (server broadcast)
          ├─ enter active state (activeRemainTicks = config)
          ├─ spawn 6 decoys (shared decoy registry)
          ├─ register ARM high-priority window (arm_priority_ticks)
          ├─ ammo jam loop (tick): hostile missiles in range (ARH/SARH/HITL-radio/GPS/AIR·ARH relay phase) → apply per-type
          │   └─ exclude missiles fired by self / same faction
          ├─ vehicle jam loop (tick): hostile vehicles in range (regardless of what they locked) → radar unlock + lock-prohibition
          └─ RWR faking (tick periodic re-inject): inject 5-10 RADAR_LOCK (fake radartype, no entity) into victims
State ends → cooldown → S2C HUD sync
```

**New/modified class list** (all RVP-owned classes, zero Mixin, no touching vanilla blacklist classes):

| Category | Class | Description |
| --- | --- | --- |
| Config | `vehicle/BoneEcmActiveConfig.java` (new) | record + parse; includes active state / decoys / dual radii / GPS offset / RWR faking / ARM, all configurable |
| Enum | `vehicle/BoneModuleType.java` (modify) | add `ECM_ACTIVE` |
| Resolution chain | `RVP_VehicleHitboxFactorManager` (modify) | add `resolveEcmActiveDevices()` |
| Key | `client/RVP_Keys.java` (modify) | add a separate `FIRE_ECM` key, default key value same as `FIRE_CHAFF` (LEFT_ALT) |
| Client | `client/RVP_ClientEvents.java` (modify) | `FIRE_ECM.consumeClick` branch → C2SFireEcm |
| Network | `network/C2SFireEcm.java` (new), `RVP_Network` (modify), `S2CEcmActiveHudSync.java` (new), `S2CEcmFakeLock.java` (new) | |
| Server state | `ecm/RVP_EcmActiveState.java` (new) | activeRemainTicks / cooldownRemainTicks / armPriorityRemainTicks |
| Server manager | `ecm/RVP_EcmActiveManager.java` (new) | core: onFire / tick jam loops / decoys / RWR faking / ARM priority registry / HUD sync |
| Decoy sharing | `ecm/RVP_EcmPassiveManager.java` (modify, minimal) | extract `spawnOneDecoy`/`DECOY_IDS` for active reuse (or a new `RVP_EcmDecoyRegistry`) |
| Missile jam flags | `entity/projectile/RVP_BaseBullet.java` (modify) | add `ecmActiveJamRemainTick`, `ecmActiveNoReacquire`, `ecmActiveMemoryJitterPending` fields |
| Missile judgment | `entity/projectile/RVP_MissileEntity.java` (modify) | three short-circuits: relay support / radio link / ARH re-scan |
| Guidance sources | `guidance/runtime/RVP_RuntimeActiveSeekerGuidance.java` (modify), `RVP_RuntimeSarhGuidanceSource.java` (modify) | ARH no-reacquire, SARH illumination cut |
| ARM guidance source | `guidance/runtime/RVP_RuntimeArmGuidanceSource.java` (modify) | ECM priority override + memory impact ±7 m jitter + memory null fallback |
| ARM scanner | `weapon/AntiRadiationSeekerHelper.java` (modify) | pseudo-pulse injection + `radarIndex=-1` sentinel passthrough + `getDefaultMemoryTick` null guard |
| Sound | `all/RVP_Sounds.java` (modify), `assets/ywzj_rvp/sounds.json` (modify), lang (modify) | register `ECM_JAMMER` + add `ecm_jammer` entry |
| HUD | `client/gui/RVP_EcmHudOverlay.java` (modify) | add an Active ECM status line |
| Persistence | `ecm/RVP_EcmActiveStateSavedData.java` (new) | **required** (directive): cross-world persistence of cooldown/remaining |

---

## 4. Data Model (`BoneEcmActiveConfig`)

New block inside the vehicle JSON top-level `bone_modules` (modeled after `ecm_passive`):

```json
"bone_modules": {
  "ecm_jammer": {
    "modules": ["ecm_active"],
    "ecm_active": {
      "active_duration_ticks": 200,
      "cooldown_ticks": 600,
      "decoy_count": 6,
      "decoy_lifetime_ticks": 200,
      "ammo_jam_radius": 300,
      "vehicle_jam_radius": 400,
      "gps_offset_meters": 20,
      "fake_lock_min": 5,
      "fake_lock_max": 10,
      "fake_lock_duration_ticks": 120,
      "fake_lock_sources": ["S400","J16","F18","J20","SLM","F15","S57","S35","F22","ITO"],
      "radar_unlock": true,
      "arm_priority_ticks": 100,
      "arm_memory_jitter_meters": 7
    }
  }
}
```

| Field | Default | Description |
| --- | --- | --- |
| `active_duration_ticks` | 200 | Active ECM duration in ticks after release (state-level); jams continuously during this period |
| `cooldown_ticks` | 600 | Cooldown in ticks |
| `decoy_count` | 6 | Number of decoys spawned immediately on release |
| `decoy_lifetime_ticks` | 200 | Decoy lifetime in ticks (independent of Passive ECM's `max(active,cooldown)`) |
| `ammo_jam_radius` | 300 | **Ammo jam radius** (separate from vehicle radius, R7) |
| `vehicle_jam_radius` | 400 | **Vehicle jam radius** |
| `gps_offset_meters` | 20 | Random offset radius for GPS missile/bomb impact (±meters), one-time (directive confirmed) |
| `fake_lock_min/max` | 5/10 | RWR fake-lock-source count range |
| `fake_lock_duration_ticks` | 120 | Fake-lock duration in ticks (periodic re-inject during this period, because RWR entries expire after 500 ms) |
| `fake_lock_sources` | default pool | radartype label pool; each source independently random and **not all identical** (count>1 guarantees at least two different); **configurable — if not set, use the default pool** (directive confirmed, same pattern as Passive NCTR) |
| `radar_unlock` | true | Whether to break the jammed vehicles' radar locks |
| `arm_priority_ticks` | 100 | ARM high-priority window (5 s = 100 ticks) |
| `arm_memory_jitter_meters` | 7 | ARM memory impact-point random jitter radius (±7 m) |

> **decoy_radius (decoy scatter range)**: directive says "just hardcode it" — no config field; use a reasonable fixed value (e.g., vehicle radius × random factor, on the order of the passive `bands[].radius`).
> **Decoy NCTR**: directive confirmed — same as Passive ECM: **has a default pool, and can be overridden in the Active config** (if not configured, falls back to `BoneEcmPassiveConfig.nctrNames` or a built-in default).

**Parsing**: `BoneEcmActiveConfig.parse(JsonElement)` (GsonHelper, with clamping); `BoneModuleType` adds `ECM_ACTIVE`; `resolveEcmActiveDevices()` returns `Map<boneName, BoneEcmActiveConfig>`; aliveness via `RVP_BoneModuleStateTable.isModuleActive`.

---

## 5. Trigger Chain & Sound

1. Add a separate `RVP_Keys.FIRE_ECM` key (directive confirmed), **default key value same as `FIRE_CHAFF` (LEFT_ALT)**: in `RVP_ClientEvents.onClientTick` add `while(RVP_Keys.FIRE_ECM.consumeClick()) ywzj_rvp$fireEcm();`; if the vehicle has a **live** ECM_ACTIVE bone → `sendToServer(new C2SFireEcm(vehicleId))`.
   - No interference with chaff: both keys use the same physical key but each consumes its own click and sends its own C2S packet.
2. `C2SFireEcm.handle`: server validates player/vehicle/bone-alive/cooldown → `RVP_EcmActiveManager.onFire(player, vehicle)`.
3. `onFire`: set `activeRemainTicks=active_duration_ticks`, `armPriorityRemainTicks=arm_priority_ticks`, `cooldownRemainTicks=cooldown_ticks` (or enter cooldown only after release ends), play `level().playSound(null, ..., ECM_JAMMER, SoundSource.NEUTRAL, 1.0F, 1.0F)`, spawn 6 decoys.
4. Sound registration: `RVP_Sounds` adds `ECM_JAMMER = register("ecm_jammer")`; `sounds.json` adds `"ecm_jammer": {"subtitle":"subtitles.ywzj_rvp.ecm_jammer","sounds":["ywzj_rvp:misc/ecm_jammer"]}` (the `.ogg` already exists); lang adds the bilingual subtitle.

---

## 6. Decoy Spawning (R4)

- Reuse the `RVP_EcmDecoyEntity` entity (`initDecoy(ownerVehicleId, nctrName, lifetimeTicks, driftVelocity)`).
- Recommended: extract `RVP_EcmPassiveManager`'s `spawnOneDecoy` and `DECOY_IDS` registration into a shared tool (a new `RVP_EcmDecoyRegistry`, or the Active manager calls the same spawn code and registers into the same `DECOY_IDS`), so `tryDivertSeeker`'s ARH/SARH diversion also considers Active decoys ("decoys like Passive ECM").
- Active release: spawn `decoy_count=6` at once, within a random radius, only in loaded chunks; lifetime=`decoy_lifetime_ticks`.
- **Scatter range `decoy_radius`: hardcoded** (directive confirmed, no config field), fixed to a reasonable value.
- **Decoy NCTR (directive confirmed)**: same as Passive ECM — has a default pool (fallback to `BoneEcmPassiveConfig.nctrNames` or built-in default), and may be provided separately in the Active config (optional field `fake_decoy_nctr`).

---

## 7. Missile-Jam Matrix (R5) — Core Design

Common premise: `RVP_EcmActiveManager` every `TICK_INTERVAL` (e.g., 4 ticks) iterates the ECM vehicles that are currently active this tick, and for each `RVP_BaseBullet` missile within `ammo_jam_radius` determines `guidanceType` and phase, then applies the effects in the table below.

> **Pre-filter (added by directive)**: skip missiles where `shooterVehicle == ecmVehicle` or `RVP_EcmIff.areVehiclesFriendly(ecmVehicle, shooterVehicle)` (**do not jam self / same faction**).

| Missile | Judgment | Jam injection | Expected behavior |
| --- | --- | --- | --- |
| ARH (relay phase) | `guidanceType==ARH && !isAutonomousSeekerOn() && hasDesignation` | set `ecmActiveJamRemainTick>0` + `ecmActiveNoReacquire=true`; `rvp$hasActiveSeekerSupportForDesignatedTarget()` returns false during jam; in `RVP_RuntimeActiveSeekerGuidance` the ARH scan branch short-circuits when `ecmActiveNoReacquire` (clearTarget) | relay cut → lost-lock accumulates → **200 tick** self-destruct (directive: 60→200), **cannot re-acquire** |
| AIR (relay phase) | `guidanceType==AIR && !isAutonomousSeekerOn() && hasDesignation` | only `ecmActiveJamRemainTick>0` (cut relay), **no NoReacquire** | relay cut → existing logic auto `setAutonomousSeekerOn(true)` → coast + own AIR(IR) search; may re-track if found |
| SARH | `guidanceType==SARH` | during jam, `RVP_RuntimeSarhGuidanceSource` treats as no illumination (clearTarget) | fails on illumination loss |
| HITL_TV / HITL_CLOS_TV (radio) | `hitl_signal_source==RADIO` | during jam, force `hitlLinkBlocked=true` (short-circuit at the `tickHitlRadioLink` result) | cumulative 40 tick → `hitlLinkSevered` + out-of-control coast |
| GPS missile/bomb | `guidanceType==GPS` | on first jam apply a one-time random 2D offset to `targetPos` (radius `gps_offset_meters`), reuse/extend `ensureGpsTargetOffset` | impact offset ±20 m, one-time |
| ARM | see §7.5 | priority override + memory jitter | attracted but inaccurate |

**Minimal-change implementation** (all within RVP classes):
1. Add fields to `RVP_BaseBullet`:
   - `ecmActiveJamRemainTick` (int, >0 means currently jammed by Active ECM)
   - `ecmActiveNoReacquire` (boolean, ARH-specific)
2. At the start of `RVP_MissileEntity.rvp$hasActiveSeekerSupportForDesignatedTarget()` add `if (ecmActiveJamRemainTick>0) return false;` (cuts ARH/AIR relay)
3. In `RVP_RuntimeActiveSeekerGuidance.evaluate()`: ARH branch `if (ecmActiveNoReacquire) { clearTarget(); }` (short-circuit before scan-on); AIR branch unchanged
4. In `RVP_MissileEntity.tickHitlRadioLink()`: treat as blocked when `ecmActiveJamRemainTick>0`
5. At the start of `RVP_RuntimeSarhGuidanceSource.evaluate()` add `if (ecmActiveJamRemainTick>0) { clearTarget(); failed; }`
6. GPS: on first jam apply a one-time offset to `targetPos` (`Random` 2D, config radius)
7. When the jam expires (`ecmActiveJamRemainTick--` reaches zero): the no-reacquire ARH is already dead; the rest may recover (if needed)

> Recommended jam duration ≥ missile self-destruct window (**200 ticks**); default `ecmActiveJamRemainTick` uses a one-time fixed value (e.g., 220), no need to renew with the active state — once a missile loses relay it is effectively dead.

### 7.5 ARM (Anti-Radiation Missile) Interaction (R8)

**Goal**: within 5 s of ECM release, the ECM vehicle becomes the **highest-priority target in the ARM's field of view** (priority > any preselected target); but the ECM interference makes ARM **memory impact points jitter randomly ±7 m each time** — "easy to lock on, hard to hit".

**Current mechanism** (`RVP_RuntimeArmGuidanceSource`):
- `copyPreselectedEmitter` (tick 0): copies the preselected radiation source from `RVP_WeaponLockStateTable` → sets `targetPos` + `rememberGuidancePos`.
- `selectEmitter` (`:104-113`): **matches preselection first** (vehicleId/radarIndex), then picks best by `AntiRadiationSeekerHelper.score` (distance/angle/PDW/`lockedBonus`).
- Memory guidance (`:76-81`): while `antiRadiationMemoryLeftTick>0`, uses `lastGuidancePos` as `targetPos` each tick.

**Implementation design**:
1. **Priority override**: `RVP_EcmActiveManager` maintains `ARM_PRIORITY = Map<vehicleId, untilTick>` (registered with `arm_priority_ticks` on release). Add an ECM override block at the start of `selectEmitter`:
   - Iterate `emitters`; if any emitter's `vehicle` is inside the `ARM_PRIORITY` window → **return that emitter directly** (bypassing preselection matching and score ranking).
   - Multiple ECM vehicles simultaneously inside the priority window → pick the best-scoring one.
2. **Precondition (key constraint)**: `collectPulseDescriptors` only collects `radarUnit.isOn()` radar radiation sources. For the ECM vehicle to enter the ARM field of view, **it must have a powered-on radar itself** (otherwise ARM cannot "see" it at all).
   - Recommended design: when registering in the manager during ECM release, also check whether the vehicle has a powered-on radar; if not, the priority override naturally fails (the emitter is not found). Document this constraint; if the product requires "a pure jammer with no radar can still be locked by ARM", an ECM pseudo-pulse channel must be added separately in `collectPulseDescriptors` (listed in §13 note). (Resolved by §7.6.)
3. **Memory impact-point jitter**: during the jam (`arm_memory_jitter`), in the memory-guidance branch:
   ```java
   Vec3 jitter = new Vec3((rnd*2-1), 0, (rnd*2-1)).scale(arm_memory_jitter_meters);
   Vec3 memory = projectile.getLastGuidancePos().add(jitter);
   projectile.setTargetPos(memory);
   ```
   Re-randomize every tick ("each memory impact point produces a random offset"); do not pollute `lastGuidancePos` itself (use a local variable only) to avoid cumulative drift.
4. **IFF filter**: when the ARM's owner (`getShooterVehicle`) and the ECM are same-faction, do not apply the priority override (consistent with the §7 pre-filter).

### 7.6 ECM Pseudo-Pulse Channel (ARM-only; solves "a pure jammer can still be locked by ARM")

**Origin**: §7.5 note — `collectPulseDescriptors` only collects real `radarUnit.isOn()` radar sources, so a radar-less jammer cannot enter the ARM field of view. Research conclusion: **inject a "pseudo-pulse" into the ARM scan pipeline, and it is naturally only visible to ARM**.

#### 7.6.1 Why it is naturally ARM-only (mechanism confirmed)

The ARM target's **only** signal source is `AntiRadiationSeekerHelper.collectPulseDescriptors` (an O(entities) scan of vehicle radars), with this call chain:

```
collectPulseDescriptors
  └─ scanVisibleEmitters
       ├─ RVP_RuntimeArmGuidanceSource.evaluate        (in-flight ARM guidance)
       └─ GunnerWeaponSuitability.findBestTargetEmitter (gunner AI decides whether ARM can hit a target)
```

- **RWR warnings** go through `WeaponUnit.tick` (RADAR_SEARCH/RADAR_LOCK) + `RVP_WarnRelayService`, and **do not pass through** `collectPulseDescriptors`;
- **Normal radar detection** goes through `RadarUnit.detectedObjects`, also not passing through;
- Therefore **a pseudo-pulse appended in `collectPulseDescriptors` is never seen by RWR or by any non-ARM system** — satisfying "recognizable only by anti-radiation missiles".

#### 7.6.2 Injection design

**Injection point**: after the vehicle iteration loop in `collectPulseDescriptors` finishes, append a pseudo-pulse segment for "currently-active Active-ECM vehicles":

```java
// pseudocode: appended after the `out` loop
for (ActiveEcmInfo ecm : RVP_EcmActiveManager.getActiveVehiclesIn(level, seekerPos, seekRange)) {
    Vec3 jamPos = ecm.vehicle().position();          // jammer position is the radiation source
    double dist = jamPos.distanceTo(seekerPos);
    if (dist > seekRange) continue;                   // reuse the seeker scan range
    double angle = Math.toDegrees(VectorUtil.angleBetween(seekerLook, jamPos.subtract(seekerPos)));
    if (angle > seekerFov) continue;                  // reuse the field of view
    out.add(new RVP_RadarPulseDescriptor(
        tickCount,                       // timeOfArrivalTick
        4.0,                             // pulseWidthMicroseconds (lock-grade wide pulse)
        angle,                           // angleOfArrivalDegrees
        9000.0 + floorMod(ecm.vehicleId(), 1000),  // carrierFrequencyMhz (pseudo/jam band, distinct from real 8-12 GHz)
        4.0 * rcsFactor / (dist*dist),   // amplitude (strong radiation, decoy-grade)
        ecm.vehicleId(),                 // emitterVehicleId
        -1,                              // emitterRadarIndex = -1 (pseudo-pulse sentinel)
        jamPos,                          // emitterPosition
        true                             // lockedEmission (always treated as a "locking emission": high attraction + no scan-phase memory needed)
    ));
}
```

**Sentinel index `emitterRadarIndex=-1`**: real radar indices are ≥0. The current `scanVisibleEmitters` code would skip this pulse because `radarUnit == null` → needs two changes (below).

**Visibility window**: the pseudo-pulse is injected only within the ECM `active_duration_ticks` (the ECM jammer is radiating). `arm_priority_ticks` (5 s) is a sub-window that only controls the "highest-priority" override; the pseudo-pulse exists throughout the whole active period.

**PDW parameter design**: `lockedEmission=true` (wide 4.0 µs pulse, amplitude ×2, gets `lockedBonus` in score), high amplitude → naturally decoy-grade strong radiation; also no reliance on `pulseTickMap` scan phase (always visible).

#### 7.6.3 Points to modify

| Location | Change |
| --- | --- |
| `weapon/AntiRadiationSeekerHelper.collectPulseDescriptors` | append the active-ECM pseudo-pulse segment at the end (§7.6.2) |
| `weapon/AntiRadiationSeekerHelper.scanVisibleEmitters` | allow `emitterRadarIndex < 0`: only resolve `level.getEntity(emitterVehicleId) instanceof AbstractVehicle`, **skip radarUnit resolution**, construct an emitter with `radarUnit=null` |
| `weapon/AntiRadiationSeekerHelper.getDefaultMemoryTick` | **null guard**: return a default memory tick (e.g., 20) when `radarUnit == null` |
| `guidance/runtime/RVP_RuntimeArmGuidanceSource` | null fallback where it calls `getDefaultMemoryTick(best.radarUnit())` (or rely on the helper's internal guard); add the ECM priority override in `selectEmitter` (already planned in §7.5) |
| `ecm/RVP_EcmActiveManager` (new) | provide `getActiveVehiclesIn(level, pos, range)` (set of vehicles currently in active state and hostile) |

> `RVP_RadarPulseDescriptor` is a record with all-public fields; construct it directly, no need to change the record itself (use `radarIndex=-1` to identify the pseudo-pulse, no new field, minimal change).

#### 7.6.4 Side effects and boundaries

1. **Gunner AI ARM also "sees" the pseudo-pulse**: `GunnerWeaponSuitability.findBestTargetEmitter` filters `emitter.vehicleId() == targetVehicle.getId()` → when the gunner judges "can ARM hit a target", an active ECM vehicle is treated as a lockable target and can be written into preselection (`setArmPreselected(root, ecmId, -1, pos)`). This is consistent with "highest-priority target in ARM field of view".
2. **No-IFF current behavior**: the ARM path (`selectEmitter`) already has no friendly/hostile filter for real radar sources (it locks whoever radiates) — the pseudo-pulse follows the same behavior. A friendly ARM locking a friendly ECM is an extension of the current radar logic, consistent. If a separate IFF is desired for the pseudo-pulse, judge the seeker owner vs. ECM faction via `RVP_EcmIff` in the injection loop; listed as a note (default: not done, keep consistent).
3. **pulseTickMap unaffected**: the pseudo-pulse is directly visible and does not write `pulseTickMap` (avoids polluting the real-radar memory-phase table).
4. **Performance**: active ECM vehicle count is on the order of 1-3, an O(1) append, no pressure.
5. **`findBestRadiationSource`**: no longer has any caller (dead code from the old ARM path); its internal `AntiRadiationTarget(...).getDefaultMemoryTick(best.radarUnit())` needs a null guard too if not deleted (recommended: clean it up while here).
6. **Precondition lifted**: with the pseudo-pulse channel, the §7.5 note "ECM vehicle must have a powered-on radar" constraint **is no longer needed** — a pure jammer (no radar / radar off) can also be locked by ARM.

---

## 8. Vehicle Jamming (R6)

### 8.1 Hostile determination
Reuse `RVP_EcmIff` (same source as GunnerBrain/CIWS):
- `!RVP_EcmIff.isNeutralRadarVehicle(target)` (exclude owner-less neutral vehicles)
- `!RVP_EcmIff.areVehiclesFriendly(ecmVehicle, targetVehicle)` → hostile
(Equivalent to the GunnerTargeting/GunnerBrain faction+Team+placer-chain semantics)

### 8.2 Radar unlock + lock prohibition
Model after `RVP_ChaffJamHelper.breakLock` (`:153`): for all `RadarUnit`/`WeaponUnit` of the target vehicle:
- `radar.setLockedEntity(null)` (vanilla public ✓; blacklist classes only via public methods)
- clear `WeaponUnit.setLockedEntity(null)` + pending
- `RVP_ChaffJamState.setCooldown(vehicleUuid, gameTime, prohibitionTicks)` to prevent an immediate re-lock (reuse the existing prohibition period; `applyRequestedLock`/`ClientRadarActionMixin` automatically reject)
- Execute periodically (while the vehicle is inside `vehicle_jam_radius` and ECM is active, re-check every N ticks because the target may re-lock)

### 8.3 RWR fake locks (directive: **no-entity pure-interference approach**)

Directive: fake sources are **non-interactive invisible, and must NOT spawn entities** — "direct pure interference, making the victim's RWR look broken".

**Problem with the original entity approach**: `RVP_RadarOverlay` uses the source entity id to get a position for the bearing line; if the source entity cannot be fetched the entry is skipped. Drawing bearing lines would need entity anchors. Per the directive, use the **no-entity approach** — drop bearing lines, only fake the RWR "lock warning + lock-source label list":

1. Server `RVP_EcmActiveManager` every 10 ticks, for hostile vehicles in range:
   - random `n = rand(fake_lock_min, fake_lock_max)` lock sources
   - pick labels randomly from the `fake_lock_sources` pool, **guaranteeing ≥2 different signals when count>1**
   - **spawn no entities**; directly send `S2CEcmFakeLock(vehicleId, fakeRadarTypes[])` to the target vehicle's passengers
2. Client handler (new `RVP_ClientEcmFakeLockHandler`):
   - for each fake source: `warningReceiver.targets.put(fakeId, new WarnTarget(RADAR_LOCK, fakeRadarType, now))`, where `fakeId` uses an **incrementing negative number** (can never be a real entity id) → `RVP_RadarOverlay` cannot find the source entity → that entry **only triggers the alarm and the centered red "being locked" text, no bearing line**, matching "RWR looks broken" (alarm keeps sounding + "locked" text, but the sources are garbled/invisible)
   - vanilla `WarningReceiver.tick` auto-plays the looping lock alarm (high-frequency ✓)
3. Lasts `fake_lock_duration_ticks`; the client handler stops re-injecting per remaining time; since `targets` auto-expire after 500 ms, it naturally fades once stopped.

> If the desire is for the RWR to "show multiple lock sources from different directions", an entity approach can be introduced later (pending); per the current directive, implement the no-entity pure interference first.

---

## 9. Range Separation (R7)

- `ammo_jam_radius`: missile-jamming (§7) judgment range, centered on the ECM vehicle position
- `vehicle_jam_radius`: vehicle-jamming (§8) judgment range
- The two radii are configured independently and take effect independently; the decoy scatter range `decoy_radius` is **hardcoded** (directive confirmed)

---

## 10. Network Packets & HUD

| Packet | Direction | Content |
| --- | --- | --- |
| `C2SFireEcm` | C2S | `vehicleId` |
| `S2CEcmActiveHudSync` | S2C | `vehicleEntityId` + `activeRemainTick/cooldownRemainTick/maxActiveTick/maxCooldownTick` |
| `S2CEcmFakeLock` | S2C | `vehicleId` + `fakeRadarTypes[]` + `durationRemainTick` (no-entity fake locks) |

HUD: extend `RVP_EcmHudOverlay` with an Active ECM status line (`ECM(Active): Jamming Xs / Charging Ys / Ready`), following the `RVP_EcmHudState` pattern (can extend `Snapshot`).

---

## 11. Implementation Plan (Milestones)

| Phase | Content |
| --- | --- |
| P1 Skeleton | `BoneEcmActiveConfig` + `BoneModuleType.ECM_ACTIVE` + `resolveEcmActiveDevices` + `C2SFireEcm` + `RVP_EcmActiveState` + separate `FIRE_ECM` key + sound registration/playback + HUD status line |
| P2 Decoys | extract shared decoy registry + spawn 6 decoys on release + lifetime config + NCTR default pool/configurable |
| P3 Missile jam | bullet fields + relay short-circuit (ARH/AIR) + SARH illumination cut + HITL-radio link cut + GPS offset + IFF filter (no self/same-faction) |
| P4 Vehicle jam | hostile determination + radar unlock (regardless of what is locked) + lock prohibition + **RWR no-entity fake locks** |
| P5 ARM interaction | `ARM_PRIORITY` registry + `selectEmitter` priority override + memory impact ±7 m jitter + **ECM pseudo-pulse channel** (`collectPulseDescriptors` injection + `scanVisibleEmitters` sentinel passthrough + memory null guard) |
| P6 Wrap-up | dual-radius integration, coexistence verification with Passive ECM, **SavedData persistence**, config documentation |

---

## 12. Risks & Constraints

1. **Mixin discipline**: `RadarUnit/WeaponUnit/AbstractVehicle/WarningReceiver` are vanilla blacklist classes — **zero new Mixins**. All touches use only public methods (`setLockedEntity/getLockedEntity/getDetectedEntities/targets.put`) or add fields to RVP-owned classes.
2. **Public code must avoid `@OnlyIn`**: the RWR client injection reuses the existing `RVP_ClientWarnRelay` (DistExecutor split); new client logic goes in the `client` package and is bridged through `RVP_ClientActionsAccess`.
3. **Performance**: missile/vehicle jam loops run only during the active state, throttled by `TICK_INTERVAL=4`; the number of in-range entities is bounded.
4. **Passive ECM coexistence**: Active decoys and Passive decoys may be on screen together; the shared registry must handle dedup and lifetime management to avoid duplicate computation by CIWS/seekers.
5. **RWR fake persistence**: the 500 ms entry window requires a re-inject loop; the no-entity approach uses incrementing negative ids that don't conflict with real source ids; note that `RVP_RadarOverlay` shows only the alarm (no bearing line) for entries whose source entity can't be found — matching "RWR looks broken".
6. **GPS offset must be one-time**: avoid per-tick cumulative jitter; `ensureGpsTargetOffset` is already a one-time pattern; the Active jam reuses it with a one-time random offset.
7. **ARM pseudo-pulse channel**: the injection point is only in the ARM-only pipeline `collectPulseDescriptors`, naturally recognized only by ARM (RWR/radar detection do not pass through it). `scanVisibleEmitters` needs to allow the `emitterRadarIndex=-1` sentinel + `getDefaultMemoryTick` null guard; gunner AI's ARM judgment automatically "sees" active ECM (as expected).
8. **No self/same-faction jamming**: all missile/vehicle jam loops pre-filter with `RVP_EcmIff.areVehiclesFriendly` to avoid friendly-fire false triggers.

---

## 13. Directives Confirmed & Added (2026-08-26)

| # | Original open question | Directive conclusion |
| --- | --- | --- |
| 1 | Key | **Add a separate `FIRE_ECM` key, default key value = chaff key (LEFT_ALT)** |
| 2 | Decoy NCTR | Same as Passive ECM: **has a default pool, can be configured** (falls back to default if unset) |
| 3 | GPS offset timing | **One-time** |
| 4 | ARH self-destruct duration | **60→200 ticks**; addition: **ECM does not jam missiles fired by itself or same faction** |
| 5 | Unlock scope | **All enemy vehicles' radar locks within jam range are force-broken regardless of what they locked** |
| 6 | RWR fake source | **Non-interactive invisible; spawn no entities** — pure interference making the victim's RWR look broken (§8.3 changed to the no-entity approach) |
| 7 | decoy_radius | **Hardcoded**, no config |
| 8 | SavedData persistence | **Required** |
| 9 | (new) ARM interaction | Within **5 s of release, becomes the highest-priority ARM target** (priority above any preselection); but **ARM memory impact points jitter randomly ±7 m** → "easy to lock on, hard to hit" |

**New config fields**: `arm_priority_ticks` (default 100 = 5 s), `arm_memory_jitter_meters` (default 7). Already merged into the §4 data model.

**Note (resolved via the pseudo-pulse channel)**: the original constraint "the ECM vehicle must have a powered-on radar to be treated as an ARM radiation source" is lifted — §7.6's **ECM pseudo-pulse channel** injects a pseudo-pulse inside the ARM-only scan pipeline (`collectPulseDescriptors`), so a radar-less pure jammer can also be locked by ARM, and the channel is naturally recognized only by ARM (RWR/radar detection do not pass through that pipeline).
