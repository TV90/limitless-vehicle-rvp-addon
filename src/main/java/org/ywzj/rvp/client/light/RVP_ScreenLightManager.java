package org.ywzj.rvp.client.light;

import net.minecraft.world.phys.Vec3;
import org.ywzj.rvp.light.RVP_LightParameters;
import org.ywzj.rvp.light.RVP_LightRuntimeTuning;
import org.ywzj.rvp.light.RVP_TransientLightTimeline;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Predicate;

/** 统一短光与持续光；时间由客户端世界提供，测试时不需要启动 Minecraft。 */
public final class RVP_ScreenLightManager {
    /** 全客户端唯一的绘制输入，服务器不加载该类。 */
    public static final RVP_ScreenLightManager INSTANCE = new RVP_ScreenLightManager();
    /** 待筛选光源的内存硬上限，独立于显卡八盏预算。 */
    static final int MAX_TRACKED = 128;
    /** 短光句柄到状态的有序表，过载时先淘汰旧光。 */
    private final Map<Long, Entry> transients = new LinkedHashMap<>();
    /** 发动机每 UUID 一盏；不使用粒子作为键。 */
    private final Map<UUID, Entry> persistent = new LinkedHashMap<>();
    /** 当前世界身份；重连同一维度也必须清理。 */
    private Object world;
    /** 最近逻辑 tick，冻结时不使用系统实时时钟推进。 */
    private long gameTime;
    /** 单调递增取消句柄，避免过期实例取消后来的灯。 */
    private long nextId;

    /** 换世界或时钟回退时完整清理；相同维度的新世界也不复用旧状态。 */
    public void beginWorld(Object currentWorld, long now) {
        if (world != currentWorld || now < gameTime) {
            // 调用统一清理，覆盖退出、换维度、重连与回放倒退。
            clear();
            world = currentWorld;
        }
        gameTime = now;
    }

    /** 登记闪光；同源连射在窗口内只更新和续期，不线性累加亮度。 */
    public long addTransient(Vec3 position, RVP_LightSourceType type, Object sourceKey,
            RVP_LightParameters parameters, long startTime) {
        // 服务端时钟可能略领先客户端：与既有爆炸工厂一致按年龄 0 播放，不能把合法首帧丢掉。
        startTime = Math.min(startTime, gameTime);
        // 调用整盏光源的实际寿命，迟包按当前衰减倍率追赶年龄，不从收到时重新播放。
        if (world == null || !valid(position, parameters)
                || startTime + lifetimeTicks(type, parameters) <= gameTime) { return 0; }
        for (Map.Entry<Long, Entry> tracked : transients.entrySet()) {
            Entry previous = tracked.getValue();
            boolean sameSource = sourceKey != null && Objects.equals(sourceKey, previous.sourceKey);
            boolean nearby = sourceKey == null && previous.sourceKey == null
                    && previous.position.distanceToSqr(position) <= Math.pow(Math.min(2, parameters.radius() * 0.25), 2);
            if (previous.type == type && (sameSource || nearby)
                    && Math.abs(startTime - previous.start) <= parameters.mergeWindowTicks()) {
                previous.position = position;
                previous.start = startTime;
                previous.expires = startTime + parameters.durationTicks();
                previous.parameters = parameters;
                // 换发取消句柄，旧爆炸实例结束时不能取消已经续期给新爆炸的同一盏灯。
                transients.remove(tracked.getKey());
                long renewed = ++nextId;
                transients.put(renewed, previous);
                return renewed;
            }
        }
        // 调用有界淘汰，短时间大量爆炸也不无限申请内存。
        reserveSpace();
        long id = ++nextId;
        transients.put(id, new Entry(position, type, sourceKey, parameters, startTime,
                startTime + parameters.durationTicks()));
        return id;
    }

    /** 更新持续光；两 tick 没有心跳就失效，实体移除与远端超时无需依赖粒子存活。 */
    public void upsertPersistent(UUID key, Vec3 position, RVP_LightParameters parameters) {
        if (world == null || key == null || !valid(position, parameters)) {
            // 调用持续光删除入口，关闭配置立即生效。
            removePersistent(key);
            return;
        }
        if (!persistent.containsKey(key)) { reserveSpace(); }
        persistent.put(key, new Entry(position, RVP_LightSourceType.ROCKET_ENGINE, key,
                parameters, gameTime, gameTime + 2));
    }

