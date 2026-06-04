package org.ywzj.rvp.entity.gunner.ai.profile;

import com.google.gson.JsonElement;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimplePreparableReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraftforge.event.AddReloadListenerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.ywzj.rvp.RVP_MOD;
import org.ywzj.vehicle.custom.serialize.GsonUtil;
import org.ywzj.vehicle.util.ResourceScanner;

import java.util.HashMap;
import java.util.Map;

@Mod.EventBusSubscriber(modid = RVP_MOD.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public class GunnerProfileManager extends SimplePreparableReloadListener<Map<ResourceLocation, JsonElement>> {

    public static final ResourceLocation DEFAULT_PROFILE_ID = RVP_MOD.modLocation("default");
    public static final GunnerProfile DEFAULT_PROFILE = createDefaultProfile();
    public static final GunnerProfileManager INSTANCE = new GunnerProfileManager();

    private Map<ResourceLocation, GunnerProfile> profiles = Map.of(DEFAULT_PROFILE_ID, DEFAULT_PROFILE);

    @Override
    protected Map<ResourceLocation, JsonElement> prepare(ResourceManager resourceManager, ProfilerFiller profiler) {
        Map<ResourceLocation, JsonElement> map = new HashMap<>();
        Map<ResourceLocation, JsonElement> legacy = ResourceScanner.scanDirectory(resourceManager, "gunner_profiles", GsonUtil.GSON);
        Map<ResourceLocation, JsonElement> gunner = ResourceScanner.scanDirectory(resourceManager, "gunner", GsonUtil.GSON);

        legacy.forEach((id, json) -> map.put(RVP_MOD.modLocation(id.getPath()), json));
        gunner.forEach((id, json) -> map.put(RVP_MOD.modLocation(id.getPath()), json));

        return Map.copyOf(map);
    }

    @Override
    protected void apply(Map<ResourceLocation, JsonElement> map, ResourceManager resourceManager, ProfilerFiller profiler) {
        Map<ResourceLocation, GunnerProfile> loaded = new HashMap<>();
        loaded.put(DEFAULT_PROFILE_ID, DEFAULT_PROFILE);
        map.forEach((id, json) -> {
            try {
                GunnerProfile profile = GsonUtil.GSON.fromJson(json, GunnerProfile.class);
                if (profile != null) {
                    profile.normalize(id.getPath());
                    loaded.put(id, profile);
                }
            } catch (Exception ignored) {}
        });
        profiles = Map.copyOf(loaded);
    }

    public GunnerProfile getProfile(ResourceLocation id) {
        return profiles.getOrDefault(id, DEFAULT_PROFILE);
    }

    public ResourceLocation normalizeProfileId(String profileName) {
        if (profileName == null || profileName.isBlank() || "default".equals(profileName)) {
            return DEFAULT_PROFILE_ID;
        }
        String normalized = profileName.trim();
        if (!normalized.contains(":")) {
            return RVP_MOD.modLocation(normalized);
        }
        ResourceLocation parsed = ResourceLocation.tryParse(normalized);
        if (parsed == null) {
            return DEFAULT_PROFILE_ID;
        }
        if (profiles.containsKey(parsed)) {
            return parsed;
        }
        if ("minecraft".equals(parsed.getNamespace())) {
            ResourceLocation remapped = RVP_MOD.modLocation(parsed.getPath());
            if (profiles.containsKey(remapped)) {
                return remapped;
            }
        }
        return parsed;
    }

    @SubscribeEvent
    public static void onAddReloadListener(AddReloadListenerEvent event) {
        event.addListener(INSTANCE);
    }

    private static GunnerProfile createDefaultProfile() {
        GunnerProfile profile = new GunnerProfile();
        profile.normalize("default");
        return profile;
    }
}
