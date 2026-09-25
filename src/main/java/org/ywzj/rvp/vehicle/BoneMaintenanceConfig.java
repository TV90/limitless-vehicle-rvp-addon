package org.ywzj.rvp.vehicle;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.util.GsonHelper;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 骨块级快速维修配置（{@code bone_modules} 骨块条目的 {@code maintenance} 子对象），
 * 写法规范对齐 {@link BoneEcmActiveConfig} 等状态级设备。
 *
 * <p>典型配置：挂在虚拟骨骼 {@code __vehicle__} 上（载具级能力，永不可被击毁）：</p>
 * <pre>
 * "bone_modules": {
 *   "__vehicle__": {
 *     "modules": ["MAINTENANCE"],
 *     "maintenance": { "use_time_ticks": 20, "wait_time_ticks": 600 }
 *   }
 * }
 * </pre>
 * <p>也可绑定实体骨（如发动机）：骨块被直击打掉则维修模块失效——快修无法触发，
 * 该能力即告失去（对抗性设计，慎用）。</p>
 *
 * <p><b>2026-09-25 起全载具默认快修</b>：未显式配置 maintenance 的载具在
 * {@code resolveMaintenanceModule} 回退 {@link #defaults()}（冷却 300t=15 秒、
 * 生效 20t × 1.5%/tick = 一次修复 30% 最大血量，挂在永不可毁的虚拟骨上）；
 * 显式配置仍按 JSON 覆盖。</p>
 *
 * @param useTimeTicks        生效时长 tick（1~1200），每 tick 回 healPerTickPercent% 最大血量
 * @param waitTimeTicks       冷却时长 tick（0~100000）
 * @param healPerTickPercent  每 tick 回复量占最大血量百分比（0.1~100，默认 1.5：20t 共 30%）
 * @param healParts           生效期是否同步回部件血量（每部件 +10% 上限，默认 false）
 * @param requireMaxAltitude  允许触发的离地高度上限（方块，<0 不限；默认 -1）
 * @param moduleRepair        模块渐进恢复子配置（null = 缺省：设备概率 25% + ERA 25%）
 */
public record BoneMaintenanceConfig(
        int useTimeTicks,
        int waitTimeTicks,
        float healPerTickPercent,
        boolean healParts,
        float requireMaxAltitude,
        @Nullable ModuleRepair moduleRepair
) {

    /**
     * 全载具默认快修配置（未显式配置 maintenance 时的回退值，用户 2026-09-25 定版）：
     * 冷却 300t（15 秒）、生效 20t × 1.5%/tick = 一次修复 30% 最大血量；
     * 不回部件血量、无离地限制、模块恢复走缺省（设备 25% + ERA 25%/至少 1 块）。
     */
    public static BoneMaintenanceConfig defaults() {
        return new BoneMaintenanceConfig(20, 300, 1.5f, false, -1f, null);
    }

    /** 解析 maintenance 子对象；缺省值由字段初值决定（ GsonUtil 同款语义）。 */
    @Nullable
    public static BoneMaintenanceConfig parse(@Nullable JsonElement element) {
        if (element == null || !element.isJsonObject()) {
            return null;
        }
        JsonObject obj = element.getAsJsonObject();
        int useTimeTicks = Math.max(1, Math.min(1200, GsonHelper.getAsInt(obj, "use_time_ticks", 20)));
        int waitTimeTicks = Math.max(0, Math.min(100000, GsonHelper.getAsInt(obj, "wait_time_ticks", 300)));
        // 缺省值与 defaults() 对齐（用户 2026-09-25 定版：冷却 15 秒、一次修复 30% 血量）
        float healPercent = GsonHelper.getAsFloat(obj, "heal_per_tick_percent", 1.5f);
        if (!Float.isFinite(healPercent) || healPercent <= 0f) {
            healPercent = 1.0f;
        }
        healPercent = Math.min(100f, healPercent);
        boolean healParts = GsonHelper.getAsBoolean(obj, "heal_parts", false);
        float maxAltitude = GsonHelper.getAsFloat(obj, "require_max_altitude", -1f);
        if (!Float.isFinite(maxAltitude)) {
            maxAltitude = -1f;
        }
        ModuleRepair moduleRepair = ModuleRepair.parse(obj.get("module_repair"));
        return new BoneMaintenanceConfig(useTimeTicks, waitTimeTicks, healPercent, healParts, maxAltitude, moduleRepair);
    }

    /** 模块渐进恢复子配置（方案 v2.1）：设备类概率掷骰 + ERA 数量比例；TRACK 无功能消费点不在缺省白名单。 */
    public static final class ModuleRepair {

        /** 允许维修的模块类型白名单（名字，大小写不敏感）；空 = 除 TRACK 外全部。 */
        @Nullable
        public final List<String> repairableTypes;
        /** ERA 单次维修恢复比例（对已毁数向上取整）。 */
        public final float eraRecoverFraction;
        /** ERA 单次维修至少恢复块数。 */
        public final int eraRecoverMin;
        /** 设备类（非 ERA）每台已毁模块每次维修的独立恢复概率（0~1）。 */
        public final float deviceRecoverChance;

        @Nullable
        private List<BoneModuleType> parsedTypes;

        private ModuleRepair(@Nullable List<String> repairableTypes,
                             float eraRecoverFraction, int eraRecoverMin, float deviceRecoverChance) {
            this.repairableTypes = repairableTypes;
            this.eraRecoverFraction = Float.isFinite(eraRecoverFraction)
                    ? Math.max(0f, Math.min(1f, eraRecoverFraction)) : 0.25f;
            this.eraRecoverMin = Math.max(0, Math.min(64, eraRecoverMin));
            this.deviceRecoverChance = Float.isFinite(deviceRecoverChance) && deviceRecoverChance >= 0f
                    ? Math.min(1f, deviceRecoverChance) : 0.25f;
        }

        /** 缺省策略（未配置 module_repair 时）：设备概率 25% + ERA 25%/至少 1 块。 */
        public static ModuleRepair defaults() {
            return new ModuleRepair(null, 0.25f, 1, 0.25f);
        }

        @Nullable
        public static ModuleRepair parse(@Nullable JsonElement element) {
            if (element == null || !element.isJsonObject()) {
                return null;
            }
            JsonObject obj = element.getAsJsonObject();
            List<String> types = null;
            if (obj.has("repairable_types") && obj.get("repairable_types").isJsonArray()) {
                types = new ArrayList<>();
                for (JsonElement item : obj.getAsJsonArray("repairable_types")) {
                    if (item != null && item.isJsonPrimitive()) {
                        types.add(item.getAsString());
                    }
                }
            }
            return new ModuleRepair(
                    types,
                    GsonHelper.getAsFloat(obj, "era_recover_fraction", 0.25f),
                    GsonHelper.getAsInt(obj, "era_recover_min", 1),
                    GsonHelper.getAsFloat(obj, "device_recover_chance", 0.25f));
        }

        /** 解析后的可维修类型集合（懒加载；空 = 不维修任何模块）。 */
        public List<BoneModuleType> repairableTypesParsed() {
            if (parsedTypes == null) {
                List<BoneModuleType> out = new ArrayList<>();
                if (repairableTypes == null || repairableTypes.isEmpty()) {
                    for (BoneModuleType type : BoneModuleType.values()) {
                        if (type != BoneModuleType.TRACK) {
                            out.add(type);
                        }
                    }
                } else {
                    for (String name : repairableTypes) {
                        BoneModuleType type = BoneModuleType.byName(name);
                        if (type != null && !out.contains(type)) {
                            out.add(type);
                        }
                    }
                }
                parsedTypes = out;
            }
            return parsedTypes;
        }

        /** 指定模块类型是否可经快速维修恢复。 */
        public boolean isRepairable(BoneModuleType type) {
            return type != null && type != BoneModuleType.TRACK && repairableTypesParsed().contains(type);
        }

        @Override
        public String toString() {
            return "ModuleRepair{types=" + repairableTypesParsed() + ", era=" + eraRecoverFraction
                    + "/" + eraRecoverMin + ", device=" + String.format(Locale.ROOT, "%.2f", deviceRecoverChance) + "}";
        }
    }
}
