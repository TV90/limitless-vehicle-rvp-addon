package org.ywzj.rvp.client.light;

import net.minecraft.world.phys.Vec3;
import org.ywzj.rvp.weapon.visual.RVP_RocketTrailParameters;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/** 世界空间烟段缓存；不依赖粒子引擎或显卡，导弹移除后旧烟继续按自身寿命消散。 */
public final class RVP_RocketTrailManager {
    /** 客户端唯一缓存，公共弹体只能通过客户端桥调用。 */
    public static final RVP_RocketTrailManager INSTANCE = new RVP_RocketTrailManager();
    /** 同步实体编号到烟段链，解决近距实体与远距克隆身份不同的问题。 */
    private final Map<Integer, Source> sources = new LinkedHashMap<>();
    /** 当前世界的对象身份；重连同一维度也必须清空。 */
    private Object world;
    /** 游戏时钟；暂停和同一 tick 的多帧绘制不推进模拟。 */
    private long gameTime;
    /** 已保存烟段数，所有写入出口共用一个总预算。 */
    private int storedSegments;

    /**
     * @param scale 烟的尺寸倍率，正数
     * @param density 尾迹浓度倍率，零关闭
     * @param hold 保持期，单位 tick，已合并发动机剩余时间和 JSON 保底
     * @param fade 淡出期，单位 tick，已包含稳定的随机增量
     * @param alphaHoldRatio 保持浓度所占总寿命比例，1 表示保持到 hold 结束
     * @param grey 基础灰度，0～1
     * @param spacing 路径分段长度，单位格，不再创建同样数量的粒子
     * @param maxGap 相邻采样允许间隔，单位 tick，超过便断开
     * @param wash 是否为贴地烟浪；径向扩散与空中尾迹各用自己的曲线
     */
    public record Profile(float scale, float density, int hold, int fade, float alphaHoldRatio,
            float grey, double spacing, int maxGap, boolean wash) { }

    /** 更新世界与时钟；只在新 tick 做过期清理，避免高帧率重复遍历全部烟段。 */
    public void beginWorld(Object current, long now) {
        if (world != current || now < gameTime) {
            sources.clear(); storedSegments = 0; world = current;
        } else if (now == gameTime) { return; }
        gameTime = now;
        Iterator<Source> iterator = sources.values().iterator();
        while (iterator.hasNext()) {
            Source source = iterator.next();
            int before = source.segments.size();
            source.segments.removeIf(segment -> expired(segment.start, now) && expired(segment.end, now));
            storedSegments -= before - source.segments.size();
            if (source.segments.isEmpty() && now - source.lastTick > 2) { iterator.remove(); }
        }
    }

    /** 每来源每 tick 最多更新一次；本地实体优先于同编号的远端克隆。 */
    public void append(int id, Vec3 position, Vec3 exhaust, Profile profile, boolean remote) {
        if (world == null || !finite(position) || !finite(exhaust) || !valid(profile)) { return; }
        // 调用来源预算入口，不按武器 ID 创建专属逻辑。
        Source source = source(id);
        if (remote && source.lastLocalTick == gameTime) { return; }
        if (!remote) { source.lastLocalTick = gameTime; }
        if (source.lastTick == gameTime) { return; }
        Node next = new Node(position, exhaust, gameTime, profile);
        Node previous = source.head;
        boolean connect = previous != null && gameTime - source.lastTick <= profile.maxGap
                && previous.position.distanceToSqr(position) <= Math.pow(RVP_RocketTrailParameters.MAX_JOIN_DISTANCE, 2);
        if (!connect) {
            // 调用有界烟段写入，为首次出现、断包和传送后的当前位置播种，不连接旧位置。
            store(source, new Segment(next, next));
        } else {
            double distance = previous.position.distanceTo(position);
            int pieces = Math.max(1, (int) Math.ceil(distance / Math.max(1,
                    Math.min(RVP_RocketTrailParameters.MAX_SEGMENT_LENGTH, profile.spacing))));
            pieces = Math.min(pieces, RVP_RocketTrailParameters.MAX_SEGMENTS_PER_UPDATE);
            Node start = previous;
            for (int index = 1; index <= pieces; index++) {
                double fraction = index / (double) pieces;
                Node end = index == pieces ? next : new Node(previous.position.lerp(position, fraction),
                        previous.velocity.lerp(exhaust, fraction),
                        previous.born + (gameTime - previous.born) * fraction, profile);
                // 调用有界写入，连续分段共享端点，保留真实转弯而不是拉直整条烟。
                store(source, new Segment(start, end));
                start = end;
            }
        }
        source.head = next; source.lastTick = gameTime;
    }

