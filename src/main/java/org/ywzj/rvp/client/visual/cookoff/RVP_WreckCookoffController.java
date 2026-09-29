package org.ywzj.rvp.client.visual.cookoff;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.phys.Vec3;
import org.ywzj.rvp.client.particle.RVP_WreckSparkParticle;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;
import org.ywzj.vehicle.entity.vehicle.TrackedVehicle;
import org.ywzj.vehicle.entity.vehicle.WheeledVehicle;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.ywzj.rvp.client.visual.cookoff.RVP_WreckCookoffResolver.*;

/** 地面载具持续殉燃；由击毁烟的既有遍历驱动，不增加全世界实体遍历。 */
public final class RVP_WreckCookoffController {

    /** 当前世界绑定，换维度时清理所有实体引用。 */
    private static ClientLevel currentLevel;
    /** 上次已处理的世界 tick，避免暂停或重复事件生成粒子。 */
    private static long lastTick = Long.MIN_VALUE;
    /** 以 UUID 而非可复用的数字 ID 区分残骸。 */
    private static final Map<UUID, Instance> INSTANCES = new HashMap<>();
    /** 本 tick 按距离排序后的实例，渲染和预算复用。 */
    private static final List<Instance> ORDERED = new ArrayList<>();
    /** 预览车辆 UUID；仅客户端模拟视觉，不伤害真实载具。 */
    private static UUID preview;
    /** 预览自动结束时间，单位客户端世界 tick。 */
    private static long previewEnd;
    /** 最近一 tick 尝试发射的原版粒子数，供调试显示。 */
    private static int emitted;

    /** 一个出口前后两帧的世界姿态。 */
    public static final class Column {
        /** 绑定的只读部件/OBB。 */
        public final Anchor anchor;
        /** 上一 tick 姿态；首次出现时等于当前姿态。 */
        public Pose previous;
        /** 当前 tick 姿态；部件无效时为 null。 */
        public Pose current;
        /** 出口相位，防止同一车辆全部火焰同步闪动。 */
        public final double phase;
        /** 本出口当前使用的采样种子；换采样点时跳变。 */
        private int sampleSeed;
        /** 本出口当前生命周期尺寸倍率；0 为刚开始或结束，1 为完整尺寸。 */
        private double lifecycleScale;

        private Column(Anchor anchor, double phase, long tick) {
            this.anchor = anchor;
            this.phase = phase;
            this.sampleSeed = samplingSeed(tick, anchor.sampleIndex());
        }

        /**
         * 返回"本 tick 换到了另一处采样点/方向"，用于让渲染跳变而不是滑过去。
         * 同组各点的相位由 {@code sampleIndex} 错开，因此不会同时换点。
         */
        private boolean reseed(long tick) {
            int seed = samplingSeed(tick, this.anchor.sampleIndex());
            if (seed == this.sampleSeed) {
                return false;
            }
            this.sampleSeed = seed;
            return true;
        }
    }

    /** 炮口火柱的前后两帧姿态；炮口不参与火星，单独维护。 */
    private static final class MuzzleColumn {
        /** 炮口绑定。 */
        private final MuzzleAnchor muzzle;
        /** 出口相位，与舱盖/接缝一样按车辆与序号错开，避免全车同步闪动。 */
        private final double phase;
        /** 上一 tick 姿态。 */
        private Pose previous;
        /** 当前 tick 姿态；无效时为 null。 */
        private Pose current;
        /** 本炮口当前生命周期尺寸倍率；0 为刚开始或结束，1 为完整尺寸。 */
        private double lifecycleScale;

        private MuzzleColumn(MuzzleAnchor muzzle, double phase) {
            this.muzzle = muzzle;
            this.phase = phase;
        }
    }