    /** 燃尽或移除时立即取消对应发动机光。 */
    public void removePersistent(UUID key) { persistent.remove(key); }

    /** 默认爆炸实例被淘汰时，取消它尚未结束的短光。 */
    public void removeTransient(long handle) { transients.remove(handle); }

    /** 查询指定短光是否仍在淡出；效果包装器不能用旧固定寿命提前截断慢衰减光源。 */
    public boolean hasTransient(long handle) {
        Entry entry = transients.get(handle);
        // 调用统一到期判断；即使本刻清理事件尚未执行，包装器也能读到准确结束状态。
        return entry != null && !entry.finished(gameTime);
    }

    /** 只在逻辑 tick 清理，避免渲染帧数改变闪光寿命。 */
    public void tick(Object currentWorld, long now) {
        // 调用世界检查，使切维度清理先于任何新状态采样。
        beginWorld(currentWorld, now);
        // 调用整盏光源的实际寿命，调慢或调快衰减都会同步改变两路的结束时刻。
        transients.values().removeIf(entry -> entry.finished(now));
        persistent.values().removeIf(entry -> entry.expires <= now);
    }

    /** 按部分 tick 采样并执行预算；没有活动光时返回空列表，调用者无需复制画面。 */
    public List<RVP_ScreenLight> collectVisible(Vec3 camera, float partialTick, double distance,
            int limit, Predicate<RVP_ScreenLight> visible) {
        List<RVP_ScreenLight> sampled = new ArrayList<>(transients.size() + persistent.size());
        double now = gameTime + Math.max(0, Math.min(1, Float.isFinite(partialTick) ? partialTick : 0));
        transients.values().forEach(entry -> sampled.add(entry.sample(now, null)));
        persistent.forEach((key, entry) -> sampled.add(entry.sample(now, key)));
        // 调用统一裁剪，灯的数量与粒子数量完全无关。
        return RVP_ScreenLightBudget.collect(sampled, camera, distance, limit, visible);
    }

    /** 判断本世界是否需要渲染工作。 */
    public boolean hasActiveLights() { return !transients.isEmpty() || !persistent.isEmpty(); }

    /** 供诊断及预算测试读取当前条目数。 */
    public int size() { return transients.size() + persistent.size(); }

    /** 清理 CPU 状态；显存由合成器的独立生命周期释放。 */
    public void clear() { transients.clear(); persistent.clear(); world = null; gameTime = 0; }

    /** 纯校验，不允许损坏坐标进入排序或 Shader。 */
    private static boolean valid(Vec3 position, RVP_LightParameters p) {
        return position != null && Double.isFinite(position.x) && Double.isFinite(position.y)
                && Double.isFinite(position.z) && p != null && p.enabled()
                && Float.isFinite(p.radius()) && p.radius() > 0 && Float.isFinite(p.intensity()) && p.intensity() > 0;
    }

    /** 光源类型只映射到参数分组，不检查任何武器ID。 */
    private static RVP_LightRuntimeTuning.Group group(RVP_LightSourceType type) {
        return switch (type) {
            case EXPLOSION -> RVP_LightRuntimeTuning.Group.explosion;
            case MUZZLE -> RVP_LightRuntimeTuning.Group.muzzle;
            case ROCKET_ENGINE -> RVP_LightRuntimeTuning.Group.engine;
            // 2026-10-31：EO_JAMMER 是本次新加的枚举值，作者群那边漏了这个分支，导致穷尽 switch 编译不过；
            // 干扰机光参数由 RVP_LightParameters.eoJammer() 固定、不走运行时调参，归到总设置分组即可。
            case EO_JAMMER -> RVP_LightRuntimeTuning.Group.global;
        };
    }