    /** 发射烟浪是独立世界烟团；不会把径向喷流连接成跨地面的长烟线。 */
    public void addWash(int id, Vec3 position, Vec3 velocity, Profile profile) {
        if (world == null || !finite(position) || !finite(velocity) || !valid(profile)) { return; }
        Source source = source(id);
        if (source.lastWashTick != gameTime) { source.lastWashTick = gameTime; source.washCount = 0; }
        if (source.washCount >= RVP_RocketTrailParameters.WASH_PUFFS_PER_TICK) { return; }
        source.washCount++;
        Node puff = new Node(position, velocity, gameTime, profile);
        // 调用同一有界存储和后续体积绘制，完全不创建旧 WASH 粒子。
        store(source, new Segment(puff, puff));
    }

    /** 移除弹体只断开后续路径，已排出的烟不能跟着导弹消失。 */
    public void stop(int id) {
        Source source = sources.get(id);
        if (source != null) { source.head = null; }
    }

    /** 世界烟段先做可见性筛选和远距直线合并，再公平分配来源预算；不按环境光距离截断。 */
    public List<RVP_RocketTrailSegment> collect(Vec3 camera, float partialTick,
            Predicate<RVP_RocketTrailSegment> visible) {
        if (!finite(camera)) { return List.of(); }
        double now = gameTime + (Float.isFinite(partialTick) ? Math.max(0, Math.min(1, partialTick)) : 0);
        List<ArrayDeque<RVP_RocketTrailSegment>> groups = new ArrayList<>();
        for (Map.Entry<Integer, Source> entry : sources.entrySet()) {
            List<RVP_RocketTrailSegment> candidates = new ArrayList<>();
            RVP_RocketTrailSegment pending = null;
            for (Segment segment : entry.getValue().segments) {
                // 调用两端各自的寿命/漂移计算；摄像机转动和弹体当前朝向都不改变历史位置。
                RVP_RocketTrailSegment sample = sample(entry.getKey(), segment, now);
                if (sample == null) { continue; }
                if (pending != null && mergeable(pending, sample, camera)) {
                    pending = new RVP_RocketTrailSegment(sample.source(), pending.start(), sample.end(),
                            pending.startRadius(), sample.endRadius(), pending.startDensity(), sample.endDensity(),
                            (pending.age() + sample.age()) * 0.5F, sample.seed(), sample.grey(), sample.noiseScale());
                } else {
                    if (pending != null && visible.test(pending)) { candidates.add(pending); }
                    pending = sample;
                }
            }
            if (pending != null && visible.test(pending)) { candidates.add(pending); }
            candidates.sort(Comparator.comparingDouble(segment -> segment.center().distanceToSqr(camera)));
            if (!candidates.isEmpty()) { groups.add(new ArrayDeque<>(candidates)); }
        }
        groups.sort(Comparator.comparingDouble(group -> group.peekFirst().center().distanceToSqr(camera)));
        List<RVP_RocketTrailSegment> result = new ArrayList<>();
        boolean added = true;
        while (added && result.size() < RVP_RocketTrailParameters.MAX_VISIBLE_SEGMENTS) {
            added = false;
            for (ArrayDeque<RVP_RocketTrailSegment> group : groups) {
                if (!group.isEmpty()) { result.add(group.removeFirst()); added = true; }
                if (result.size() == RVP_RocketTrailParameters.MAX_VISIBLE_SEGMENTS) { break; }
            }
        }
        // 半透明烟从远到近合成；灰度相近的相交烟段使用此有界近似，不写世界深度。
        result.sort(Comparator.comparingDouble((RVP_RocketTrailSegment segment) ->
                segment.center().distanceToSqr(camera)).reversed());
        return result;
    }

