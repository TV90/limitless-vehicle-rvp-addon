package org.ywzj.rvp.config;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.util.GsonHelper;

import java.util.ArrayList;
import java.util.List;

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
        float deployedPitch
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
                (float) GsonHelper.getAsDouble(obj, "stowed_pitch", 0.0),
                (float) GsonHelper.getAsDouble(obj, "deployed_pitch", 88.0)
        );
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