    /** 一个仍被客户端追踪的残骸实例。 */
    private static final class Instance {
        /** 当前实体引用，UUID 相同但对象更换时重建绑定。 */
        private final AbstractVehicle vehicle;
        /** 缓存的出口，禁止每帧重复扫描结构。 */
        private final List<Column> columns = new ArrayList<>();
        /** 只画火柱的炮口，不参与火星预算。 */
        private final List<MuzzleColumn> muzzles = new ArrayList<>();
        /** 本次遍历是否仍见到车辆，用于移除卸载/离开范围的实例。 */
        private long seen;
        /** 到相机的距离，单位格。 */
        private double distance;
        /** 本实例开始被客户端观察的世界 tick。 */
        private final long startedAt;
        /** 本实例开始收缩的世界 tick；Long.MIN_VALUE 表示仍在活动。 */
        private long endingAt = Long.MIN_VALUE;
        /** 开始收缩时的尺寸倍率，避免在增长未完成时突然跳到完整尺寸。 */
        private double endStartScale;

        private Instance(AbstractVehicle vehicle) {
            this.vehicle = vehicle;
            this.startedAt = lastTick;
            // 调用自动挂点解析器，所有车型共用同一套只读规则；炮口只在建立绑定时收集一次
            Discovery discovery = discover(vehicle, true);
            List<Anchor> anchors = discovery.anchors();
            for (int i = 0; i < anchors.size(); i++) {
                columns.add(new Column(anchors.get(i), (vehicle.getUUID().hashCode() & 1023) + i * 1.7,
                        lastTick));
            }
            for (int i = 0; i < discovery.muzzles().size(); i++) {
                // 炮口相位沿用与舱盖/接缝相同的"车辆哈希 + 序号"错相，避免整车同步闪动
                muzzles.add(new MuzzleColumn(discovery.muzzles().get(i),
                        (vehicle.getUUID().hashCode() & 1023) + i * 1.7));
            }
            // 调用本实例生命周期同步：新残骸第一帧从最小火柱开始增长。
            updateLifecycleScale(lastTick);
        }

        /** 判断本实例是否已经进入结束收缩阶段。 */
        private boolean isEnding() {
            return endingAt != Long.MIN_VALUE;
        }

        /** 开始结束收缩；只在实例从当前 tick 的追踪集合中消失时调用。 */
        private void beginEnding(long tick) {
            if (isEnding()) return;
            this.endStartScale = lifecycleScaleAt(tick);
            this.endingAt = tick;
        }

        /** 实例重新进入追踪范围时恢复活动状态，不重复播放增长动画。 */
        private void resumeActive() {
            if (!isEnding()) return;
            this.endingAt = Long.MIN_VALUE;
            this.endStartScale = 1;
            setLifecycleScale(1);
        }

        /** 判断结束收缩是否已经完成。 */
        private boolean isExpired(long tick) {
            return isEnding() && tick - endingAt >= durationTicks(RVP_WreckCookoffSettings.shrinkTicks);
        }

        /** 计算本 tick 的火柱尺寸倍率；增长和收缩均使用平滑插值。 */
        private double lifecycleScaleAt(long tick) {
            if (isEnding()) {
                double progress = (tick - endingAt)
                        / durationTicks(RVP_WreckCookoffSettings.shrinkTicks);
                // 结束阶段必须从当前尺寸下降到零，不能复用增长方向的正向曲线。
                return RVP_WreckCookoffController.shrinkLifecycleScale(endStartScale, progress);
            }
            double progress = (tick - startedAt)
                    / durationTicks(RVP_WreckCookoffSettings.growthTicks);
            return RVP_WreckCookoffController.growthLifecycleScale(progress);
        }

        /** 将生命周期倍率同步到舱盖/接缝火柱和炮口火柱视图。 */
        private void updateLifecycleScale(long tick) {
            setLifecycleScale(lifecycleScaleAt(tick));
        }

        /** 写入所有出口的共享生命周期倍率，避免渲染器和粒子发射器各算一份。 */
        private void setLifecycleScale(double scale) {
            double clamped = Math.max(0, Math.min(1, scale));
            for (Column column : columns) column.lifecycleScale = clamped;
            for (MuzzleColumn muzzle : muzzles) muzzle.lifecycleScale = clamped;
        }