    /** 是否有存活烟段，供 DH 的早期深度回调使用；不依赖仍有发动机或活动灯。 */
    public boolean hasActive() { return RVP_RocketTrailParameters.VOLUME_ENABLED && storedSegments > 0; }
    /** 当前缓存烟段数，供调试命令和预算测试回读。 */
    public int size() { return storedSegments; }
    /** 登出、换维度或显式清理时释放世界引用。 */
    public void clear() { sources.clear(); storedSegments = 0; world = null; gameTime = 0; }

    /** 创建来源并限制总数，必要时淘汰最早登记的来源。 */
    private Source source(int id) {
        Source result = sources.get(id);
        if (result != null) { return result; }
        if (sources.size() >= RVP_RocketTrailParameters.MAX_SOURCES) {
            Source oldest = sources.remove(sources.keySet().iterator().next());
            storedSegments -= oldest.segments.size();
        }
        result = new Source(); sources.put(id, result); return result;
    }

    /** 每次写入都同时维护单来源和全局上限，不产生无界补点队列。 */
    private void store(Source source, Segment segment) {
        if (source.segments.size() >= RVP_RocketTrailParameters.MAX_SEGMENTS_PER_SOURCE) {
            source.segments.removeFirst(); storedSegments--;
        }
        while (storedSegments >= RVP_RocketTrailParameters.MAX_STORED_SEGMENTS) {
            Source largest = sources.values().stream().max(Comparator.comparingInt(value -> value.segments.size())).orElseThrow();
            largest.segments.removeFirst(); storedSegments--;
        }
        source.segments.addLast(segment); storedSegments++;
    }

    /** 两端独立恢复世界烟的位置和透明度；远距也保留小于一像素的真实世界几何。 */
    private static RVP_RocketTrailSegment sample(int id, Segment segment, double now) {
        float firstAge = (float) Math.max(0, now - segment.start.born);
        float lastAge = (float) Math.max(0, now - segment.end.born);
        float firstDensity = density(segment.start, firstAge);
        float lastDensity = density(segment.end, lastAge);
        if (firstDensity <= 0 && lastDensity <= 0) { return null; }
        float seed = (id * 0x9E3779B9 & 0xFFFF) / 65536F;
        Profile profile = segment.end.profile;
        return new RVP_RocketTrailSegment(id, drift(segment.start, firstAge, seed), drift(segment.end, lastAge, seed),
                radius(segment.start.profile, firstAge), radius(profile, lastAge), firstDensity, lastDensity,
                (firstAge + lastAge) * 0.5F, seed,
                Math.min(0.90F, profile.grey + (firstAge + lastAge) * 0.0007F), 0.7F / profile.scale);
    }

    /** 连续低频漂移来自出生速度和世界时钟，不受当前导弹位置或帧率影响。 */
    private static Vec3 drift(Node node, float age, float seed) {
        boolean wash = node.profile.wash;
        double drag = (1 - Math.pow(wash ? 0.96 : 0.91, age)) / (wash ? 0.04 : 0.09);
        double wind = age * 0.006 * Math.max(0.25, node.profile.scale);
        return node.position.add(node.velocity.scale(drag * (wash ? 1 : 0.15)))
                .add(Math.cos(seed * Math.PI * 2) * wind,
                        age * (wash ? 0.006 : 0.004), Math.sin(seed * Math.PI * 2) * wind);
    }

    /** 半径随年龄平滑增长，旧烟更松散；上限随既有 particle_scale 一起缩放。 */
    private static float radius(Profile profile, float age) {
        return profile.scale * (profile.wash ? 0.3F + 2.7F * Math.min(1, age / 80)
                : 0.45F + 1.6F * (1 - (float) Math.exp(-age / 100)));
    }

