package org.ywzj.rvp.client.visual.cookoff;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.phys.Vec3;
import org.ywzj.rvp.client.particle.RVP_WreckFlameParticle;
import org.ywzj.rvp.client.particle.RVP_WreckMuzzleSmokeParticle;
import org.ywzj.rvp.client.particle.RVP_WreckMuzzleSmokeSelfRenderer;
import org.ywzj.rvp.client.particle.RVP_WreckSparkParticle;
import org.ywzj.rvp.config.RVP_CommonConfig;
import org.ywzj.rvp.vehicle.wreck.RVP_WreckDestructionState;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;
import org.ywzj.vehicle.entity.vehicle.TrackedVehicle;
import org.ywzj.vehicle.entity.vehicle.WheeledVehicle;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import static org.ywzj.rvp.client.visual.cookoff.RVP_WreckCookoffResolver.*;

/** 地面载具持续殉燃；由击毁烟的既有遍历驱动，不增加全世界实体遍历。 */
public final class RVP_WreckCookoffController {

    /** 预览与真实殉燃共用公共时间规则，实际百分比在双端安全状态类中维护。 */
    public static final double WRECK_LIFETIME_PERCENT = RVP_WreckDestructionState.WRECK_LIFETIME_PERCENT;
    /** 殉燃完成后允许生成长程击毁烟的最短等待时间，单位 tick。 */
    private static final int LONG_SMOKE_DELAY_MIN_TICKS = 30;
    /** 殉燃完成后允许生成长程击毁烟的最长等待时间，单位 tick。 */
    private static final int LONG_SMOKE_DELAY_MAX_TICKS = 60;

    /** 当前世界绑定，换维度时清理所有实体引用。 */
    private static ClientLevel currentLevel;
    /** 上次已处理的世界 tick，避免暂停或重复事件生成粒子。 */
    private static long lastTick = Long.MIN_VALUE;
    /** 以 UUID 而非可复用的数字 ID 区分残骸。 */
    private static final Map<UUID, Instance> INSTANCES = new HashMap<>();
    /** 已完成定时殉燃的车辆 UUID；防止残骸仍存在时下一 tick 被重新创建火柱。 */
    private static final Set<UUID> COMPLETED = new HashSet<>();
    /** 每辆已完成殉燃的车辆允许开始生成长程击毁烟的客户端世界 tick。 */
    private static final Map<UUID, Long> LONG_SMOKE_UNLOCK_TICKS = new HashMap<>();
    /** 本 tick 按距离排序后的实例，渲染和预算复用。 */
    private static final List<Instance> ORDERED = new ArrayList<>();
    /** 预览车辆 UUID；仅客户端模拟视觉，不伤害真实载具。 */
    private static UUID preview;
    /** 预览自动结束时间，单位客户端世界 tick。 */
    private static long previewEnd;
    /** 最近一 tick 尝试发射的原版粒子数，供调试显示。 */
    private static int emitted;
    /** 最近一 tick 尝试发射的静态喷燃粒子数，供调试显示。 */
    private static int flameEmitted;
    /** 最近一 tick 尝试发射的炮口灰烟粒子数，供调试显示。 */
    private static int muzzleSmokeEmitted;

    /** 一个出口前后两帧的世界姿态。 */
    public static final class Column {
        /** 所属残骸车辆 UUID；喷燃预算按该 UUID 限制单车上限。 */
        private final UUID vehicleId;
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