        /** 将可调时长转换为至少一个 tick，防止指令边界外的零除。 */
        private static double durationTicks(double ticks) {
            return Math.max(1, ticks);
        }

    }

    private RVP_WreckCookoffController() {}

    /** 增长阶段的生命周期倍率：从 0 平滑到 1。 */
    public static double growthLifecycleScale(double progress) {
        return smoothstep(clampProgress(progress));
    }

    /** 收缩阶段的生命周期倍率：从传入的当前倍率平滑降到 0，不重新播放增长过程。 */
    public static double shrinkLifecycleScale(double startScale, double progress) {
        double scale = Math.max(0, Math.min(1, startScale));
        return scale * (1 - smoothstep(clampProgress(progress)));
    }

    /** 将生命周期进度限制到 [0, 1]，避免调参或浮点误差越界。 */
    private static double clampProgress(double progress) {
        return Double.isFinite(progress) ? Math.max(0, Math.min(1, progress)) : 1;
    }

    /** 平滑三次插值，避免火柱在起止边界突然改变速度。 */
    private static double smoothstep(double progress) {
        return progress * progress * (3 - 2 * progress);
    }

    /** 仅履带与轮式载具属于目标类型，落地飞机不算地面载具。 */
    public static boolean isGroundVehicle(AbstractVehicle vehicle) {
        return vehicle instanceof TrackedVehicle || vehicle instanceof WheeledVehicle;
    }

    /** 在旧烟遍历前调用；返回 false 表示本 tick 不应重复推进。 */
    public static boolean begin(ClientLevel level) {
        if (currentLevel != level) {
            // 调用本类统一清理，避免跨世界持有旧实体和预览状态。
            clear();
            currentLevel = level;
        }
        if (level == null || Minecraft.getInstance().isPaused() || lastTick == level.getGameTime()) return false;
        lastTick = level.getGameTime();
        emitted = 0;
        if (preview != null && lastTick >= previewEnd) preview = null;
        return true;
    }

    /** 接受现有遍历中的载具，自动筛选击毁状态或显式预览。 */
    public static void consider(AbstractVehicle vehicle) {
        // 调用本类类型门控和本体击毁标记，攻击来源不参与判定。
        if (!isGroundVehicle(vehicle) || vehicle.isRemoved()
                || (!vehicle.isDestroyed() && !vehicle.getUUID().equals(preview))) return;
        Vec3 camera = Minecraft.getInstance().gameRenderer.getMainCamera().getPosition();
        double distance = camera.distanceTo(vehicle.position());
        if (distance > 256) return;
        Instance instance = INSTANCES.get(vehicle.getUUID());
        if (instance == null || instance.vehicle != vehicle
                || (instance.columns.isEmpty() && lastTick % 20 == 0)) {
            instance = new Instance(vehicle);
            INSTANCES.put(vehicle.getUUID(), instance);
        }
        // 重新观察到同一残骸时取消尚未完成的收缩，但不重新播放增长阶段。
        instance.resumeActive();
        instance.seen = lastTick;
        instance.distance = distance;
        for (Column column : instance.columns) {
            // 接缝出口按"单个采样点持续 3～6 tick 再换点"推进；换点时为跳变，
            // 因此这里先判定是否换点，再决定要不要保留上一 tick 姿态供插值
            boolean jumped = column.reseed(lastTick);
            // 调用本项目姿态求解器：舱盖按世界 Y 轴 30° 圆锥采样方向，接缝按环绕方位采样
            Pose pose = resolve(column.anchor, column.sampleSeed);
            column.previous = (column.current == null || jumped) ? pose : column.current;
            column.current = pose;
        }
        for (MuzzleColumn muzzle : instance.muzzles) {
            // 调用炮口专用求解器；炮口只画火柱、不参与火星预算
            Pose pose = resolveMuzzle(muzzle.muzzle);
            muzzle.previous = muzzle.current == null ? pose : muzzle.current;
            muzzle.current = pose;
        }
        // 调用本实例生命周期同步：增长阶段与挂点更新在同一 tick 生效。
        instance.updateLifecycleScale(lastTick);
    }

