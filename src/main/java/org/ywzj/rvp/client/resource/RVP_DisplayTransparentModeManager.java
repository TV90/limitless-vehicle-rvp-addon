package org.ywzj.rvp.client.resource;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.resources.FileToIdConverter;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimplePreparableReloadListener;
import net.minecraft.util.GsonHelper;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.jetbrains.annotations.NotNull;
import org.ywzj.vehicle.client.resource.ClientAssetsManager;
import org.ywzj.vehicle.client.resource.vehicle.BaseDisplay;
import org.ywzj.vehicle.client.resource.vehicle.VehicleBedrockModel;
import org.ywzj.vehicle.custom.serialize.GsonUtil;
import org.ywzj.vehicle.util.ResourceScanner;

import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;

@OnlyIn(Dist.CLIENT)
public final class RVP_DisplayTransparentModeManager extends SimplePreparableReloadListener<Map<ResourceLocation, JsonElement>> {

    public static final RVP_DisplayTransparentModeManager INSTANCE = new RVP_DisplayTransparentModeManager();

    private static final String MODE_FIELD = "ywzj_rvp_transparent_mode";
    private static final String MODE_COCKPIT_DEPTH_FIX = "cockpit_depth_fix";

    private final Map<ResourceLocation, Set<String>> cockpitDepthFixBonesByDisplayId = new ConcurrentHashMap<>();
    private final Map<VehicleBedrockModel, Set<String>> cockpitDepthFixBonesByModel = Collections.synchronizedMap(new WeakHashMap<>());

    private RVP_DisplayTransparentModeManager() {}

    @Override
    protected @NotNull Map<ResourceLocation, JsonElement> prepare(@NotNull ResourceManager resourceManager, @NotNull ProfilerFiller profiler) {
        return ResourceScanner.scanDirectory(resourceManager, FileToIdConverter.json("display/vehicle"), GsonUtil.GSON);
    }

    @Override
    protected void apply(@NotNull Map<ResourceLocation, JsonElement> resources, @NotNull ResourceManager resourceManager, @NotNull ProfilerFiller profiler) {
        cockpitDepthFixBonesByDisplayId.clear();
        cockpitDepthFixBonesByModel.clear();
        for (var entry : resources.entrySet()) {
            JsonObject root;
            try {
                root = GsonHelper.convertToJsonObject(entry.getValue(), "rvp vehicle display");
            } catch (Exception ignored) {
                continue;
            }
            JsonArray effects = root.has("special_bone_effects") && root.get("special_bone_effects").isJsonArray()
                    ? root.getAsJsonArray("special_bone_effects")
                    : null;
            if (effects == null || effects.isEmpty()) {
                continue;
            }
            Set<String> bones = ConcurrentHashMap.newKeySet();
            for (JsonElement effectElement : effects) {
                if (!effectElement.isJsonObject()) {
                    continue;
                }
                JsonObject effect = effectElement.getAsJsonObject();
                String mode = GsonHelper.getAsString(effect, MODE_FIELD, "");
                if (!MODE_COCKPIT_DEPTH_FIX.equals(mode)) {
                    continue;
                }
                String bone = GsonHelper.getAsString(effect, "bone", "");
                if (!bone.isEmpty()) {
                    bones.add(bone);
                }
            }
            if (!bones.isEmpty()) {
                cockpitDepthFixBonesByDisplayId.put(entry.getKey(), Set.copyOf(bones));
            }
        }
    }

    public boolean isCockpitDepthFix(VehicleBedrockModel model, String bone) {
        if (model == null || bone == null || bone.isEmpty()) {
            return false;
        }
        Set<String> bound = cockpitDepthFixBonesByModel.get(model);
        if (bound == null) {
            bindKnownDisplays();
            bound = cockpitDepthFixBonesByModel.get(model);
        }
        return bound != null && bound.contains(bone);
    }

    public boolean hasCockpitDepthFix(VehicleBedrockModel model) {
        if (model == null) {
            return false;
        }
        Set<String> bound = cockpitDepthFixBonesByModel.get(model);
        if (bound == null) {
            bindKnownDisplays();
            bound = cockpitDepthFixBonesByModel.get(model);
        }
        return bound != null && !bound.isEmpty();
    }

    private void bindKnownDisplays() {
        Map<ResourceLocation, BaseDisplay> displays = ClientAssetsManager.INSTANCE.getVehicleDisplays();
        if (displays == null || displays.isEmpty()) {
            return;
        }
        for (var entry : displays.entrySet()) {
            Set<String> bones = cockpitDepthFixBonesByDisplayId.get(entry.getKey());
            if (bones == null || bones.isEmpty()) {
                continue;
            }
            VehicleBedrockModel model = entry.getValue().getModel();
            if (model != null) {
                cockpitDepthFixBonesByModel.put(model, bones);
            }
        }
    }

    public static String modeField() {
        return MODE_FIELD;
    }

    public static String cockpitDepthFixMode() {
        return MODE_COCKPIT_DEPTH_FIX;
    }
}