    /** 保持期和 alpha 比例沿用旧语义，淡出使用平滑曲线，避免整条烟突然消失。 */
    private static float density(Node node, float age) {
        Profile profile = node.profile;
        float lifetime = Math.max(1, profile.hold + profile.fade);
        if (age >= lifetime) { return 0; }
        float hold = profile.alphaHoldRatio >= 1 ? profile.hold
                : Math.max(1, Math.min(profile.hold, Math.round(lifetime * profile.alphaHoldRatio)));
        float fade = Math.max(0, Math.min(1, (age - hold) / Math.max(1, lifetime - hold)));
        return profile.density * (1 - fade * fade * (3 - 2 * fade));
    }

    /** 只合并远处几乎共线、相接且属性接近的烟段，弯道与断包处绝不拉直。 */
    private static boolean mergeable(RVP_RocketTrailSegment first, RVP_RocketTrailSegment second, Vec3 camera) {
        if (first.end().distanceToSqr(second.start()) > 0.0001
                || first.center().distanceToSqr(camera) < 256 * 256
                || first.start().distanceToSqr(second.end()) > 96 * 96
                || Math.abs(first.endRadius() - second.startRadius()) > 0.05F
                || Math.abs(first.endDensity() - second.startDensity()) > 0.05F) { return false; }
        Vec3 a = first.end().subtract(first.start());
        Vec3 b = second.end().subtract(second.start());
        return a.lengthSqr() > 0.00001 && b.lengthSqr() > 0.00001
                && a.normalize().dot(b.normalize()) > 0.998;
    }

    /** 过期比较使用自己的出生时钟，不查询仍然存在的导弹。 */
    private static boolean expired(Node node, double now) { return now - node.born >= Math.max(1, node.profile.hold + node.profile.fade); }
    /** 拒绝非有限坐标，防止异常快照污染整屏。 */
    private static boolean finite(Vec3 value) {
        return value != null && Double.isFinite(value.x) && Double.isFinite(value.y) && Double.isFinite(value.z);
    }
    /** 数据已经由配置出口解析，这里仍对浮点数和硬上限做最后防护。 */
    private static boolean valid(Profile value) {
        return value != null && Float.isFinite(value.scale) && value.scale > 0 && value.scale <= 64
                && Float.isFinite(value.density) && value.density > 0 && value.density <= 16
                && Float.isFinite(value.alphaHoldRatio) && value.alphaHoldRatio >= 0 && value.alphaHoldRatio <= 1
                && Float.isFinite(value.grey) && value.grey >= 0 && value.grey <= 1
                && Double.isFinite(value.spacing) && value.spacing > 0 && value.maxGap > 0
                && value.hold >= 0 && value.hold <= 12000 && value.fade >= 0 && value.fade <= 12000;
    }

    /** 单来源烟段链；头节点仅用于下一次连接，与已排出烟的生命周期分离。 */
    private static final class Source {
        /** 按生成先后保存的世界烟段。 */
        private final ArrayDeque<Segment> segments = new ArrayDeque<>();
        /** 最新空中尾迹节点，贴地烟浪不更新它。 */
        private Node head;
        /** 上一次空中尾迹或烟浪的生成 tick。 */
        private long lastTick = Long.MIN_VALUE / 2;
        /** 最近一次本地实体更新 tick，远距克隆不能重复登记。 */
        private long lastLocalTick = Long.MIN_VALUE / 2;
        /** 贴地烟浪自己的去重时钟，不得阻止同 tick 的空中尾迹登记。 */
        private long lastWashTick = Long.MIN_VALUE / 2;
        /** 当前 tick 已生成烟浪团数，防止同帧重复调用放大密度。 */
        private int washCount;
    }

    /** @param position 出生世界坐标 @param velocity 出生尾喷或烟浪速度 @param born 出生游戏 tick @param profile 出生时配置快照 */
    private record Node(Vec3 position, Vec3 velocity, double born, Profile profile) { }
    /** @param start 两端共享的起点节点 @param end 终点节点；独立烟团两者相同 */
    private record Segment(Node start, Node end) { }
}