    /** 遍历结束后公平分配全局粒子预算，并移除本 tick 未出现的残骸。 */
    public static void end() {
        INSTANCES.values().removeIf(instance -> {
            if (instance.seen != lastTick) {
                // 未在本 tick 被客户端追踪到，进入结束收缩而不是立即消失。
                instance.beginEnding(lastTick);
            }
            instance.updateLifecycleScale(lastTick);
            return instance.isExpired(lastTick);
        });
        ORDERED.clear();
        ORDERED.addAll(INSTANCES.values());
        ORDERED.sort(Comparator.comparingDouble(instance -> instance.distance));
        int[] requests = new int[ORDERED.size()];
        for (int i = 0; i < requests.length; i++) {
            Instance instance = ORDERED.get(i);
            if (instance.isEnding()) {
                // 结束收缩阶段只保留火柱绘制，不再生成新的火星。
                requests[i] = 0;
                continue;
            }
            // 调用本类出口类别收集，再交给预算表统一算权重，避免发射与估算两处各写一份权重表
            List<Kind> presentKinds = new ArrayList<>();
            for (Kind kind : Kind.values()) {
                if (instance.columns.stream().anyMatch(c -> c.current != null && c.anchor.kind() == kind)) {
                    presentKinds.add(kind);
                }
            }
            int base = RVP_WreckCookoffBudget.baseDemand(presentKinds);
            // 调用距离预算规则，低粒子设置进一步减少，绝不强制绕过原版设置。
            double setting = switch (Minecraft.getInstance().options.particles().get()) {
                case ALL -> 1;
                case DECREASED -> 0.5;
                case MINIMAL -> 0.2;
            };
            double wanted = base * RVP_WreckCookoffBudget.distanceScale(instance.distance)
                    * RVP_WreckCookoffSettings.density * setting;
            requests[i] = (int) wanted + (currentLevel.random.nextDouble() < wanted % 1 ? 1 : 0);
        }
        // 调用全局公平预算；单车额度的下限由测试锚定在"基础需求"上，防止出口变多后被预算压回去。
        int[] allocations = RVP_WreckCookoffBudget.allocate(requests,
                RVP_WreckCookoffBudget.PARTICLES_PER_TICK, RVP_WreckCookoffBudget.PARTICLES_PER_VEHICLE);
        for (int i = 0; i < allocations.length; i++) {
            // 调用本类分类发射，保持舱盖/接缝大量、炮口中等的比例。
            emit(ORDERED.get(i), allocations[i]);
            emitted += allocations[i];
        }
    }

