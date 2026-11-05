package org.ywzj.rvp.client.light;

import net.minecraft.world.phys.Vec3;
import org.ywzj.rvp.light.RVP_RocketPlumeParameters;
import org.ywzj.rvp.light.RVP_LightParameters;
import org.ywzj.rvp.light.RVP_LightRuntimeTuning;
import static org.ywzj.rvp.light.RVP_RocketPlumeRuntimeTuning.Parameter.*;
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
        // 调用无实体归属的预览入口；真实弹体使用带 ownerEntityId 的重载。
        upsert(key, position, direction, configuredScale, caliber, strength, preview, -1);
    }

    /** 记录真实弹体 ID，切入 HITL 的首帧也能过滤上一帧缓存，无需等待两 tick 过期。 */
    public void upsert(UUID key, Vec3 position, Vec3 direction, Float configuredScale,
            float caliber, float strength, boolean preview, int ownerEntityId) {
        // 调用共用登记入口；命令预览和无时间线的诊断保留原有尺寸、衰减与寿命。
        upsert(key, position, direction, configuredScale, caliber, strength, preview, ownerEntityId, Double.NaN);
    }

    /** 正式发动机使用当前燃烧窗口倒计时；负数表示没有有限窗口，只做点火渐入。 */
    public void upsertEngine(UUID key, Vec3 position, Vec3 direction, Float configuredScale,
            float caliber, float strength, int ownerEntityId, int remainingBurnTicks) {
        // 调用共用登记入口，燃尽刻使用整数游戏时钟，不能随同刻渲染次数不断向后推迟。
        upsert(key, position, direction, configuredScale, caliber, strength, false, ownerEntityId,
                remainingBurnTicks < 0 ? Double.POSITIVE_INFINITY : gameTime + (double) remainingBurnTicks);
    }

    /** 本地两秒启停预览使用真实时间线，不需要实体心跳、不发送发射或伤害请求。 */
    public void upsertEnginePreview(UUID key, Vec3 position, Vec3 direction, Float configuredScale, float caliber) {
        // 调用同一登记流程；有限预览到四十刻立即清理，不能延长真实弹体的生命周期。
        upsert(key, position, direction, configuredScale, caliber, 1, true, -1, gameTime + 40.0);
    }

    /** 同 UUID 的逐刻/逐帧续期保留首次点火时刻；明确熄火删除后再次登记自然重播点火过渡。 */
    private void upsert(UUID key, Vec3 position, Vec3 direction, Float configuredScale,
            float caliber, float strength, boolean preview, int ownerEntityId, double burnEnd) {
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
        Tracked previous = entries.get(key);
        long start = !Double.isNaN(burnEnd) && previous != null && !Double.isNaN(previous.burnEnd)
                && previous.expires > gameTime ? previous.start : gameTime;
        entries.put(key, new Tracked(plume, start, gameTime + (preview ? 40 : 2), preview, ownerEntityId, burnEnd));
    }

    /** 只做视锥与独立数量筛选，不受环境光 maxLights/maxDistance/总强度开关控制。 */
    public List<RVP_RocketPlume> collect(Vec3 camera, float partialTick, Predicate<RVP_RocketPlume> visible) {
        double now = gameTime + Math.max(0, Math.min(1, Float.isFinite(partialTick) ? partialTick : 0));
        List<RVP_RocketPlume> result = new ArrayList<>();
        for (Tracked entry : entries.values()) {
            // 调用 ID 屏蔽门，覆盖暂停/切视角首帧以及远端 UUID 不同的同 ID 克隆。
            if (org.ywzj.rvp.client.state.RVP_ClientHitlState.shouldHideActiveMissileVfx(entry.ownerEntityId)) continue;
            if (endTime(entry) <= now) { continue; }
            boolean fadingPreview = entry.preview && Double.isNaN(entry.burnEnd);
            float fade = fadingPreview ? (float) Math.max(0, 1 - (now - entry.start) / 40.0) : 1;
            // 调用共用光晕曲线；只替换有限预览的光晕包络，真实持续喷焰仍跟随发动机燃烧事实。
            float haloFade = fadingPreview ? RVP_LightRuntimeTuning.haloFade(RVP_LightRuntimeTuning.Group.engine,
                    now - entry.start, 40, fade) : 1;
            // 调用正式喷焰启停曲线，使用部分 tick 逐帧过渡；续期不重置点火，暂停不推进年龄。
            float transition = Double.isNaN(entry.burnEnd) ? 1
                    : RVP_RocketPlumeParameters.transition(now - entry.start, entry.burnEnd - now);
            // 调用独立光晕半径调参，照亮范围改动不会影响已有预览、真实喷焰或几何长宽。
            RVP_RocketPlume base = entry.plume;
            // 调用独立长宽、尾轴偏移与启停曲线；在可见性筛选前应用，裁剪范围与实际绘制保持一致。
            RVP_RocketPlume sample = new RVP_RocketPlume(
                    base.position().add(base.direction().scale(AXIAL_OFFSET.value())), base.direction(),
                    base.length() * LENGTH_SCALE.value() * (float) Math.pow(transition, LENGTH_RESPONSE.value()),
                    base.radius() * WIDTH_SCALE.value() * (float) Math.pow(transition, WIDTH_RESPONSE.value()),
                    RVP_LightRuntimeTuning.haloRadius(RVP_LightRuntimeTuning.Group.engine, base.haloRadius()),
                    base.strength() * fade, base.seed(), base.strength() * haloFade,
                    (float) Math.pow(transition, BRIGHTNESS_RESPONSE.value()));
            if (transition > 0 && (sample.strength() > 0 || sample.haloStrength() > 0) && visible.test(sample)) { result.add(sample); }
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

    /** 预览等待核心和独立光晕均结束；正式喷焰不能超过当前燃尽刻或两刻心跳期限。 */
    private static double endTime(Tracked entry) {
        // 调用有限时间线约束启停预览；它不采用旧稳态预览的独立光晕延寿。
        if (!Double.isNaN(entry.burnEnd)) { return Math.min(entry.expires, entry.burnEnd); }
        // 调用共用光晕寿命，使预览和真实短光采用同样的独立保持/淡出参数。
        return entry.preview ? entry.start + Math.max(40,
                RVP_LightRuntimeTuning.haloDurationTicks(RVP_LightRuntimeTuning.Group.engine, 40))
                : entry.expires;
    }

    /** 拒绝 NaN/无穷坐标，避免损坏整屏绘制。 */
    private static boolean finite(Vec3 vector) {
        return vector != null && Double.isFinite(vector.x) && Double.isFinite(vector.y) && Double.isFinite(vector.z);
    }

    /**
     * @param plume 几何与亮度快照
     * @param start 预览开始或本次连续燃烧首次登记的游戏 tick；逐帧续期不得重置
     * @param expires 到期游戏 tick
     * @param preview 是否本地四十tick预览；无时间线时沿用旧降亮，有有限时间线时模拟启停
     * @param ownerEntityId 喷焰所属弹体 ID；无实体归属的预览使用 -1
     * @param burnEnd 当前燃烧窗口的游戏结束刻；NaN 表示原有预览/诊断，正无穷表示无有限燃烧窗口
     */
    private record Tracked(RVP_RocketPlume plume, long start, long expires, boolean preview, int ownerEntityId, double burnEnd) { }
}
