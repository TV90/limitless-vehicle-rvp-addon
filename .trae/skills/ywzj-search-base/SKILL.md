---
name: "ywzj-search-base"
description: "Searches the base mod at E:\\ywzj\\ywzj_vehicle when code is not found in this repo. Invoke when repo-wide search yields no matches or user says the feature exists in ywzj_vehicle."
---

# YWZJ Search Base

## Purpose

When investigating a feature (e.g. thermal imaging, keybinds, overlays, shaders) and the current repo does not contain the implementation, continue the investigation in the base mod project located at:

- `E:\ywzj\ywzj_vehicle`

This skill standardizes the fallback workflow and the evidence you should collect.

## When To Invoke

Invoke this skill when ANY of these are true:

1. You already searched this repo (java/resources) and found no relevant matches.
2. The user explicitly says the feature lives in `E:\ywzj\ywzj_vehicle`.
3. You only find references via dependency jars (e.g. `libs/ywzj_vehicle-*-all.jar`) but not in source form.

## Workflow

### 1) Confirm the base path exists

- List `E:\ywzj\ywzj_vehicle` and locate typical Forge mod structure:
  - `src/main/java`
  - `src/main/resources`
  - `build.gradle`, `gradle.properties`

If the directory is missing, fall back to dependency jar inspection (see section 4).

### 2) Search the base mod source (fast path)

Search keywords in BOTH java and resources:

- Thermal imaging keywords:
  - `thermal|Thermal`
  - `toggle_thermal|thermal_imaging`
  - `ThermalHandler|ModShaders|PostChain|PostPass`
  - `shaders/post/thermal.json`
- Keybind keywords:
  - `AllKeys|KeyMapping|RegisterKeyMappingsEvent`
  - `GLFW_KEY_4|KEY_4`

Preferred order:

1. Find key registration (which key toggles thermal)
2. Find the handler (what flag/state is toggled)
3. Find shader/post chain hookup (how rendering is altered)

### 3) Extract a minimal call chain (for documentation)

For “key -> effect” features, document:

- Key mapping definition and default key
- Where the pressed state is consumed (client tick / input handler)
- Where the thermal state is stored (static flag, capability, player state, etc.)
- Where rendering is modified (overlay event, post chain, render target, shader uniforms)
- Any config toggles or per-vehicle constraints

Keep file references as clickable links when reporting.

### 4) If only jars are available (no base source)

Inspect the dependency jar:

- List entries under:
  - `assets/ywzj_vehicle/shaders/**`
  - `org/ywzj/vehicle/client/shader/**`
  - `org/ywzj/vehicle/all/AllKeys.class`

Then, locate:

- `ThermalHandler` (thermal post effect logic)
- `ModShaders` / `PostChainMixin` (shader registration / post chain injection)
- Language keys such as `key.ywzj_vehicle.toggle_thermal_imaging.desc`

Do NOT claim exact key defaults unless you find the keycode in code or key registration data.

## Constraints

- Do not modify `E:\ywzj\ywzj_vehicle` unless the user explicitly asks to change the base mod.
- Prefer read-only investigation and reporting.