    /**
     * 分类权重同类出口按世界时间轮换避免固定一处独占。
     * 炮口已不喷火星，因此火星出口只有舱盖与接缝两类。
     */
    private static void emit(Instance instance, int count) {
        if (instance.isEnding()) return;
        List<Column> weighted = new ArrayList<>();
        for (Kind kind : Kind.values()) {
            // 炮口出口不参与火星；这里跳过，避免权重表里留一项永不发射的死配置
            if (kind == Kind.MUZZLE) continue;
            List<Column> outlets = instance.columns.stream()
                    .filter(column -> column.current != null && column.anchor.kind() == kind).toList();
            if (outlets.isEmpty()) continue;
            // 调用预算表权重，与 end() 的需求估算共用同一张表，避免两处权重不一致
            int weight = RVP_WreckCookoffBudget.weightOf(kind);
            for (int i = 0; i < weight; i++) {
                weighted.add(outlets.get((int) Math.floorMod(lastTick + i, outlets.size())));
            }
        }
        if (weighted.isEmpty()) return;
        for (int i = 0; i < count; i++) {
            Column column = weighted.get((int) Math.floorMod(lastTick * 7 + i, weighted.size()));
            Pose pose = column.current;
            // 调用本类柱长入口，以连续柱身随机点补充溅出的火星。
            double along = currentLevel.random.nextDouble() * length(column) * 0.7;
            double spread = column.anchor.width() * 0.35;
            Vec3 origin = pose.position().add(pose.direction().scale(along)).add(
                    (currentLevel.random.nextDouble() - 0.5) * spread,
                    (currentLevel.random.nextDouble() - 0.5) * spread,
                    (currentLevel.random.nextDouble() - 0.5) * spread);
            Vec3 axis = pose.direction();
            // 调用殉燃火星抛射速度规则，改用 RVP 自实现的"受重力下坠 + 落地弹跳"粒子，
            // 速度直接沿采样方向给定（舱盖即 30° 圆锥内的一条母线）。
            double axisSpeed = RVP_WreckSparkParticle.launchSpeed(currentLevel.random.nextDouble());
            // 调用 RVP 火星生成入口，直接加入粒子引擎；数量与寿命仍按既有分配额与火星规则，
            // 距离 LOD 由上面的分配额与火柱段数负责，寿命不随距离缩短（否则落地前就淡出）。
            RVP_WreckSparkParticle.spawn(Minecraft.getInstance().particleEngine, currentLevel,
                    origin.x, origin.y, origin.z,
                    axis.x, axis.y, axis.z, axisSpeed,
                    RVP_WreckSparkParticle.SPARK_QUAD_SIZE);
        }
    }

    /** 返回当前全局调参下的柱长；炮口柱长与出口类别分开取。 */
    public static double length(Column column) {
        return switch (column.anchor.kind()) {
            case HATCH -> RVP_WreckCookoffSettings.height;
            case SEAM -> 0.8;
            case MUZZLE -> RVP_WreckCookoffSettings.length;
        };
    }

    /** 渲染用的一根火柱：舱盖/接缝出口与炮口出口的公共视图。 */
    public interface RenderColumn {
        /** 上一 tick 的世界姿态。 */
        Pose previousPose();

        /** 当前 tick 的世界姿态。 */
        Pose currentPose();

        /**
         * 火柱方向；与"火星采样方向"解耦。
         *
         * <p>舱盖必须恒为世界向上：火星方向是在世界 Y 轴 30° 圆锥内随机采样的，
         * 若火柱直接复用火星方向，柱子会跟着随机锥角歪掉。</p>
         */
        Vec3 columnDirection();

        /** 上一 tick 的火柱方向，供渲染插值；舱盖为常量世界向上。 */
        Vec3 previousColumnDirection();

        /** 与类别无关的柱宽，单位格。 */
        double width();

        /** 出口相位，用于让各柱动画错开。 */
        double phase();

        /** 柱长，单位格。 */
        double length();

        /** 生命周期尺寸倍率；用于开始增长和结束收缩。 */
        double lifecycleScale();
    }

    /** 舱盖/接缝出口的火柱视图。 */
    private record AnchorColumn(Column column) implements RenderColumn {
        @Override
        public Pose previousPose() {
            return column.previous;
        }

        @Override
        public Pose currentPose() {
            return column.current;
        }

        @Override
        public Vec3 columnDirection() {
            // 调用本项目柱向规则：舱盖柱恒向上（用户定版），只有火星走 30° 圆锥；
            // 接缝不画柱，该分支不会被渲染走到
            return RVP_WreckCookoffGeometry.columnDirection(column.anchor.kind() == Kind.HATCH,
                    column.current.direction());
        }

        @Override
        public Vec3 previousColumnDirection() {
            Pose previous = column.previous == null ? column.current : column.previous;
            return RVP_WreckCookoffGeometry.columnDirection(column.anchor.kind() == Kind.HATCH,
                    previous.direction());
        }

        @Override
        public double width() {
            return column.anchor.width();
        }

        @Override
        public double phase() {
            return column.phase;
        }