        private Column(UUID vehicleId, Anchor anchor, double phase, long tick) {
            this.vehicleId = vehicleId;
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

    /** 炮口灰烟的前后两帧姿态；炮口不参与火星，单独维护。 */
    private static final class MuzzleColumn {
        /** 所属残骸车辆 UUID；喷燃预算按该 UUID 限制单车上限。 */
        private final UUID vehicleId;
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

        private MuzzleColumn(UUID vehicleId, MuzzleAnchor muzzle, double phase) {
            this.vehicleId = vehicleId;
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
        /** 只画灰烟的炮口，不参与火星预算。 */
        private final List<MuzzleColumn> muzzles = new ArrayList<>();
        /** 本次遍历是否仍见到车辆，用于移除卸载/离开范围的实例。 */
        private long seen;
        /** 到相机的距离，单位格。 */
        private double distance;
        /** 真实残骸使用服务端击毁 tick；纯客户端预览使用本地开始 tick。 */
        private final long startedAt;
        /** 是否为纯客户端预览，避免预览生命周期覆盖真实残骸的服务端时间表。 */
        private final boolean previewInstance;
        /** 本实例开始收缩的世界 tick；Long.MIN_VALUE 表示仍在活动。 */
        private long endingAt = Long.MIN_VALUE;
        /** 开始收缩时的尺寸倍率，避免在增长未完成时突然跳到完整尺寸。 */
        private double endStartScale;
        /** 本实例创建时锁定的殉燃火柱总生命周期，单位 tick。 */
        private final long cookoffDurationTicks;
        /** 本实例实际采用的收缩时长，单位 tick；实体提前离开追踪范围时仍使用完整收缩时长。 */
        private long endingDurationTicks;
        /** 是否因达到按残骸寿命比例计算的定时结束点而收缩；提前离开追踪范围不置为 true。 */
        private boolean timedEnding;

        private Instance(AbstractVehicle vehicle, RVP_WreckDestructionState state) {
            this.vehicle = vehicle;
            this.previewInstance = state == null;
            this.startedAt = state == null ? lastTick : state.startedAt();
            // 调用本项目权威快照；仅无伤害预览读取本地 common 配置作为时长基准。
            this.cookoffDurationTicks = state == null ? RVP_WreckCookoffController.cookoffDurationTicks(
                    RVP_CommonConfig.getWreckLifetimeSeconds(), WRECK_LIFETIME_PERCENT) : state.durationTicks();
            this.endingDurationTicks = durationTicks(RVP_WreckCookoffSettings.shrinkTicks);
            // 调用自动挂点解析器，所有车型共用同一套只读规则；炮口只在建立绑定时收集一次
            Discovery discovery = discover(vehicle, true);
            List<Anchor> anchors = discovery.anchors();
            for (int i = 0; i < anchors.size(); i++) {
                columns.add(new Column(vehicle.getUUID(), anchors.get(i),
                        (vehicle.getUUID().hashCode() & 1023) + i * 1.7,
                        lastTick));
            }
            for (int i = 0; i < discovery.muzzles().size(); i++) {
                // 炮口相位沿用与舱盖/接缝相同的"车辆哈希 + 序号"错相，避免整车同步闪动
                muzzles.add(new MuzzleColumn(vehicle.getUUID(), discovery.muzzles().get(i),
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
            beginEnding(tick, durationTicks(RVP_WreckCookoffSettings.shrinkTicks));
        }

        /** 开始结束收缩，并记录本次收缩时长，保证定时结束不会被重新激活。 */
        private void beginEnding(long tick, long shrinkTicks) {
            if (isEnding()) return;
            this.endStartScale = lifecycleScaleAt(tick);
            this.endingDurationTicks = Math.max(1, shrinkTicks);
            this.endingAt = tick;
        }

        /** 实例重新进入追踪范围时恢复活动状态，不重复播放增长动画。 */
        private void resumeActive(long tick) {
            if (!isEnding()) return;
            if (timedEnding || shouldStartTimedEnding(tick)) return;
            this.endingAt = Long.MIN_VALUE;
            this.endStartScale = 1;
            setLifecycleScale(1);
        }

        /** 判断结束收缩是否已经完成。 */
        private boolean isExpired(long tick) {
            return isEnding() && tick - endingAt >= endingDurationTicks;
        }

        /** 判断是否已到达按残骸寿命比例计算的火柱结束时间。 */
        private boolean shouldStartTimedEnding(long tick) {
            return tick - startedAt >= activeDurationTicks();
        }

        /** 计算保留完整火柱后可用于收缩的前置活动时长，单位 tick。 */
        private long activeDurationTicks() {
            long shrinkTicks = durationTicks(RVP_WreckCookoffSettings.shrinkTicks);
            return Math.max(0, cookoffDurationTicks - Math.min(cookoffDurationTicks, shrinkTicks));
        }

        /** 在达到总生命周期时开始按剩余时长收缩，确保火柱总可见时长不超过配置结果。 */
        private void startTimedEndingIfNeeded(long tick) {
            if (!isEnding() && shouldStartTimedEnding(tick)) {
                long shrinkTicks = Math.min(durationTicks(RVP_WreckCookoffSettings.shrinkTicks),
                        Math.max(1, cookoffDurationTicks));
                long endingAt = startedAt + activeDurationTicks();
                // 定时检测可能因实体暂时离开遍历而晚到，仍按原定时刻开始收缩，避免生命周期被拖长。
                this.endStartScale = lifecycleScaleAt(endingAt);
                this.endingDurationTicks = shrinkTicks;
                this.timedEnding = true;
                this.endingAt = endingAt;
            }
        }

        /** 计算本 tick 的火柱尺寸倍率；增长和收缩均使用平滑插值。 */
        private double lifecycleScaleAt(long tick) {
            if (isEnding()) {
                double progress = (tick - endingAt)
                        / (double) endingDurationTicks;
                // 结束阶段必须从当前尺寸下降到零，不能复用增长方向的正向曲线。
                return RVP_WreckCookoffController.shrinkLifecycleScale(endStartScale, progress);
            }
            double progress = (double) (tick - startedAt)
                    / durationTicks(RVP_WreckCookoffSettings.growthTicks);
            return RVP_WreckCookoffController.growthLifecycleScale(progress);
        }

        /** 将生命周期倍率同步到舱盖火焰和炮口灰烟视图。 */
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
        private static long durationTicks(double ticks) {
            return Math.max(1L, Math.round(ticks));
        }

    }

    private RVP_WreckCookoffController() {}

    /** 将残骸保留秒数和代码百分比转换为火柱总生命周期，单位 tick。 */
    public static long cookoffDurationTicks(int wreckLifetimeSeconds, double percent) {
        // 调用本项目双端公共规则，客户端预览与服务端延迟调度使用同一时长算法。
        return RVP_WreckDestructionState.cookoffDurationTicks(wreckLifetimeSeconds, percent);
    }

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

    /**
     * 计算车顶喷燃在轴向进度处的横截面半径，形成根部窄、远端宽的倒梯形圆锥。
     *
     * @param progress      从喷口到喷焰远端的归一化进度
     * @param envelopeWidth 当前出口的基础喷燃宽度，单位格
     * @return 当前截面半径，单位格
     */
    static double roofConeRadius(double progress, double envelopeWidth) {
        double width = Math.max(0.08D, Math.min(1.4D, envelopeWidth));
        double clampedProgress = clampProgress(progress);
        double baseFactor = Math.max(0.02D, Math.min(1.5D, RVP_WreckCookoffSettings.roofConeBase));
        double tipFactor = Math.max(baseFactor, Math.min(2.5D, RVP_WreckCookoffSettings.roofConeTip));
        double baseRadius = width * baseFactor;
        double tipRadius = width * tipFactor;
        return baseRadius + (tipRadius - baseRadius) * clampedProgress;
    }

    /** 根据 [0, 1] 随机值计算车顶喷燃的轴向初速度，单位格/tick。 */
    static double roofFlameAxisSpeed(double random) {
        double clampedRandom = Double.isFinite(random) ? Math.max(0.0D, Math.min(1.0D, random)) : 0.0D;
        double minimum = Math.max(0.05D, Math.min(20.0D, RVP_WreckCookoffSettings.roofAxisSpeedMin));
        double maximum = Math.max(minimum,
                Math.max(0.05D, Math.min(20.0D, RVP_WreckCookoffSettings.roofAxisSpeedMax)));
        return minimum + (maximum - minimum) * clampedRandom;
    }

    /** 根据配置高度和生命周期倍率计算车顶喷燃的可见目标高度，单位格。 */
    static double roofFlameTargetHeight(double configuredHeight, double lifecycleScale) {
        double height = Double.isFinite(configuredHeight)
                ? Math.max(0.5D, Math.min(16.0D, configuredHeight))
                : 0.5D;
        double scale = Double.isFinite(lifecycleScale)
                ? Math.max(0.0D, Math.min(1.0D, lifecycleScale))
                : 0.0D;
        return height * scale;
    }

    /** 计算贴图中心的轴向活动范围，给贴图半径预留余量避免视觉高度超出目标高度。 */
    static double roofFlameCenterTravel(double targetHeight, double width, double lifecycleScale) {
        double safeHeight = Math.max(0.05D, Double.isFinite(targetHeight) ? targetHeight : 0.05D);
        double safeWidth = Math.max(0.16D, Math.min(1.4D, width));
        double scale = Double.isFinite(lifecycleScale)
                ? Math.max(0.0D, Math.min(1.0D, lifecycleScale))
                : 0.0D;
        double maximumQuadSize = safeWidth * 1.32D * 1.2D
                * Math.max(0.1D, Math.min(3.0D, RVP_WreckCookoffSettings.roofTextureScaleMax))
                * scale;
        return Math.max(0.05D, safeHeight - maximumQuadSize);
    }

    /** 计算车顶沿轴向铺满连续贴图所需的层数，最多受单车喷燃预算限制。 */
    static int roofFlameLayerCount(double targetHeight, double width, double lifecycleScale) {
        double centerTravel = roofFlameCenterTravel(targetHeight, width, lifecycleScale);
        double safeWidth = Math.max(0.16D, Math.min(1.4D, width));
        double scale = Double.isFinite(lifecycleScale)
                ? Math.max(0.0D, Math.min(1.0D, lifecycleScale))
                : 0.0D;
        double maximumQuadSize = safeWidth * 1.32D * 1.2D
                * Math.max(0.1D, Math.min(3.0D, RVP_WreckCookoffSettings.roofTextureScaleMax))
                * scale;
        double spacing = Math.max(0.18D, maximumQuadSize * 1.08D);
        int layers = (int) Math.ceil(centerTravel / spacing) + 1;
        return Math.max(RVP_WreckCookoffBudget.EFFECT_LAYERS_PER_COLUMN,
                Math.min(RVP_WreckCookoffBudget.EFFECT_PARTICLES_PER_VEHICLE, layers));
    }

    /** 返回连续分层中某一层的中心位置，避免车顶贴图随机散落造成轴向断层。 */
    static double roofFlameLayerPosition(double centerTravel, int layer, int layerCount) {
        double travel = Math.max(0.05D, Double.isFinite(centerTravel) ? centerTravel : 0.05D);
        int count = Math.max(1, layerCount);
        int index = Math.max(0, Math.min(count - 1, layer));
        return travel * (index + 0.5D) / count;
    }

    /**
     * 计算炮口灰烟沿炮管轴向连续覆盖所需的层数；烟片越小或殉燃仍在增长时，层数越多。
     *
     * <p>炮口烟的 {@code SingleQuadParticle#quadSize} 初始值为目标尺寸的 70%，
     * 因而不能沿用原先固定两层的稀疏采样；这里按保守覆盖间距计算，避免随机落点再次露出空档。</p>
     *
     * @param travel 炮口灰烟中心允许覆盖的轴向距离，单位格
     * @param lifecycleScale 当前殉燃生命周期倍率，无单位
     * @return 至少两层、且不超过单车效果预算的轴向层数
     */
    static int muzzleSmokeLayerCount(double travel, double lifecycleScale) {
        double safeTravel = Math.max(0.05D, Double.isFinite(travel) ? travel : 0.05D);
        double scale = Double.isFinite(lifecycleScale)
                ? Math.max(0.0D, Math.min(1.0D, lifecycleScale))
                : 0.0D;
        double smokeSize = Double.isFinite(RVP_WreckCookoffSettings.muzzleSmokeSize)
                ? Math.max(0.1D, Math.min(3.0D, RVP_WreckCookoffSettings.muzzleSmokeSize))
                : 0.1D;
        // 按当前首帧烟片尺寸留出保守间距；小烟片和增长阶段自动增加层数，避免轴向断层。
        double coverageSpacing = Math.max(0.18D, smokeSize * scale * 0.56D);
        int layers = (int) Math.ceil(safeTravel / coverageSpacing) + 1;
        return Math.max(RVP_WreckCookoffBudget.EFFECT_LAYERS_PER_COLUMN,
                Math.min(RVP_WreckCookoffBudget.EFFECT_PARTICLES_PER_VEHICLE, layers));
    }

    /** 返回炮口灰烟连续分层中某一层的中心位置，避免整段随机采样造成贴图空隙。 */
    static double muzzleSmokeLayerPosition(double travel, int layer, int layerCount) {
        return roofFlameLayerPosition(travel, layer, layerCount);
    }

    /** 根据 [0, 1] 随机值计算车顶贴图倍率，单位为无量纲尺寸倍率。 */
    static double roofTextureScale(double random) {
        double clampedRandom = Double.isFinite(random) ? Math.max(0.0D, Math.min(1.0D, random)) : 0.0D;
        double minimum = Math.max(0.1D, Math.min(3.0D, RVP_WreckCookoffSettings.roofTextureScaleMin));
        double maximum = Math.max(minimum,
                Math.max(0.1D, Math.min(3.0D, RVP_WreckCookoffSettings.roofTextureScaleMax)));
        return minimum + (maximum - minimum) * clampedRandom;
    }

    /**
     * 计算车顶喷燃的实际生成原点；只偏置喷燃贴图，不改变火星采样点或载具姿态。
     *
     * @param hatchOrigin 解析出的舱盖/顶部近似出口原点
     * @return 沿世界竖直方向偏置后的喷燃原点
     */
    static Vec3 roofFlameOrigin(Vec3 hatchOrigin) {
        double offset = Double.isFinite(RVP_WreckCookoffSettings.roofVerticalOffset)
                ? Math.max(RVP_WreckCookoffSettings.ROOF_VERTICAL_OFFSET_MIN,
                Math.min(RVP_WreckCookoffSettings.ROOF_VERTICAL_OFFSET_MAX,
                        RVP_WreckCookoffSettings.roofVerticalOffset))
                : 0.0D;
        return hatchOrigin.add(RVP_WreckCookoffGeometry.UP.scale(offset));
    }

    /** 平滑三次插值，避免火柱在起止边界突然改变速度。 */
    private static double smoothstep(double progress) {
        return progress * progress * (3 - 2 * progress);
    }

    /** 仅履带与轮式载具属于目标类型，落地飞机不算地面载具。 */
    public static boolean isGroundVehicle(AbstractVehicle vehicle) {
        return vehicle instanceof TrackedVehicle || vehicle instanceof WheeledVehicle;
    }

    /**
     * 判断地面载具是否已经越过殉燃结束后的长程击毁烟等待门。
     *
     * <p>真实残骸统一读取服务端快照，远距离观察、重连、资源重载均不会重新计时。
     * 立即飞头不等待殉燃；纯客户端预览保留原来的本地生命周期。</p>
     *
     * @param vehicle 待判断的载具
     * @param gameTime 当前客户端世界 tick
     * @return 非地面载具保持原行为；真实地面残骸由服务端时间表决定
     */
    public static boolean canEmitLongSmoke(AbstractVehicle vehicle, long gameTime) {
        if (!isGroundVehicle(vehicle)) {
            return true;
        }
        if (vehicle.isDestroyed()) {
            // 调用本项目权威状态；未收到快照时等待，防止长程烟先出现再被殉燃收回。
            RVP_WreckDestructionState state = RVP_ClientWreckDestructionState.get(vehicle);
            return state != null && state.canEmitLongSmoke(gameTime);
        }
        UUID vehicleId = vehicle.getUUID();
        if (!INSTANCES.containsKey(vehicleId) && !COMPLETED.contains(vehicleId)) {
            return true;
        }
        Long unlockTick = LONG_SMOKE_UNLOCK_TICKS.get(vehicleId);
        return unlockTick != null && isLongSmokeUnlockReached(unlockTick, gameTime);
    }

    /** 将随机样本映射到包含边界的 30～60 tick 长程烟延迟，供生命周期计算和单元测试复用。 */
    static int resolveLongSmokeDelayTicks(int randomSample) {
        int range = LONG_SMOKE_DELAY_MAX_TICKS - LONG_SMOKE_DELAY_MIN_TICKS + 1;
        return LONG_SMOKE_DELAY_MIN_TICKS + Math.floorMod(randomSample, range);
    }

    /** 判断当前世界 tick 是否已达到该车记录的长程烟解锁 tick。 */
    static boolean isLongSmokeUnlockReached(long unlockTick, long currentTick) {
        return currentTick >= unlockTick;
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
        flameEmitted = 0;
        muzzleSmokeEmitted = 0;
        if (preview != null && lastTick >= previewEnd) preview = null;
        return true;
    }

    /** 接受现有遍历中的载具，自动筛选击毁状态或显式预览。 */
    public static void consider(AbstractVehicle vehicle) {
        // 调用本类类型门控和本体击毁标记，攻击来源不参与判定。
        if (!isGroundVehicle(vehicle) || vehicle.isRemoved()
                || (!vehicle.isDestroyed() && !vehicle.getUUID().equals(preview))) return;
        boolean isPreview = !vehicle.isDestroyed() && vehicle.getUUID().equals(preview);
        // 调用服务端快照缓存：真实残骸必须等到分支已知，立即飞头永不生成殉燃粒子。
        RVP_WreckDestructionState state = isPreview ? null : RVP_ClientWreckDestructionState.get(vehicle);
        if (!isPreview && (state == null || !state.isBurning(lastTick))) {
            // 到期由 end() 按原定时刻完成收缩；立即分支/未知状态不能保留旧预览实例。
            if (state == null || state.mode() == RVP_WreckDestructionState.Mode.IMMEDIATE) {
                INSTANCES.remove(vehicle.getUUID());
            }
            return;
        }
        if (isPreview && RVP_CommonConfig.getWreckLifetimeSeconds() <= 0) return;
        Vec3 camera = Minecraft.getInstance().gameRenderer.getMainCamera().getPosition();
        double distance = camera.distanceTo(vehicle.position());
        if (distance > 256) return;
        Instance instance = INSTANCES.get(vehicle.getUUID());
        if (instance == null || instance.vehicle != vehicle || instance.previewInstance != isPreview
                || (instance.columns.isEmpty() && lastTick % 20 == 0)) {
            instance = new Instance(vehicle, state);
            INSTANCES.put(vehicle.getUUID(), instance);
        }
        // 重新观察到同一残骸时取消尚未完成的收缩，但不重新播放增长阶段。
        instance.resumeActive(lastTick);
        instance.seen = lastTick;
        instance.distance = distance;
        for (Column column : instance.columns) {
            // 接缝出口按"单个采样点持续 3～12 tick 再换点"推进；换点时为跳变，
            // 因此这里先判定是否换点，再决定要不要保留上一 tick 姿态供插值
            boolean jumped = column.reseed(lastTick);
            // 调用本项目姿态求解器：舱盖按世界 Y 轴 30° 圆锥采样方向，接缝按环绕方位采样
            Pose pose = resolve(column.anchor, column.sampleSeed);
            column.previous = (column.current == null || jumped) ? pose : column.current;
            column.current = pose;
        }
        for (MuzzleColumn muzzle : instance.muzzles) {
            // 调用炮口专用求解器；炮口只画灰烟、不参与火星预算
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
                if (instance.shouldStartTimedEnding(lastTick)) {
                    instance.startTimedEndingIfNeeded(lastTick);
                } else {
                    instance.beginEnding(lastTick);
                }
            }
            // 调用本项目生命周期规则：达到 wreckLifetimeSeconds × 百分比后停止殉燃并进入收缩。
            instance.startTimedEndingIfNeeded(lastTick);
            instance.updateLifecycleScale(lastTick);
            if (!instance.isExpired(lastTick)) return false;
            if (instance.timedEnding && instance.previewInstance) {
                // 定时殉燃已完整结束；保留终结标记，直到客户端世界清理，避免实体仍存活时重新创建实例。
                UUID vehicleId = instance.vehicle.getUUID();
                COMPLETED.add(vehicleId);
                // 调用本项目随机源，为每辆车锁定一次 30～60 tick 的长程烟启动延迟。
                LONG_SMOKE_UNLOCK_TICKS.computeIfAbsent(vehicleId,
                        ignored -> lastTick + resolveLongSmokeDelayTicks(currentLevel.random.nextInt(
                                LONG_SMOKE_DELAY_MAX_TICKS - LONG_SMOKE_DELAY_MIN_TICKS + 1)));
            }
            return true;
        });
        ORDERED.clear();
        ORDERED.addAll(INSTANCES.values());
        ORDERED.sort(Comparator.comparingDouble(instance -> instance.distance));
        int[] requests = new int[ORDERED.size()];
        for (int i = 0; i < requests.length; i++) {
            Instance instance = ORDERED.get(i);
            if (instance.isEnding()) {
                // 结束收缩阶段只保留殉燃效果绘制，不再生成新的火星。
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
        // 调用本项目殉燃效果发射器：车顶保留火焰，炮口改为灰黑烟，二者共用公平粒子预算。
        emitCookoffEffectParticles();
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

    /**
     * 按当前出口轮询车顶火焰和炮口灰烟粒子；数量受粒子设置、密度和生命周期倍率共同控制。
     * 殉燃结束阶段仍会生成逐渐变少的短寿命视觉粒子，使效果自然淡出而非突然消失。
     */
    private static void emitCookoffEffectParticles() {
        if (currentLevel == null) {
            return;
        }
        List<RenderColumn> columns = renderColumns();
        if (columns.isEmpty()) {
            return;
        }
        double particleSetting = switch (Minecraft.getInstance().options.particles().get()) {
            case ALL -> 1.0D;
            case DECREASED -> 0.5D;
            case MINIMAL -> 0.2D;
        };
        double density = Math.max(0.0D, Math.min(2.0D, RVP_WreckCookoffSettings.density));
        double baseChance = density * particleSetting;
        if (!(baseChance > 0.0D)) {
            return;
        }
        int availableLayers = columns.stream()
                .mapToInt(RVP_WreckCookoffController::effectLayerCount)
                .sum();
        int maxParticles = Math.min(RVP_WreckCookoffBudget.EFFECT_PARTICLES_PER_TICK, availableLayers);
        Map<UUID, Integer> perVehicle = new HashMap<>();
        int spawned = 0;
        int flameSpawned = 0;
        int muzzleSmokeSpawned = 0;
        for (int layer = 0; layer < RVP_WreckCookoffBudget.EFFECT_PARTICLES_PER_VEHICLE
                && spawned < maxParticles; layer++) {
            int offset = (int) Math.floorMod(lastTick * 11L + layer * 17L, columns.size());
            for (int i = 0; i < columns.size() && spawned < maxParticles; i++) {
                RenderColumn column = columns.get((offset + i) % columns.size());
                if (layer >= effectLayerCount(column)) {
                    continue;
                }
                int vehicleCount = perVehicle.getOrDefault(column.vehicleId(), 0);
                if (vehicleCount >= RVP_WreckCookoffBudget.EFFECT_PARTICLES_PER_VEHICLE) {
                    continue;
                }
                double lifecycleScale = Math.max(0.0D, Math.min(1.0D, column.lifecycleScale()));
                double effectDensity = column.isRoofColumn()
                        ? 1.0D
                        : Math.max(0.0D, Math.min(2.0D, RVP_WreckCookoffSettings.muzzleSmokeDensity));
                double chance = baseChance * effectDensity * lifecycleScale;
                if (!(chance > 0.0D)
                        || (chance < 1.0D && currentLevel.random.nextDouble() >= chance)) {
                    continue;
                }
                // 调用本项目单枚殉燃效果入口：车顶固定静态火焰贴图，炮口改用灰黑烟动画贴图。
                spawnCookoffEffectParticle(column, lifecycleScale, layer);
                perVehicle.put(column.vehicleId(), vehicleCount + 1);
                spawned++;
                if (column.isRoofColumn()) {
                    flameSpawned++;
                } else {
                    muzzleSmokeSpawned++;
                }
            }
        }
        flameEmitted = flameSpawned;
        muzzleSmokeEmitted = muzzleSmokeSpawned;
    }

    /** 创建一枚车顶火焰或炮口灰烟粒子；车顶出口额外使用连续分层和倒梯形圆锥分布。 */
    private static void spawnCookoffEffectParticle(RenderColumn column, double lifecycleScale, int layer) {
        Pose pose = column.currentPose();
        if (pose == null) {
            return;
        }
        Vec3 axis = column.columnDirection();
        if (axis == null || axis.lengthSqr() < 1.0E-8D) {
            return;
        }
        axis = axis.normalize();
        Vec3 side = axis.cross(new Vec3(0.0D, 1.0D, 0.0D));
        if (side.lengthSqr() < 1.0E-8D) {
            side = axis.cross(new Vec3(1.0D, 0.0D, 0.0D));
        }
        side = side.normalize();
        Vec3 cross = axis.cross(side).normalize();
        double length = Math.max(0.1D, column.length());
        double width = Math.max(0.08D, Math.min(1.4D, column.width() * 0.35D));
        boolean roofColumn = column.isRoofColumn();
        if (!roofColumn) {
            // 炮口不再调用车顶火焰工厂，改为沿炮管轴向离口并持续向上漂移的灰黑烟。
            spawnMuzzleSmokeParticle(column, pose, axis, length, width, lifecycleScale, layer);
            return;
        }
        int layerCount = effectLayerCount(column);
        double targetHeight = roofFlameTargetHeight(length, lifecycleScale);
        double travel = roofFlameCenterTravel(targetHeight, width, lifecycleScale);
        double along = roofFlameLayerPosition(travel, layer, layerCount);
        double coneProgress = along / Math.max(0.1D, travel);
        double azimuth = currentLevel.random.nextDouble() * (Math.PI * 2.0D);
        Vec3 radialDirection = side.scale(Math.cos(azimuth)).add(cross.scale(Math.sin(azimuth))).normalize();
        Vec3 flameOrigin = roofFlameOrigin(pose.position());
        Vec3 position = flameOrigin.add(axis.scale(along));
        // 车顶喷口采用圆盘内均匀采样：截面半径随轴向扩大，粒子整体形成倒梯形圆锥体积。
        double coneRadius = roofConeRadius(coneProgress, width)
                * (0.88D + 0.12D * (layer + 0.5D) / Math.max(1, layerCount));
        double radialDistance = Math.sqrt(currentLevel.random.nextDouble()) * coneRadius;
        position = position.add(radialDirection.scale(radialDistance));
        double axisSpeed = roofFlameAxisSpeed(currentLevel.random.nextDouble());
        Vec3 velocity = axis.scale(axisSpeed)
                .add(side.scale((currentLevel.random.nextDouble() - 0.5D) * 0.035D))
                .add(cross.scale((currentLevel.random.nextDouble() - 0.5D) * 0.035D));
        // 远端同步增加径向速度，让喷燃方向也向外展开，而不只是把贴图散放在圆锥内。
        double outwardSpeed = Math.max(0.0D, Math.min(0.5D, RVP_WreckCookoffSettings.roofOutwardSpeed))
                * coneProgress;
        velocity = velocity.add(radialDirection.scale(outwardSpeed));
        double sizeProgress = 0.90D + 0.42D * coneProgress;
        double textureScale = roofTextureScale(currentLevel.random.nextDouble());
        float size = (float) (Math.max(0.16D, width)
                * sizeProgress * textureScale * lifecycleScale);
        float alpha = (float) ((0.56D + currentLevel.random.nextDouble() * 0.28D) * lifecycleScale);
        int lifetime = 8 + currentLevel.random.nextInt(9);
        int textureIndex = currentLevel.random.nextInt(RVP_WreckFlameParticle.textureCount());
        float red = 1.0F;
        float green = 0.32F + currentLevel.random.nextFloat() * 0.34F;
        float blue = 0.03F + currentLevel.random.nextFloat() * 0.10F;
        // 调用本项目粒子工厂：沿柱向发射并加入客户端粒子引擎，不改变载具物理或服务端状态。
        // 调用本项目有界喷燃粒子入口：把速度与目标高度解耦，防止高速粒子冲出可预测火柱范围。
        RVP_WreckFlameParticle.spawnBounded(Minecraft.getInstance().particleEngine, currentLevel,
                position, velocity, flameOrigin, axis, travel,
                size, alpha, lifetime, textureIndex, red, green, blue);
    }

    /** 创建一枚炮口灰烟；初速度沿炮管，粒子自身持续逼近世界竖直上浮速度。 */
    private static void spawnMuzzleSmokeParticle(RenderColumn column, Pose pose, Vec3 axis,
                                                 double length, double width, double lifecycleScale, int layer) {
        Vec3 side = axis.cross(new Vec3(0.0D, 1.0D, 0.0D));
        if (side.lengthSqr() < 1.0E-8D) {
            side = axis.cross(new Vec3(1.0D, 0.0D, 0.0D));
        }
        side = side.normalize();
        Vec3 cross = axis.cross(side).normalize();
        double travel = Math.min(length * 0.72D, 3.0D);
        int layerCount = muzzleSmokeLayerCount(travel, lifecycleScale);
        // 调用本类连续分层入口：每个 tick 按轴向等距覆盖炮口到喷出段，不再把烟片随机撒在整段范围。
        double along = muzzleSmokeLayerPosition(travel, layer, layerCount);
        Vec3 position = pose.position().add(axis.scale(along))
                .add(side.scale((currentLevel.random.nextDouble() - 0.5D) * width))
                .add(cross.scale((currentLevel.random.nextDouble() - 0.5D) * width));
        double axisSpeed = Math.max(0.0D, Math.min(2.0D, RVP_WreckCookoffSettings.muzzleSmokeAxisSpeed));
        double updraft = Math.max(0.0D, Math.min(1.0D, RVP_WreckCookoffSettings.muzzleSmokeUpdraft));
        double spread = Math.max(0.0D, Math.min(0.25D, RVP_WreckCookoffSettings.muzzleSmokeSpread));
        Vec3 velocity = axis.scale(axisSpeed)
                .add(RVP_WreckCookoffGeometry.UP.scale(updraft))
                .add(side.scale((currentLevel.random.nextDouble() - 0.5D) * spread))
                .add(cross.scale((currentLevel.random.nextDouble() - 0.5D) * spread));
        float size = (float) (Math.max(0.1D, RVP_WreckCookoffSettings.muzzleSmokeSize)
                * (0.85D + currentLevel.random.nextDouble() * 0.30D) * lifecycleScale);
        float alpha = (float) (Math.max(0.0D, Math.min(1.0D, RVP_WreckCookoffSettings.muzzleSmokeAlpha))
                * (0.85D + currentLevel.random.nextDouble() * 0.30D) * lifecycleScale);
        int lifetime = (int) Math.round(Math.max(4.0D,
                Math.min(120.0D, RVP_WreckCookoffSettings.muzzleSmokeLifetime)));
        float grey = (float) Math.max(0.02D, Math.min(0.5D, RVP_WreckCookoffSettings.muzzleSmokeGrey));
        // 调用本项目炮口灰烟自绘管理器：复用已有近处烟贴图，脱离 ParticleEngine 批次，
        // 由 AFTER_PARTICLES 阶段按距离排序自绘（与炮口白烟同一管线，深度三难正解）。
        int textureCount = RVP_WreckFlameParticle.textureCount();
        RVP_WreckMuzzleSmokeSelfRenderer.spawn(currentLevel,
                position, velocity, size, alpha, lifetime,
                Math.floorMod(layer + currentLevel.random.nextInt(textureCount), textureCount),
                updraft, spread, grey);
    }

    /** 返回单个出口需要的连续效果层数；车顶按最大目标高度铺满，炮口按烟片尺寸连续覆盖。 */
    private static int effectLayerCount(RenderColumn column) {
        if (!column.isRoofColumn()) {
            double travel = Math.min(Math.max(0.1D, column.length()) * 0.72D, 3.0D);
            return muzzleSmokeLayerCount(travel, column.lifecycleScale());
        }
        double width = Math.max(0.08D, Math.min(1.4D, column.width() * 0.35D));
        double targetHeight = roofFlameTargetHeight(column.length(), 1.0D);
        return roofFlameLayerCount(targetHeight, width, 1.0D);
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

        /** 所属残骸车辆 UUID；供喷燃粒子单车预算使用。 */
        UUID vehicleId();

        /** 是否为车顶舱盖喷口；车顶喷燃使用快速轴向速度和倒梯形圆锥分布。 */
        boolean isRoofColumn();
    }

    /** 舱盖/接缝出口的殉燃效果视图。 */
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

        @Override
        public UUID vehicleId() {
            return column.vehicleId;
        }

        @Override
        public boolean isRoofColumn() {
            return column.anchor.kind() == Kind.HATCH;
        }
    }

    /** 炮口灰烟视图；炮口不参与火星，轴向范围取炮口设置。 */
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
            // 炮口灰烟初始方向沿炮管轴向，与开火方向一致
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

        @Override
        public UUID vehicleId() {
            return muzzle.vehicleId;
        }

        @Override
        public boolean isRoofColumn() {
            return false;
        }
    }

    /**
     * 公平分配喷燃出口：每轮每车各取一根，最多 {@code MAX_COLUMNS} 根；远处降低每车根数。
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
        // 调用本项目预览入口：允许用户重新预览此前已完成定时殉燃的同一辆载具。
        COMPLETED.remove(vehicle.getUUID());
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
        COMPLETED.clear();
        LONG_SMOKE_UNLOCK_TICKS.clear();
        preview = null;
        previewEnd = 0;
        currentLevel = null;
        lastTick = Long.MIN_VALUE;
        emitted = 0;
        flameEmitted = 0;
        muzzleSmokeEmitted = 0;
    }

    /** 调试状态，不暴露或修改服务端实体。 */
    public static String describe() {
        return "自动适配地面载具；活动残骸=" + INSTANCES.size() + "；上 tick 火星生成尝试数=" + emitted
                + "/" + RVP_WreckCookoffBudget.PARTICLES_PER_TICK + "；height=" + RVP_WreckCookoffSettings.height
                + " 格；length=" + RVP_WreckCookoffSettings.length
                + " 格；density=" + RVP_WreckCookoffSettings.density
                + "；车顶火焰=" + flameEmitted + "/" + RVP_WreckCookoffBudget.EFFECT_PARTICLES_PER_TICK
                + "；炮管灰烟=" + muzzleSmokeEmitted + "/" + RVP_WreckCookoffBudget.EFFECT_PARTICLES_PER_TICK
                + "；grow=" + RVP_WreckCookoffSettings.growthTicks
                + " tick；shrink=" + RVP_WreckCookoffSettings.shrinkTicks + " tick"
                + "；roof_speed=" + RVP_WreckCookoffSettings.roofAxisSpeedMin
                + "～" + RVP_WreckCookoffSettings.roofAxisSpeedMax + " 格/tick"
                + "；roof_cone=" + RVP_WreckCookoffSettings.roofConeBase
                + "→" + RVP_WreckCookoffSettings.roofConeTip
                + "；roof_outward=" + RVP_WreckCookoffSettings.roofOutwardSpeed + " 格/tick"
                + "；roof_scale=" + RVP_WreckCookoffSettings.roofTextureScaleMin
                + "～" + RVP_WreckCookoffSettings.roofTextureScaleMax
                + "；roof_vertical_offset=" + RVP_WreckCookoffSettings.roofVerticalOffset + " 格"
                + "；muzzle_smoke_density=" + RVP_WreckCookoffSettings.muzzleSmokeDensity
                + "；muzzle_smoke_size=" + RVP_WreckCookoffSettings.muzzleSmokeSize + " 格"
                + "；muzzle_smoke_lifetime=" + RVP_WreckCookoffSettings.muzzleSmokeLifetime + " tick"
                + "；muzzle_smoke_axis_speed=" + RVP_WreckCookoffSettings.muzzleSmokeAxisSpeed + " 格/tick"
                + "；muzzle_smoke_updraft=" + RVP_WreckCookoffSettings.muzzleSmokeUpdraft + " 格/tick"
                + "；muzzle_smoke_spread=" + RVP_WreckCookoffSettings.muzzleSmokeSpread + " 格/tick"
                + "；muzzle_smoke_alpha=" + RVP_WreckCookoffSettings.muzzleSmokeAlpha
                + "；muzzle_smoke_grey=" + RVP_WreckCookoffSettings.muzzleSmokeGrey;
    }
}
