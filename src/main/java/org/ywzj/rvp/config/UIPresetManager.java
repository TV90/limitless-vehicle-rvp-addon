package org.ywzj.rvp.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.annotations.SerializedName;
import net.minecraftforge.fml.loading.FMLPaths;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

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

    /** 单组件锚点 */
    public enum Anchor {
        @SerializedName("center") CENTER,
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
            return switch (anchor == null ? Anchor.RIGHT : anchor) {
                case CENTER -> screenWidth / 2 + offsetX;
                case RIGHT -> screenWidth / 2 + offsetX;
                case RIGHT_BOTTOM -> screenWidth / 2 + offsetX;
            };
        }

        public int computeY(int screenHeight) {
            float ratio = (float) screenHeight / REF_HEIGHT;
            int scaledOffset = Math.round(offsetY * ratio);
            return switch (anchor == null ? Anchor.RIGHT : anchor) {
                case CENTER -> screenHeight / 2 + scaledOffset;
                case RIGHT -> screenHeight + scaledOffset;
                case RIGHT_BOTTOM -> screenHeight + scaledOffset;
            };
        }
    }

    /** 单个预设（一个预设文件 = 一个布局风格） */
    public static class UIPreset {
        public String name;
        public UIPosition radar;
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
        return PRESETS.get(name);
    }

    /** 获取当前载具应使用的预设（根据 presetName），不存在或为空返回 null */
    public static UIPosition getRadar(String presetName) {
        UIPreset preset = get(presetName);
        return preset == null ? null : preset.radar;
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
        Path presetDir = FMLPaths.CONFIGDIR.get().resolve("limitless_vehicle/ui_presets");
        if (Files.notExists(presetDir)) {
            createDefaultPresets(presetDir);
            return;
        }

        PRESETS.clear();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(presetDir, "*.json")) {
            for (Path entry : stream) {
                try {
                    String json = Files.readString(entry);
                    UIPreset preset = GSON.fromJson(json, UIPreset.class);
                    if (preset != null && preset.name != null && !preset.name.isEmpty()) {
                        PRESETS.put(preset.name, preset);
                        LOGGER.info("Loaded UI preset: {}", preset.name);
                    }
                } catch (Exception e) {
                    LOGGER.error("Failed to load UI preset: {}", entry.getFileName(), e);
                }
            }
        } catch (IOException e) {
            LOGGER.error("Failed to scan UI presets directory", e);
        }

        if (PRESETS.isEmpty()) {
            LOGGER.warn("No UI presets found, creating default presets");
            createDefaultPresets(presetDir);
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
            if (preset != null) {
                PRESETS.put(preset.name, preset);
            }
        } catch (IOException e) {
            LOGGER.error("Failed to create default UI presets", e);
        }
    }
}