        @Override
        public double length() {
            // 调用控制器柱长入口，避免这里再抄一份"按类别取柱长"的 switch
            return RVP_WreckCookoffController.length(column);
        }

        @Override
        public double lifecycleScale() {
            return column.lifecycleScale;
        }
    }

    /** 炮口火柱视图；炮口不参与火星，柱长取炮口设置。 */
    private record MuzzleRenderColumn(MuzzleColumn muzzle) implements RenderColumn {
        @Override
        public Pose previousPose() {
            return muzzle.previous;
        }

        @Override
        public Pose currentPose() {
            return muzzle.current;
        }

        @Override
        public Vec3 columnDirection() {
            // 炮口柱沿炮管轴向，与开火方向一致
            return muzzle.current.direction();
        }

        @Override
        public Vec3 previousColumnDirection() {
            return (muzzle.previous == null ? muzzle.current : muzzle.previous).direction();
        }

        @Override
        public double width() {
            return muzzle.muzzle.width();
        }

        @Override
        public double phase() {
            return muzzle.phase;
        }

        @Override
        public double length() {
            return RVP_WreckCookoffSettings.length;
        }

        @Override
        public double lifecycleScale() {
            return muzzle.lifecycleScale;
        }
    }

    /**
     * 公平分配火柱：每轮每车各取一根，最多 {@code MAX_COLUMNS} 根；远处降低每车根数。
     * 接缝按用户定版只喷火星、不画柱，因此这里跳过接缝出口。
     */
    public static List<RenderColumn> renderColumns() {
        List<RenderColumn> result = new ArrayList<>();
        for (int round = 0; round < 24 && result.size() < RVP_WreckCookoffBudget.MAX_COLUMNS; round++) {
            for (Instance instance : ORDERED) {
                if (result.size() >= RVP_WreckCookoffBudget.MAX_COLUMNS) break;
                boolean far = instance.distance > 128 && round > 3;
                if (far) continue;
                if (round < instance.columns.size()) {
                    Column column = instance.columns.get(round);
                    if (column.current != null && isHatchColumnAnchor(column.anchor)) {
                        result.add(new AnchorColumn(column));
                    }
                }
                if (round < instance.muzzles.size()) {
                    MuzzleColumn muzzle = instance.muzzles.get(round);
                    if (muzzle.current != null) {
                        result.add(new MuzzleRenderColumn(muzzle));
                    }
                }
            }
        }
        return result;
    }

    /** 开始 60 秒载具预览，不修改实体击毁标记。 */
    public static void preview(AbstractVehicle vehicle) {
        if (currentLevel != vehicle.level()) {
            // 调用统一清理，跨世界开启预览也不能绕过世界身份检查。
            clear();
        }
        currentLevel = (ClientLevel) vehicle.level();
        preview = vehicle.getUUID();
        previewEnd = currentLevel.getGameTime() + 1200;
    }

    /** 停止模拟预览；真实击毁的同一辆车仍按正常规则持续喷燃。 */
    public static void stopPreview() {
        preview = null;
    }

    /** 客户端世界退出或资源重载时清空强引用。 */
    public static void clear() {
        INSTANCES.clear();
        ORDERED.clear();
        preview = null;
        previewEnd = 0;
        currentLevel = null;
        lastTick = Long.MIN_VALUE;
        emitted = 0;
    }

    /** 调试状态，不暴露或修改服务端实体。 */
    public static String describe() {
        return "自动适配地面载具；活动残骸=" + INSTANCES.size() + "；上 tick 火星生成尝试数=" + emitted
                + "/" + RVP_WreckCookoffBudget.PARTICLES_PER_TICK + "；height=" + RVP_WreckCookoffSettings.height
                + " 格；length=" + RVP_WreckCookoffSettings.length
                + " 格；density=" + RVP_WreckCookoffSettings.density
                + "；grow=" + RVP_WreckCookoffSettings.growthTicks
                + " tick；shrink=" + RVP_WreckCookoffSettings.shrinkTicks + " tick";
    }
}