    /** 爆炸/炮口按衰减倍率伸缩整盏光源寿命；发动机持续光和预览保留已有时间线。 */
    private static float lifetimeTicks(RVP_LightSourceType type, RVP_LightParameters parameters) {
        if (type == RVP_LightSourceType.ROCKET_ENGINE) { return parameters.durationTicks(); }
        // 调用统一短光寿命规则，读取当前倍率而非出生时快照，既有光源也立即响应。
        return RVP_TransientLightTimeline.durationTicks(parameters.durationTicks(),
                RVP_LightRuntimeTuning.get(group(type), RVP_LightRuntimeTuning.Parameter.decayScale));
    }

    /** 保持总表有界；持续光下一 tick 可重新竞争，不能永久占满短光预算。 */
    private void reserveSpace() {
        while (size() >= MAX_TRACKED) {
            if (!transients.isEmpty()) { transients.remove(transients.keySet().iterator().next()); }
            else { persistent.remove(persistent.keySet().iterator().next()); }
        }
    }

    /** 内部可变状态只由管理器持有，对外始终生成只读快照。 */
    private static final class Entry {
        /** 世界位置。 */
        private Vec3 position;
        /** 来源分类。 */
        private final RVP_LightSourceType type;
        /** 稳定炮口键或空。 */
        private final Object sourceKey;
        /** 安全参数。 */
        private RVP_LightParameters parameters;
        /** 本轮亮起的世界 tick。 */
        private long start;
        /** 持续光的心跳到期tick；短光按当前倍率和出生年龄动态计算实际终点。 */
        private long expires;

        private Entry(Vec3 position, RVP_LightSourceType type, Object sourceKey,
                RVP_LightParameters parameters, long start, long expires) {
            this.position = position; this.type = type; this.sourceKey = sourceKey;
            this.parameters = parameters; this.start = start; this.expires = expires;
        }

        /** 只比较出生年龄，不因调参重设开始时间，也不按渲染帧推进寿命。 */
        private boolean finished(double now) {
            // 调用整盏光源实际寿命，结束或被显式取消后不会自行复活。
            return now - start >= lifetimeTicks(type, parameters);
        }

        /** 爆炸从白热过渡到橙红；持续光只受燃烧事实和预算影响。 */
        private RVP_ScreenLight sample(double now, UUID key) {
            // 调用类型分组映射，照亮与光晕的所有输入由同一原始条目采样。
            RVP_LightRuntimeTuning.Group group = group(type);
            // 调用采样时调参，使已有光源下一帧也能响应；不修改存储的原始预设或重置年龄。
            RVP_LightParameters tuned = RVP_LightRuntimeTuning.apply(group, parameters);
            double age = Math.max(0, now - start);
            // 调用整盏光源寿命，颜色、照亮、光晕和清理都跟随同一个时间进度。
            float duration = lifetimeTicks(type, parameters);
            float progress = (float) Math.max(0, Math.min(1, age / duration));
            float fade = key == null ? (float) Math.pow(1 - progress, tuned.decay()) : 1;
            if (type != RVP_LightSourceType.ROCKET_ENGINE) {
                // 调用整盏短光的平滑包络，照亮和光晕在同一时刻平滑结束。
                fade = RVP_TransientLightTimeline.fade(age, parameters.durationTicks(), parameters.decay(),
                        RVP_LightRuntimeTuning.get(group, RVP_LightRuntimeTuning.Parameter.decayScale));
            }
            float haloIntensity = type == RVP_LightSourceType.EXPLOSION
                    ? RVP_LightRuntimeTuning.get(group, RVP_LightRuntimeTuning.Parameter.worldHaloIntensity) * fade
                    : tuned.intensity() * fade;
            float green = tuned.green();
            float blue = tuned.blue();
            if (type == RVP_LightSourceType.EXPLOSION) {
                green *= 1 - progress * 0.6F;
                blue *= 1 - progress * 0.9F;
            }
            return new RVP_ScreenLight(position, tuned.red(), green, blue, tuned.radius(),
                    tuned.bloom() ? RVP_LightRuntimeTuning.haloRadius(group, parameters.radius()) : 0,
                    key == null && age >= duration ? 0 : tuned.intensity() * fade,
                    tuned.bloom() ? haloIntensity : 0,
                    key == null ? (float) Math.max(0, duration - age) : (float) Math.max(0, expires - now),
                    type, key, tuned.surfaceWeight(),
                    tuned.bloom(), tuned.volumetricStrength());
        }
    }
}
