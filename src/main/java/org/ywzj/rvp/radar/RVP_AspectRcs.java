package org.ywzj.rvp.radar;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;
import org.ywzj.vehicle.vehicle.part.PartUnit;
import org.ywzj.vehicle.vehicle.part.WeaponBayUnit;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * [RVP] 分角度 RCS 雷达隐身（借鉴 MCH-Reforged 的 front/side/rear 三档模型，2026-09-16）。
 *
 * <p>载具 JSON 新增两个 RVP 扩展字段（均缺省 = 行为与现状一致）：</p>
 * <ul>
 *   <li>顶层 {@code rvp_radar_rcs_factor: [front, side, rear]}：分角度因子。方位角 =
 *       载具 yRot 前向单位向量与"载具→观察者"水平连线向量的夹角，段内（0~90°、90~180°）
 *       按 sin³ 缓动插值（左右对称、无俯仰维度），结果钳 [0.01,10]；</li>
 *   <li>弹舱部件条目 {@code open_radar_rcs_multiplier}：该弹舱<b>开启时</b>的 RCS 增幅倍率
 *       （逐弹舱独立，如隐身化侧弹舱小、主弹舱大），取所有开启弹舱倍率的连乘。</li>
 * </ul>
 *
 * <p>{@link #combinedFactor} = 分角度因子 × 弹舱增幅连乘，消费方将原距离上限乘以该值：
 * 客户端雷达探测表后过滤（mechanical/phase 通用）与 ARH 主动雷达导引头获取距离
 * （仅 {@code scanRadarTarget} 雷达分支，AIR 主动红外不受影响）。本体
 * {@code physics_info.radar_cross_section} 继续独立乘算，双栈并存。</p>
 *
 * <p>解析挂 {@code VehicleDataManagerMixin} 数据包 apply 链尾（双端各自执行），存储用
 * {@link ConcurrentHashMap}——单机下服务端/客户端线程都会读写本缓存。</p>
 */
public final class RVP_AspectRcs {

    /** 单载具剖面：三档分角度因子 + 弹舱开启增幅表（弹舱部件 id → 倍率）。 */
    private record Profile(float front, float side, float rear, Map<String, Float> bayMultipliers) {}

    private static final Map<ResourceLocation, Profile> PROFILES = new ConcurrentHashMap<>();

    /**
     * 段内插值曲线强度：过渡进度 v = sin^(2×CURVE_POWER)(u·π/2)。
     * 1 = sin²（接近线性）、1.5 = sin³（当前，2026-09-16 用户定版：±30° 隐身保留 ~88%、
     * ±45° 保留 ~65%、接近正侧方快速暴露）、2 = sin⁴（前段极平）。
     */
    private static final double CURVE_POWER = 1.5;

    private RVP_AspectRcs() {
    }

    /** 数据包重载前清空缓存（与其它 RVP 缓存同款：apply 全量重建）。 */
    public static void clear() {
        PROFILES.clear();
    }

    /**
     * 从单载具 JSON 解析剖面：顶层 {@code rvp_radar_rcs_factor} 数组 + parts 内
     * {@code type == ywzj_vehicle:weapon_bay} 条目的 {@code open_radar_rcs_multiplier}。
     * 两者都未配置时不入缓存（combined 恒 1，零开销）。
     */
    public static void parse(ResourceLocation vehicleId, JsonObject obj) {
        float front = 1.0F;
        float side = 1.0F;
        float rear = 1.0F;
        boolean hasAspect = false;
        if (obj.has("rvp_radar_rcs_factor") && obj.get("rvp_radar_rcs_factor").isJsonArray()) {
            JsonArray arr = obj.getAsJsonArray("rvp_radar_rcs_factor");
            if (arr.size() >= 3) {
                front = Mth.clamp(arr.get(0).getAsFloat(), 0.01F, 10.0F);
                side = Mth.clamp(arr.get(1).getAsFloat(), 0.01F, 10.0F);
                rear = Mth.clamp(arr.get(2).getAsFloat(), 0.01F, 10.0F);
                hasAspect = true;
            }
        }
        Map<String, Float> bayMultipliers = new HashMap<>();
        if (obj.has("parts") && obj.get("parts").isJsonArray()) {
            for (JsonElement pe : obj.getAsJsonArray("parts")) {
                if (!pe.isJsonObject()) {
                    continue;
                }
                JsonObject po = pe.getAsJsonObject();
                String type = po.has("type") && po.get("type").isJsonPrimitive()
                        ? po.get("type").getAsString() : "";
                if (!"ywzj_vehicle:weapon_bay".equals(type)) {
                    continue;
                }
                String bayId = po.has("id") && po.get("id").isJsonPrimitive()
                        ? po.get("id").getAsString().trim() : "";
                if (bayId.isEmpty() || !po.has("open_radar_rcs_multiplier")) {
                    continue;
                }
                float multiplier = Mth.clamp(po.get("open_radar_rcs_multiplier").getAsFloat(), 0.01F, 100.0F);
                bayMultipliers.put(bayId, multiplier);
            }
        }
        if (!hasAspect && bayMultipliers.isEmpty()) {
            return;
        }
        PROFILES.put(vehicleId, new Profile(
                hasAspect ? front : 1.0F,
                hasAspect ? side : 1.0F,
                hasAspect ? rear : 1.0F,
                bayMultipliers));
    }

    /**
     * 综合隐身因子 = 分角度因子（front/side/rear 按方位插值）× 开启弹舱增幅连乘。
     * 未配置剖面的载具返回 1.0。
     *
     * @param vehicle     被观察的载具
     * @param observerPos 观察者位置（雷达站/导弹弹体）
     */
    public static double combinedFactor(AbstractVehicle vehicle, Vec3 observerPos) {
        if (vehicle == null || observerPos == null) {
            return 1.0D;
        }
        Profile profile = PROFILES.get(vehicle.getVehicleId());
        if (profile == null) {
            return 1.0D;
        }
        // 方位角：载具 yRot 前向单位向量（-sin(yaw), cos(yaw)）与"载具→观察者"水平连线向量的夹角
        Vec3 toObserver = observerPos.subtract(vehicle.position());
        double nx = toObserver.x;
        double nz = toObserver.z;
        double len = Math.sqrt(nx * nx + nz * nz);
        if (len <= 1.0E-6D) {
            return 1.0D;
        }
        nx /= len;
        nz /= len;
        float yawRad = vehicle.getYRot() * Mth.DEG_TO_RAD;
        double fx = -Mth.sin(yawRad);
        double fz = Mth.cos(yawRad);
        double dot = Mth.clamp(fx * nx + fz * nz, -1.0D, 1.0D);
        double aspectDeg = Math.toDegrees(Math.acos(dot));
        // 目的：段内插值用 sin³ 缓动（CURVE_POWER=1.5）替代线性——正面 ±30° 锥内隐身
        // 几乎不衰减（保留 ~88%）、±45° 保留 ~65%，接近正侧方才快速暴露（线性在 30°
        // 就丢掉 1/3 隐身收益，2026-09-16 用户反馈衰减过快）。
        double segmentProgress = Mth.clamp(
                (aspectDeg <= 90.0D ? aspectDeg : aspectDeg - 90.0D) / 90.0D, 0.0D, 1.0D);
        double eased = Math.pow(Math.sin(segmentProgress * Math.PI / 2.0D), 2.0D * CURVE_POWER);
        double aspect;
        if (aspectDeg <= 90.0D) {
            aspect = profile.front() + (profile.side() - profile.front()) * eased;
        } else {
            aspect = profile.side() + (profile.rear() - profile.side()) * eased;
        }
        // 弹舱开启增幅：所有开启弹舱倍率连乘（isOn 为同步数据，双端一致）
        double bayFactor = 1.0D;
        for (PartUnit<?> partUnit : vehicle.getPartUnits()) {
            if (partUnit instanceof WeaponBayUnit bayUnit && bayUnit.isOn()) {
                Float multiplier = profile.bayMultipliers().get(bayUnit.getId());
                if (multiplier != null) {
                    bayFactor *= multiplier;
                }
            }
        }
        // 目的：连乘后封顶 1（2026-09-16 用户定版）——弹舱开启只能吃掉隐身裕度、
        // 让 RCS 回到"不隐身"基准，不会比不隐身更显眼。
        return Mth.clamp(aspect * bayFactor, 0.01D, 1.0D);
    }

    /** 供外部判断载具是否配置了剖面（未配置 = 无隐身行为）。 */
    public static boolean hasProfile(AbstractVehicle vehicle) {
        return vehicle != null && PROFILES.containsKey(vehicle.getVehicleId());
    }
}
