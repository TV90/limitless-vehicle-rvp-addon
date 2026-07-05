package org.ywzj.rvp.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.annotations.SerializedName;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraftforge.fml.loading.FMLPaths;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Nullable;
import org.ywzj.vehicle.util.ResourceScanner;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * UI 预设管理器。
 * <p>
 * 从 {@code config/limitless_vehicle/ui_presets/*.json} 加载各预设文件。
 * 每辆载具通过 vehicle JSON 的 {@code "ui_preset": "preset_name"} 指定要使用的预设。
 * 可通过 {@link #get(String)} 获取预设。
 * </p>
 */
public class UIPresetManager {

    private static final Logger LOGGER = LogManager.getLogger("UIPresetManager");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Map<String, UIPreset> PRESETS = new HashMap<>();
    private static final String CONFIG_PRESET_DIR = "limitless_vehicle/ui_presets";
    private static final String DATAPACK_PRESET_DIR = "ui_presets";

    /** 单组件锚点 */
    public enum Anchor {
        @SerializedName("center") CENTER,
        @SerializedName("top_left") TOP_LEFT,
        @SerializedName("left") LEFT,
        @SerializedName("right") RIGHT,
        @SerializedName("right_bottom") RIGHT_BOTTOM
    }

    /** 单组件位置 */
    public static class UIPosition {
        /** 参考高度（1080p），offset 按实际分辨率等比缩放 */
        public static final int REF_HEIGHT = 1080;

        public Anchor anchor;
        @SerializedName("offset_x")
        public int offsetX;
        @SerializedName("offset_y")
        public int offsetY;
        public float scale = 1.0f;

        public int computeX(int screenWidth) {
            Anchor resolvedAnchor = anchor == null ? Anchor.RIGHT : anchor;
            if (resolvedAnchor == Anchor.CENTER) {
                return screenWidth / 2 + offsetX;
            }
            if (resolvedAnchor == Anchor.TOP_LEFT || resolvedAnchor == Anchor.LEFT) {
                return offsetX;
            }
            if (resolvedAnchor == Anchor.RIGHT) {
                return screenWidth / 2 + offsetX;
            }
            return screenWidth / 2 + offsetX;
        }

        public int computeY(int screenHeight) {
            float ratio = (float) screenHeight / REF_HEIGHT;
            int scaledOffset = Math.round(offsetY * ratio);
            Anchor resolvedAnchor = anchor == null ? Anchor.RIGHT : anchor;
            if (resolvedAnchor == Anchor.CENTER) {
                return screenHeight / 2 + scaledOffset;
            }
            if (resolvedAnchor == Anchor.TOP_LEFT) {
                return scaledOffset;
            }
            if (resolvedAnchor == Anchor.LEFT) {
                return screenHeight / 2 + scaledOffset;
            }
            if (resolvedAnchor == Anchor.RIGHT) {
                return screenHeight + scaledOffset;
            }
            return screenHeight + scaledOffset;
        }
    }

    /** 单个预设（一个预设文件 = 一个布局风格） */
    public static class UIPreset {
        public String name;
        public UIPosition radar;
        @SerializedName("external_radar")
        public UIPosition externalRadar;
        public UIPosition rwr;
        @SerializedName("vehicle_bones")
        public UIPosition vehicleBones;
        @SerializedName("scope_envelope")
        public UIPosition scopeEnvelope;
        /** 多雷达独立位置，key = sub_part_unit_id（如 "scan_radar"），value = 位置 */
        public Map<String, UIPosition> radars;
    }

    /** 获取指定名称的预设，不存在返回 null */
    public static UIPreset get(String name) {
        if (name == null || name.isEmpty()) return null;
        UIPreset preset = PRESETS.get(name);
        if (preset != null) {
            return preset;
        }
        ResourceLocation id = ResourceLocation.tryParse(name);
        if (id != null) {
            preset = PRESETS.get(id.toString());
            if (preset != null) {
                return preset;
            }
            return PRESETS.get(id.getPath());
        }
        return null;
    }

    /** 获取当前载具应使用的预设（根据 presetName），不存在或为空返回 null */
    public static UIPosition getRadar(String presetName) {
        UIPreset preset = get(presetName);
        return preset == null ? null : preset.radar;
    }

    public static UIPosition getExternalRadar(String presetName) {
        UIPreset preset = get(presetName);
        return preset == null ? null : preset.externalRadar;
    }

    /**
     * 获取指定雷达部件的 UI 位置。
     * 优先查找 {@code radars[partUnitId]}，不存在则回退到 {@code radar}。
     */
    public static UIPosition getRadarByPartId(String presetName, String partUnitId) {
        UIPreset preset = get(presetName);
        if (preset == null) return null;
        if (preset.radars != null && partUnitId != null) {
            UIPosition pos = preset.radars.get(partUnitId);
            if (pos != null) return pos;
        }
        return preset.radar;
    }

    public static UIPosition getRwr(String presetName) {
        UIPreset preset = get(presetName);
        return preset == null ? null : preset.rwr;
    }

    /** 返回所有已加载预设的名称列表 */
    public static java.util.Set<String> getLoadedPresetNames() {
        return java.util.Collections.unmodifiableSet(PRESETS.keySet());
    }

    public static UIPosition getVehicleBones(String presetName) {
        UIPreset preset = get(presetName);
        return preset == null ? null : preset.vehicleBones;
    }

    /** 加载所有预设文件 */
    public static void load() {
        PRESETS.clear();
        loadConfigPresets();
        if (PRESETS.isEmpty()) {
            LOGGER.warn("No UI presets found, creating default presets");
            createDefaultPresets(configPresetDir());
        }
    }

    /** 从数据包 + config 加载预设。数据包同名预设优先，config 仅作回退。 */
    public static void load(@Nullable ResourceManager resourceManager) {
        PRESETS.clear();
        if (resourceManager != null) {
            loadDatapackPresets(resourceManager);
        }
        loadConfigPresets();
        if (PRESETS.isEmpty()) {
            LOGGER.warn("No UI presets found, creating default presets");
            createDefaultPresets(configPresetDir());
        }
    }

    private static void loadDatapackPresets(ResourceManager resourceManager) {
        Map<ResourceLocation, JsonElement> entries = ResourceScanner.scanDirectory(resourceManager, DATAPACK_PRESET_DIR, GSON);
        entries.forEach((id, json) -> {
            try {
                UIPreset preset = GSON.fromJson(json, UIPreset.class);
                registerPreset(preset, id.getPath(), id.toString(), "data/" + id);
            } catch (Exception e) {
                LOGGER.error("Failed to load UI preset from data pack: {}", id, e);
            }
        });
    }

    private static void loadConfigPresets() {
        Path presetDir = configPresetDir();
        if (Files.notExists(presetDir)) {
            createDefaultPresets(presetDir);
            return;
        }
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(presetDir, "*.json")) {
            for (Path entry : stream) {
                try {
                    String json = Files.readString(entry);
                    UIPreset preset = GSON.fromJson(json, UIPreset.class);
                    String fileName = entry.getFileName().toString();
                    String fallbackName = fileName.endsWith(".json") ? fileName.substring(0, fileName.length() - 5) : fileName;
                    if ("default".equalsIgnoreCase(fallbackName) && preset != null && preset.externalRadar != null) {
                        UIPosition pos = preset.externalRadar;
                        if ((pos.anchor == null || pos.anchor == Anchor.RIGHT)
                                && pos.offsetX == -128
                                && pos.offsetY == -260
                                && Math.abs(pos.scale - 1.0f) < 0.0001f) {
                            pos.anchor = Anchor.TOP_LEFT;
                            pos.offsetX = 80;
                            pos.offsetY = 300;
                            Files.writeString(entry, GSON.toJson(preset));
                        }
                        if (pos.anchor == Anchor.TOP_LEFT
                                && pos.offsetX == 80
                                && (pos.offsetY == 160 || pos.offsetY == 220)
                                && Math.abs(pos.scale - 1.0f) < 0.0001f) {
                            pos.offsetY = 300;
                            Files.writeString(entry, GSON.toJson(preset));
                        }
                    }
                    registerPreset(preset, fallbackName, null, "config/" + fileName);
                } catch (Exception e) {
                    LOGGER.error("Failed to load UI preset: {}", entry.getFileName(), e);
                }
            }
        } catch (IOException e) {
            LOGGER.error("Failed to scan UI presets directory", e);
        }
    }

    private static Path configPresetDir() {
        return FMLPaths.CONFIGDIR.get().resolve(CONFIG_PRESET_DIR);
    }

    private static void registerPreset(@Nullable UIPreset preset, String fallbackName, @Nullable String alias, String source) {
        if (preset == null) {
            return;
        }
        if (preset.name == null || preset.name.isBlank()) {
            preset.name = fallbackName;
        }
        String key = preset.name.trim();
        if (PRESETS.putIfAbsent(key, preset) == null) {
            LOGGER.info("Loaded UI preset: {} from {}", key, source);
        }
        if (alias != null && !alias.isBlank()) {
            PRESETS.putIfAbsent(alias, preset);
        }
    }

    private static void createDefaultPresets(Path presetDir) {
        try {
            Files.createDirectories(presetDir);

            // 默认预设（与原硬编码位置一致）
            String defaultPreset = """
                    {
                      "name": "default",
                      "radar": {
                        "anchor": "right",
                        "offset_x": 128,
                        "offset_y": -80,
                        "scale": 1.0
                      },
                      "external_radar": {
                        "anchor": "top_left",
                        "offset_x": 80,
                        "offset_y": 300,
                        "scale": 1.0
                      },
                      "rwr": {
                        "anchor": "right",
                        "offset_x": 128,
                        "offset_y": -180,
                        "scale": 1.0
                      },
                      "vehicle_bones": {
                        "anchor": "right_bottom",
                        "offset_x": 116,
                        "offset_y": 80,
                        "scale": 1.0
                      }
                    }
                    """;
            Files.writeString(presetDir.resolve("default.json"), defaultPreset);
            LOGGER.info("Created default UI preset at {}", presetDir.resolve("default.json"));

            // 加载默认预设
            UIPreset preset = GSON.fromJson(defaultPreset, UIPreset.class);
            registerPreset(preset, "default", null, "generated default");
        } catch (IOException e) {
            LOGGER.error("Failed to create default UI presets", e);
        }
    }
}
