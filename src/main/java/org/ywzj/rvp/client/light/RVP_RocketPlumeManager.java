package org.ywzj.rvp.client.light;

import net.minecraft.world.phys.Vec3;
import org.ywzj.rvp.light.RVP_RocketPlumeParameters;
import org.ywzj.rvp.light.RVP_LightParameters;
import org.ywzj.rvp.light.RVP_LightRuntimeTuning;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Predicate;

/** 喷焰视觉独立于环境光预算和观察距离；燃烧事实仍由统一发动机查询提供。 */
public final class RVP_RocketPlumeManager {
    /** 客户端唯一实例，服务端不引用。 */
    public static final RVP_RocketPlumeManager INSTANCE = new RVP_RocketPlumeManager();
    /** 每实体一个喷流，稳定 UUID 防止粒子数量决定亮度。 */
    private final Map<UUID, Tracked> entries = new LinkedHashMap<>();
    /** 世界实例身份，重连相同维度也不能复用。 */
    private Object world;
    /** 当前游戏时钟，暂停时不按真实时间推进。 */
    private long gameTime;

    /** 清理换世界、时钟回退和失去心跳的喷流。 */
    public void beginWorld(Object current, long now) {
        if (world != current || now < gameTime) { entries.clear(); world = current; }
        gameTime = now;
        // 调用真实结束边界，独立延长预览光晕时等待其完成，真实发动机仍按两刻心跳失效。
        entries.values().removeIf(entry -> endTime(entry) <= now);
    }

    /** 正常喷流两 tick 无心跳失效；预览最多四十 tick，使用同一形状程序。 */
    public void upsert(UUID key, Vec3 position, Vec3 direction, Float configuredScale,
            float caliber, float strength, boolean preview) {
        if (world == null || key == null || !finite(position) || !finite(direction)
                || !Double.isFinite(direction.lengthSqr()) || direction.lengthSqr() < 1.0E-12
                || !Float.isFinite(strength) || strength <= 0) {
            remove(key); return;
        }
        // 调用统一缩放规则，只复用旧 JSON 的 flame_scale，新增视觉参数全由代码控制。
        float scale = RVP_RocketPlumeParameters.scale(configuredScale);
        float seed = (key.getLeastSignificantBits() & 0xFFFFL) / 65536F;
        RVP_RocketPlume plume = new RVP_RocketPlume(position, direction.normalize(),
                RVP_RocketPlumeParameters.BASE_LENGTH * scale,
                RVP_RocketPlumeParameters.BASE_RADIUS * scale,
                RVP_LightParameters.engineRadius(caliber), Math.min(1F, strength), seed);
        if (!entries.containsKey(key) && entries.size() >= RVP_RocketPlumeParameters.MAX_TRACKED) {
            entries.remove(entries.keySet().iterator().next());
        }
        entries.put(key, new Tracked(plume, gameTime, gameTime + (preview ? 40 : 2), preview));
    }

    /** 只做视锥与独立数量筛选，不受环境光 maxLights/maxDistance/总强度开关控制。 */
    public List<RVP_RocketPlume> collect(Vec3 camera, float partialTick, Predicate<RVP_RocketPlume> visible) {
        double now = gameTime + Math.max(0, Math.min(1, Float.isFinite(partialTick) ? partialTick : 0));
        List<RVP_RocketPlume> result = new ArrayList<>();
        for (Tracked entry : entries.values()) {
            if (endTime(entry) <= now) { continue; }
            float fade = entry.preview ? (float) Math.max(0, 1 - (now - entry.start) / 40.0) : 1;
            // 调用共用光晕曲线；只替换有限预览的光晕包络，真实持续喷焰仍跟随发动机燃烧事实。
            float haloFade = entry.preview ? RVP_LightRuntimeTuning.haloFade(RVP_LightRuntimeTuning.Group.engine,
                    now - entry.start, 40, fade) : 1;
            // 调用独立光晕半径调参，照亮范围改动不会影响已有预览、真实喷焰或几何长宽。
            RVP_RocketPlume base = entry.plume;
            RVP_RocketPlume sample = new RVP_RocketPlume(base.position(), base.direction(), base.length(), base.radius(),
                    RVP_LightRuntimeTuning.haloRadius(RVP_LightRuntimeTuning.Group.engine, base.haloRadius()),
                    base.strength() * fade, base.seed(), base.strength() * haloFade);
            if ((sample.strength() > 0 || sample.haloStrength() > 0) && visible.test(sample)) { result.add(sample); }
        }
        return result.stream().sorted(Comparator.comparingDouble(plume -> plume.center().distanceToSqr(camera)))
                .limit(RVP_RocketPlumeParameters.MAX_VISIBLE).toList();
    }

    /** 燃尽、销毁或远端明确熄火时立即取消，不等待心跳到期。 */
    public void remove(UUID key) { entries.remove(key); }
    /** 供早期 DH 回调决定是否保留当帧深度。 */
    public boolean hasActive() { return RVP_RocketPlumeParameters.ENABLED && !entries.isEmpty(); }
    /** 供状态命令检查环境光与喷焰各自的预算。 */
    public int size() { return entries.size(); }
    /** 登出、切维度和明确 clear 命令清理 CPU 输入。 */
    public void clear() { entries.clear(); world = null; gameTime = 0; }

    /** 预览等待核心和独立光晕均结束；真实喷流继续严格使用燃烧心跳到期时间。 */
    private static double endTime(Tracked entry) {
        // 调用共用光晕寿命，使预览和真实短光采用同样的独立保持/淡出参数。
        return entry.preview ? entry.start + Math.max(40,
                RVP_LightRuntimeTuning.haloDurationTicks(RVP_LightRuntimeTuning.Group.engine, 40)) : entry.expires;
    }

    /** 拒绝 NaN/无穷坐标，避免损坏整屏绘制。 */
    private static boolean finite(Vec3 vector) {
        return vector != null && Double.isFinite(vector.x) && Double.isFinite(vector.y) && Double.isFinite(vector.z);
    }

    /**
     * @param plume 几何与亮度快照
     * @param start 预览开始游戏 tick
     * @param expires 到期游戏 tick
     * @param preview 是否按四十 tick 衰减的命令预览
     */
    private record Tracked(RVP_RocketPlume plume, long start, long expires, boolean preview) { }
}
