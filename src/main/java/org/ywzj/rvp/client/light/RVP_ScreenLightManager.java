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
import java.util.function.BiConsumer;
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
                startTime + parameters.durationTicks(), null));
        return id;
    }

    /** 更新持续光；两 tick 没有心跳就失效，实体移除与远端超时无需依赖粒子存活。 */
    public void upsertPersistent(UUID key, Vec3 position, RVP_LightParameters parameters) {
        // 调用类型化持续光入口，原发动机调用继续使用全向光。
        upsertPersistent(key, position, RVP_LightSourceType.ROCKET_ENGINE, parameters, null);
    }

    /** 持续光保留来源和半球轴；干扰机不再被误归为发动机，轴为 null 时保持全向光。 */
    public void upsertPersistent(UUID key, Vec3 position, RVP_LightSourceType type,
                                 RVP_LightParameters parameters, Vec3 hemisphereAxis) {
        if (type != RVP_LightSourceType.ROCKET_ENGINE && type != RVP_LightSourceType.EO_JAMMER) {
            throw new IllegalArgumentException("持续光只支持发动机或光电干扰机");
        }
        if (hemisphereAxis != null && (!Double.isFinite(hemisphereAxis.lengthSqr()) || hemisphereAxis.lengthSqr() < 1.0E-12)) {
            throw new IllegalArgumentException("半球光方向必须是非零有限向量");
        }
        if (world == null || key == null || !valid(position, parameters)) {
            // 调用持续光删除入口，关闭配置立即生效。
            removePersistent(key);
            return;
        }
        if (!persistent.containsKey(key)) { reserveSpace(); }
        persistent.put(key, new Entry(position, type, key, parameters, gameTime, gameTime + 2,
                hemisphereAxis == null ? null : hemisphereAxis.normalize()));
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
        // 调用两路最晚结束时刻，独立延长的光晕不会随照亮提前被清理。
        transients.values().removeIf(entry -> entry.finished(now));
        persistent.values().removeIf(entry -> entry.expires <= now);
    }

    /** 按部分 tick 采样并执行预算；没有活动光时返回空列表，调用者无需复制画面。 */
    public List<RVP_ScreenLight> collectVisible(Vec3 camera, float partialTick, double distance,
            int limit, Predicate<RVP_ScreenLight> visible) {
        // 正式绘制不传诊断回调，保持原有开销与行为。
        return collectVisible(camera, partialTick, distance, limit, visible, null);
    }

    /**
     * 采样并执行预算，同时把被剔除的候选与原因交给诊断回调。
     *
     * @param camera 相机位置
     * @param partialTick 部分刻
     * @param distance 客户端距离预算
     * @param limit 本帧灯数上限
     * @param visible 视锥可见性判断
     * @param diagnostics 剔除回调，null 表示不诊断
     */
    public List<RVP_ScreenLight> collectVisible(Vec3 camera, float partialTick, double distance,
            int limit, Predicate<RVP_ScreenLight> visible,
            BiConsumer<RVP_ScreenLight, String> diagnostics) {
        List<RVP_ScreenLight> sampled = new ArrayList<>(transients.size() + persistent.size());
        double now = gameTime + Math.max(0, Math.min(1, Float.isFinite(partialTick) ? partialTick : 0));
        transients.values().forEach(entry -> sampled.add(entry.sample(now, null)));
        persistent.forEach((key, entry) -> sampled.add(entry.sample(now, key)));
        // 调用天空专用距离预算；0 表示天空沿用普通预算，不影响地面照亮、光晕与核心。
        double skyDistance = RVP_LightRuntimeTuning.get(RVP_LightRuntimeTuning.Group.global,
                RVP_LightRuntimeTuning.Parameter.skyDistance);
        // 调用统一裁剪，灯的数量与粒子数量完全无关；诊断只影响是否回调，不改变判定顺序。
        return RVP_ScreenLightBudget.collect(sampled, camera, distance, skyDistance, limit, visible, diagnostics);
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
            // 干扰机直接使用eoJammer()预设；global没有颜色/半径参数，套用会生成NaN并被光源预算剔除。
            // 返回null，由采样入口保留独立预设；总画质倍率仍由绘制阶段统一应用。
            case EO_JAMMER -> null;
        };
    }

    /** 爆炸/炮口照亮按衰减倍率伸缩；光晕的独立时长不会反向改变这里。 */
    private static float lightingLifetimeTicks(RVP_LightSourceType type, RVP_LightParameters parameters) {
        if (type == RVP_LightSourceType.ROCKET_ENGINE || type == RVP_LightSourceType.EO_JAMMER) { return parameters.durationTicks(); }
        // 调用统一短光寿命规则，读取当前倍率而非出生时快照，既有光源也立即响应。
        return RVP_TransientLightTimeline.durationTicks(parameters.durationTicks(),
                RVP_LightRuntimeTuning.get(group(type), RVP_LightRuntimeTuning.Parameter.decayScale));
    }

    /** 短光等待照亮、光晕与天空闪光都结束才清理；任一路调长都不会被其它路提前取消。 */
    private static float lifetimeTicks(RVP_LightSourceType type, RVP_LightParameters parameters) {
        // 调用两路各自的寿命，保留其中结束较晚的一路；不改变照亮的真实采样进度。
        float lighting = lightingLifetimeTicks(type, parameters);
        float halo = RVP_LightRuntimeTuning.haloDurationTicks(group(type), lighting);
        // 调用天空自身的时间线（保持+淡出），天空可以比照亮和光晕都活得更久。
        float sky = RVP_LightRuntimeTuning.skyDurationTicks(group(type));
        return Math.max(lighting, Math.max(halo, sky));
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
        /** 世界空间的半球光轴；null 表示不限制方向。 */
        private final Vec3 hemisphereAxis;

        private Entry(Vec3 position, RVP_LightSourceType type, Object sourceKey,
                RVP_LightParameters parameters, long start, long expires, Vec3 hemisphereAxis) {
            this.position = position; this.type = type; this.sourceKey = sourceKey;
            this.parameters = parameters; this.start = start; this.expires = expires;
            this.hemisphereAxis = hemisphereAxis;
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
            RVP_LightParameters tuned = group == null ? parameters : RVP_LightRuntimeTuning.apply(group, parameters);
            double age = Math.max(0, now - start);
            // 调用照亮原寿命；光晕可以单独延长，不能用总条目寿命拖慢环境照亮或颜色变化。
            float duration = lightingLifetimeTicks(type, parameters);
            float progress = (float) Math.max(0, Math.min(1, age / duration));
            float fade = key == null ? (float) Math.pow(1 - progress, tuned.decay()) : 1;
            if (type == RVP_LightSourceType.EXPLOSION || type == RVP_LightSourceType.MUZZLE) {
                // 调用原有照亮包络；光晕可另选亮度曲线，但仍使用同一寿命终点。
                fade = RVP_TransientLightTimeline.fade(age, parameters.durationTicks(), parameters.decay(),
                        RVP_LightRuntimeTuning.get(group, RVP_LightRuntimeTuning.Parameter.decayScale));
            }
            // 调用独立光晕曲线；持续光没有固定结束时间，保持原有燃烧/开关包络，短光按原始年龄采样。
            float haloFade = key == null ? RVP_LightRuntimeTuning.haloFade(group, age, duration, fade) : fade;
            float haloIntensity = (type == RVP_LightSourceType.EXPLOSION
                    ? RVP_LightRuntimeTuning.get(group, RVP_LightRuntimeTuning.Parameter.worldHaloIntensity)
                    : tuned.intensity()) * haloFade;
            float green = tuned.green();
            float blue = tuned.blue();
            if (type == RVP_LightSourceType.EXPLOSION) {
                green *= 1 - progress * 0.6F;
                blue *= 1 - progress * 0.9F;
            }
            // 调用静态光晕范围并叠加随淡出收缩的倍率；只缩光晕半径，照亮半径仍用 tuned.radius()。
            // 持续光与干扰机的光晕半径为常量或独立预设，收缩倍率为1，不出现收缩或NaN。
            float haloRadius = tuned.bloom()
                    ? (group == null ? parameters.radius()
                    : RVP_LightRuntimeTuning.sampledHaloRadius(group, parameters.radius(), haloFade)) : 0;
            // 调用天空峰值亮度与收拢幅度；使用天空自己的保持/淡出刻数，与光晕曲线完全独立。
            float skyIntensity = key == null ? RVP_LightRuntimeTuning.skyIntensity(group, age) : 0;
            float skySpread = RVP_LightRuntimeTuning.skySpread(group, age);
            // 调用圆形白热核心：爆炸专用，尺寸随爆炸当量与寿命扩张、亮度跟随光晕淡出；其它来源为 null。
            RVP_LightParameters.EngineEmission emission =
                    type == RVP_LightSourceType.EXPLOSION && key == null
                            ? RVP_LightRuntimeTuning.coreEmission(group, parameters.radius(), progress, haloFade) : null;
            return new RVP_ScreenLight(position, tuned.red(), green, blue, tuned.radius(), haloRadius,
                    key == null && age >= duration ? 0 : tuned.intensity() * fade,
                    tuned.bloom() ? haloIntensity : 0, skyIntensity, skySpread, emission,
                    key == null ? (float) Math.max(0, lifetimeTicks(type, parameters) - age) : (float) Math.max(0, expires - now),
                    type, key, tuned.surfaceWeight(),
                    tuned.bloom(), tuned.volumetricStrength(), hemisphereAxis);
        }
    }
}
