package org.ywzj.rvp.config;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.GsonHelper;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

public record RVP_LauncherDeployConfig(
        boolean enabled,
        String id,
        String partUnitId,
        String pitchPartUnitId,
        String pitchGroup,
        List<String> weaponUnitIds,
        double deploySpeedMax,
        double retractSpeedMin,
        int deployTimeTick,
        int retractTimeTick,
        boolean requirePlayerPresent,
        boolean autoDeploy,
        boolean autoRetract,
        boolean blockFireWhenClosed,
        boolean blockFireWhenDeploying,
        boolean blockFireWhenRetracting,
        boolean blockFireWhenSpeeding,
        boolean applyPitchAfterWeaponTick,
        float stowedPitch,
        float deployedPitch,
        // 按弹种选择俯仰骨下的独立炮管子骨，子骨内 Cube 的顺序决定发射顺序。
        Map<ResourceLocation, String> barrelBonesByWeapon,
        // 收拢到展开的归一化时间与结构俯仰角，省略时保持线性插值。
        List<PitchPoint> pitchCurve
) {
    public static List<RVP_LauncherDeployConfig> parseList(JsonObject vehicleObj) {
        if (!vehicleObj.has("rvp_launcher_deploy")) {
            return List.of();
        }

        JsonElement root = vehicleObj.get("rvp_launcher_deploy");
        List<RVP_LauncherDeployConfig> list = new ArrayList<>();
        if (root.isJsonObject()) {
            RVP_LauncherDeployConfig config = parseOne(root.getAsJsonObject(), 0);
            if (config != null) {
                list.add(config);
            }
            return List.copyOf(list);
        }
        if (!root.isJsonArray()) {
            return List.of();
        }

        int index = 0;
        for (JsonElement element : root.getAsJsonArray()) {
            if (element != null && element.isJsonObject()) {
                RVP_LauncherDeployConfig config = parseOne(element.getAsJsonObject(), index++);
                if (config != null) {
                    list.add(config);
                }
            }
        }
        return List.copyOf(list);
    }

    private static RVP_LauncherDeployConfig parseOne(JsonObject obj, int index) {
        boolean enabled = GsonHelper.getAsBoolean(obj, "enabled", true);
        if (!enabled) {
            return null;
        }

        String partUnitId = GsonHelper.getAsString(obj, "part_unit_id", "").trim();
        List<String> weaponUnitIds = parseStringList(obj, "weapon_unit_ids");
        if (partUnitId.isEmpty() && weaponUnitIds.isEmpty()) {
            return null;
        }

        String id = GsonHelper.getAsString(obj, "id", "").trim();
        if (id.isEmpty()) {
            id = !partUnitId.isEmpty() ? partUnitId : "launcher_" + index;
        }

        String pitchPartUnitId = GsonHelper.getAsString(obj, "pitch_part_unit_id", "").trim();
        if (pitchPartUnitId.isEmpty()) {
            if (!weaponUnitIds.isEmpty()) {
                pitchPartUnitId = weaponUnitIds.get(0);
            } else {
                pitchPartUnitId = partUnitId;
            }
        }

        String pitchGroup = GsonHelper.getAsString(obj, "pitch_group", "").trim();
        double deploySpeedMax = Math.max(0.0, GsonHelper.getAsDouble(obj, "deploy_speed_max", 2.0));
        double retractSpeedMin = Math.max(
                deploySpeedMax,
                GsonHelper.getAsDouble(obj, "retract_speed_min", deploySpeedMax + 0.5)
        );

        float stowedPitch = GsonHelper.getAsFloat(obj, "stowed_pitch", 0.0F);
        float deployedPitch = GsonHelper.getAsFloat(obj, "deployed_pitch", 88.0F);
        return new RVP_LauncherDeployConfig(
                true,
                id,
                partUnitId,
                pitchPartUnitId,
                pitchGroup,
                weaponUnitIds,
                deploySpeedMax,
                retractSpeedMin,
                Math.max(1, GsonHelper.getAsInt(obj, "deploy_time_tick", 60)),
                Math.max(1, GsonHelper.getAsInt(obj, "retract_time_tick", 40)),
                GsonHelper.getAsBoolean(obj, "require_player_present", true),
                GsonHelper.getAsBoolean(obj, "auto_deploy", true),
                GsonHelper.getAsBoolean(obj, "auto_retract", true),
                GsonHelper.getAsBoolean(obj, "block_fire_when_closed", true),
                GsonHelper.getAsBoolean(obj, "block_fire_when_deploying", true),
                GsonHelper.getAsBoolean(obj, "block_fire_when_retracting", true),
                GsonHelper.getAsBoolean(obj, "block_fire_when_speeding", true),
                GsonHelper.getAsBoolean(obj, "apply_pitch_after_weapon_tick", false),
                stowedPitch,
                deployedPitch,
                parseBarrelBones(obj.get("barrel_bones_by_weapon")),
                parsePitchCurve(obj.get("pitch_curve"), stowedPitch, deployedPitch)
        );
    }

    /** 单个结构关键帧：进度从 0 到 1，角度使用本体的俯仰符号。 */
    public record PitchPoint(float progress, float angle) {}

    private static List<PitchPoint> parsePitchCurve(JsonElement value, float stowedPitch, float deployedPitch) {
        TreeMap<Float, Float> points = new TreeMap<>();
        points.put(0.0F, stowedPitch);
        if (value != null && value.isJsonArray()) {
            for (JsonElement entry : value.getAsJsonArray()) {
                if (!entry.isJsonArray() || entry.getAsJsonArray().size() != 2) {
                    continue;
                }
                try {
                    float progress = entry.getAsJsonArray().get(0).getAsFloat();
                    float angle = entry.getAsJsonArray().get(1).getAsFloat();
                    if (progress > 0.0F && progress < 1.0F && Float.isFinite(angle)) {
                        points.put(progress, angle);
                    }
                } catch (IllegalStateException | UnsupportedOperationException | NumberFormatException ignored) {
                    // 忽略无效的中间帧，仍可使用相邻有效帧插值。
                }
            }
        }
        points.put(1.0F, deployedPitch);
        return points.entrySet().stream().map(e -> new PitchPoint(e.getKey(), e.getValue())).toList();
    }

    /** 使用与显示动画一致的姿态曲线，收回时以反向进度取样同一条曲线。 */
    public float pitchAt(float progress) {
        if (progress <= 0.0F) {
            return stowedPitch;
        }
        PitchPoint prior = pitchCurve.get(0);
        for (int i = 1; i < pitchCurve.size(); i++) {
            PitchPoint next = pitchCurve.get(i);
            if (progress <= next.progress()) {
                float fraction = (progress - prior.progress()) / (next.progress() - prior.progress());
                return prior.angle() + fraction * (next.angle() - prior.angle());
            }
            prior = next;
        }
        return deployedPitch;
    }

    /** 读取炮管构型，保留声明顺序供尚未同步选弹的实体使用默认结构。 */
    private static Map<ResourceLocation, String> parseBarrelBones(JsonElement value) {
        if (value == null || !value.isJsonObject()) {
            return Map.of();
        }
        Map<ResourceLocation, String> result = new LinkedHashMap<>();
        for (var entry : value.getAsJsonObject().entrySet()) {
            ResourceLocation weapon = ResourceLocation.tryParse(entry.getKey());
            JsonElement bone = entry.getValue();
            if (weapon == null || !bone.isJsonPrimitive() || !bone.getAsJsonPrimitive().isString()) {
                continue;
            }
            String name = bone.getAsString().trim();
            if (!name.isEmpty()) {
                result.put(weapon, name);
            }
        }
        return Collections.unmodifiableMap(result);
    }

    private static List<String> parseStringList(JsonObject obj, String key) {
        List<String> list = new ArrayList<>();
        if (!obj.has(key)) {
            return list;
        }
        JsonElement element = obj.get(key);
        if (element.isJsonPrimitive()) {
            String value = element.getAsString().trim();
            if (!value.isEmpty()) {
                list.add(value);
            }
            return list;
        }
        if (!element.isJsonArray()) {
            return list;
        }
        for (JsonElement item : element.getAsJsonArray()) {
            if (item != null && item.isJsonPrimitive()) {
                String value = item.getAsString().trim();
                if (!value.isEmpty()) {
                    list.add(value);
                }
            }
        }
        return list;
    }

    public boolean appliesToWeaponUnit(String weaponUnitId) {
        return weaponUnitId != null && weaponUnitIds.contains(weaponUnitId);
    }
}
