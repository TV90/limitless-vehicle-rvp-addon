package org.ywzj.rvp.guidance;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.ywzj.rvp.guidance.activation.RVP_GuidanceActivationContext;
import org.ywzj.rvp.guidance.activation.RVP_GuidanceActivationEvaluator;
import org.ywzj.rvp.weapon.data.RVP_GuidanceActivationData;
import org.ywzj.rvp.weapon.data.RVP_GuidanceData;
import org.ywzj.rvp.guidance.RVP_EnumPhaseResolvePolicy;
import org.ywzj.rvp.weapon.data.RVP_GuidanceStageData;
import org.ywzj.vehicle.custom.serialize.GsonUtil;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Shared helpers for guidance JSON white-box tests (no Minecraft bootstrap).
 */
public final class RVP_GuidanceWhiteboxSupport {

    private RVP_GuidanceWhiteboxSupport() {}

    public static List<RVP_GuidanceStageData> loadStages(Path weaponJson) throws IOException {
        String raw = Files.readString(weaponJson, StandardCharsets.UTF_8);
        JsonObject root = JsonParser.parseString(raw).getAsJsonObject();
        JsonObject guidanceJson = root.getAsJsonObject("guidance_data").deepCopy();
        normalizeGuidanceEnumFields(guidanceJson);
        RVP_GuidanceData guidance = GsonUtil.GSON.fromJson(guidanceJson, RVP_GuidanceData.class);
        if (guidance == null) {
            throw new IllegalStateException("guidance_data missing in " + weaponJson);
        }
        return guidance.getStages();
    }

    /** Mirrors {@link org.ywzj.rvp.all.RVP_WeaponTypes} enum casing normalization. */
    public static void normalizeGuidanceEnumFields(JsonObject guidanceObject) {
        if (guidanceObject.has("phase_resolve_policy")
                && guidanceObject.get("phase_resolve_policy").isJsonPrimitive()) {
            String policy = guidanceObject.get("phase_resolve_policy").getAsString();
            guidanceObject.addProperty("phase_resolve_policy", policy.trim().toUpperCase(Locale.ROOT));
        }
        if (!guidanceObject.has("stages") || !guidanceObject.get("stages").isJsonArray()) {
            return;
        }
        for (JsonElement stageElement : guidanceObject.getAsJsonArray("stages")) {
            if (!stageElement.isJsonObject()) {
                continue;
            }
            JsonObject stage = stageElement.getAsJsonObject();
            if (!stage.has("sources") || !stage.get("sources").isJsonArray()) {
                continue;
            }
            for (JsonElement sourceElement : stage.getAsJsonArray("sources")) {
                if (!sourceElement.isJsonObject()) {
                    continue;
                }
                JsonObject source = sourceElement.getAsJsonObject();
                if (source.has("type") && source.get("type").isJsonPrimitive()) {
                    source.addProperty("type", source.get("type").getAsString().trim().toUpperCase(Locale.ROOT));
                }
                if (source.has("composite_mode") && source.get("composite_mode").isJsonPrimitive()) {
                    source.addProperty("composite_mode",
                            source.get("composite_mode").getAsString().trim().toUpperCase(Locale.ROOT));
                }
            }
        }
    }

    public static boolean isActive(RVP_GuidanceActivationData activation, RVP_GuidanceActivationContext ctx) {
        return RVP_GuidanceActivationEvaluator.isPhaseActive(activation, ctx);
    }

    public static RVP_GuidanceActivationContext ctx(
            int tick, double targetDist, boolean hasEntity, boolean hasIllumination, double altitude
    ) {
        return ctx(tick, targetDist, hasEntity ? targetDist : -1, hasEntity, hasIllumination, altitude);
    }

    public static RVP_GuidanceActivationContext ctx(
            int tick,
            double targetDist,
            double entityDist,
            boolean hasEntity,
            boolean hasIllumination,
            double altitude
    ) {
        boolean hasTargetPoint = targetDist >= 0;
        return new RVP_GuidanceActivationContext(
                null,
                tick,
                hasTargetPoint ? targetDist : -1,
                hasEntity ? entityDist : -1,
                altitude,
                hasTargetPoint,
                hasEntity,
                hasIllumination
        );
    }

    /**
     * Mirrors {@link RVP_GuidancePhaseSelector#selectActive} activation + compatibility without MC projectile.
     */
    public static List<String> simulateStageNames(
            List<RVP_GuidanceStageData> stages,
            RVP_GuidanceActivationContext ctx,
            Set<Integer> sticky
    ) {
        List<RVP_GuidancePhaseSelector.StageSelection> active = new ArrayList<>();
        for (int i = 0; i < stages.size(); i++) {
            RVP_GuidanceStageData stage = stages.get(i);
            RVP_GuidanceActivationData activation = stage.getActivation();
            boolean stickyHeld = sticky.contains(i);
            if (stickyHeld || isActive(activation, ctx)) {
                active.add(new RVP_GuidancePhaseSelector.StageSelection(stage, i));
                if (activation.isEnterOnce() && !stickyHeld) {
                    sticky.add(i);
                }
            }
        }
        if (active.size() > 1) {
            active = RVP_GuidanceCompositeCompatibility.resolveCompatible(
                    active,
                    ss -> ss.stage().getActivation().specificityScore(),
                    ss -> ss.stage().getPrimaryGuidanceType()
            );
        }
        return active.stream().map(s -> s.stage().getName()).toList();
    }

    public static List<String> simulateStageNames(
            RVP_GuidanceData guidance,
            List<RVP_GuidanceStageData> stages,
            RVP_GuidanceActivationContext ctx,
            Set<Integer> sticky
    ) {
        List<RVP_GuidancePhaseSelector.StageSelection> active = new ArrayList<>();
        for (int i = 0; i < stages.size(); i++) {
            RVP_GuidanceStageData stage = stages.get(i);
            RVP_GuidanceActivationData activation = stage.getActivation();
            boolean stickyHeld = sticky.contains(i);
            if (stickyHeld || isActive(activation, ctx)) {
                active.add(new RVP_GuidancePhaseSelector.StageSelection(stage, i));
                if (activation.isEnterOnce() && !stickyHeld) {
                    sticky.add(i);
                }
            }
        }
        if (active.size() > 1) {
            active = resolveWithPolicy(guidance.getPhaseResolvePolicy(), active);
        }
        return active.stream().map(s -> s.stage().getName()).toList();
    }

    private static List<RVP_GuidancePhaseSelector.StageSelection> resolveWithPolicy(
            RVP_EnumPhaseResolvePolicy policy,
            List<RVP_GuidancePhaseSelector.StageSelection> active
    ) {
        return switch (policy) {
            case FIRST_PHASE -> List.of(active.get(0));
            case STICKY, HIGHEST_SPECIFICITY -> RVP_GuidanceCompositeCompatibility.resolveCompatible(
                    active,
                    ss -> ss.stage().getActivation().specificityScore(),
                    ss -> ss.stage().getPrimaryGuidanceType()
            );
        };
    }

    public static Set<Integer> emptySticky() {
        return new HashSet<>();
    }

    public static int indexOf(List<RVP_GuidanceStageData> stages, String name) {
        for (int i = 0; i < stages.size(); i++) {
            if (name.equals(stages.get(i).getName())) {
                return i;
            }
        }
        return -1;
    }
}
