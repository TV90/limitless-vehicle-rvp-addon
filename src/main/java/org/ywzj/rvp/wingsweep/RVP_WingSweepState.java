package org.ywzj.rvp.wingsweep;

import net.minecraftforge.event.entity.EntityMountEvent;
import net.minecraftforge.event.entity.EntityLeaveLevelEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.ywzj.rvp.RVP_MOD;
import org.ywzj.rvp.config.RVP_WingSweepConfig;
import org.ywzj.rvp.config.RVP_WingSweepConfigManager;
import org.ywzj.vehicle.api.event.VehicleMoveEvent;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;
import org.ywzj.vehicle.entity.vehicle.FixedWingVehicle;
import org.ywzj.vehicle.vehicle.part.SwitchableUnit;
import org.slf4j.Logger;
import com.mojang.logging.LogUtils;

import javax.annotation.Nullable;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * [RVP] 可变后掠翼状态管理（服务端唯一事实源，零 Mixin：本体公共事件 + 公共方法）。
 *
 * <p>机制（审核定稿 v3）：载具 JSON 放置两个隐藏 {@code ywzj_vehicle:switchable} 部件
 * （{@value #PART_MANUAL_ID}=手动接管、{@value #PART_FORM_ID}=后掠态）作为同步状态载体；
 * 本类在服务端每载具每 tick（本体 {@link VehicleMoveEvent}，{@code AbstractVehicle.java:523}
 * 服务端发布）判定目标形态并维护部件 {@code setOn}——部件经本体 sync-data 脏同步自动到达
 * 客户端，动画控制器 {@code context.isPartOn(...)} 直接消费；同时按形态把气动乘子直写
 * {@code FixedWingVehicle} 的 public 逐实例字段（{@code liftToDragK/airDragKMin/Max/
 * turnRateBySpeed}，本体 {@code FixedWingVehicleData.apply} 按值拷贝后不再改写），本体
 * 气动公式自动生效，无需复刻公式。</p>
 *
 * <p>边界（agents.md 合规）：零 Mixin/零 Accessor/零新增反射；全部经本体公共事件与
 * 公共方法（{@code setOn/getBolts 类比}，参照本体 Ztz99a 直改 Bolt 先例）；未放置隐藏
 * 部件的载具零影响。乘子插值 {@value #BLEND_TICKS} tick，与动画 {@code duration:2} 对齐。</p>
 */
@Mod.EventBusSubscriber(modid = RVP_MOD.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class RVP_WingSweepState {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** 隐藏部件 id：on=手动接管（协议约定，与数据包载具 JSON parts 严格一致）。 */
    public static final String PART_MANUAL_ID = "wing_sweep_manual";
    /** 隐藏部件 id：on=后掠态（协议约定）。 */
    public static final String PART_FORM_ID = "wing_sweep_form";
    /** 乘子插值 tick 数：与动画切换 {@code duration:2} 秒对齐，避免物理阶跃。 */
    private static final int BLEND_TICKS = 40;

    /** 每载具运行时状态（仅服务端线程访问：VehicleMoveEvent/MountEvent 均在主线程发布）。 */
    private static final Map<AbstractVehicle, SweepRuntime> STATES = new ConcurrentHashMap<>();

    private RVP_WingSweepState() {}

    /** 单载具运行时状态。 */
    private static final class SweepRuntime {
        /** true=手动接管（↑ 进入，↓ 退出）。 */
        boolean manual;
        /** 手动模式下的目标形态：true=后掠。 */
        boolean manualForm;
        /** 自动模式滞回记忆：最近一次自动判定结果（防阈值抖动）。 */
        boolean autoSwept;
        /** 物理乘子插值：0=全展开 1=全后掠（动画对齐）。 */
        float blend;
        /** 基准气动参数（首次接管该载具时捕获，本体 apply 后的原始值）。 */
        boolean captured;
        float baseLiftToDragK;
        float baseAirDragKMin;
        float baseAirDragKMax;
        float baseTurnRateBySpeed;
    }

    // ------------------------------------------------------------------
    // 事件
    // ------------------------------------------------------------------

    /**
     * 目的：服务端每载具每 tick 驱动（本体在 {@code tickPhysics(tickMove())} 后发布，
     * 服务端专属）。本事件即"每 tick 判定形态 → 维护部件 → 直写气动字段"的入口。
     */
    @SubscribeEvent
    public static void onVehicleMove(VehicleMoveEvent event) {
        tickVehicle(event.getVehicle());
    }

    /**
     * 目的：驾驶员下机时复位（手册语义"下机重上重置"）。
     * 仅当下机实体为当前驾驶员时触发；乘员/炮手下机不受影响。
     */
    @SubscribeEvent
    public static void onMount(EntityMountEvent event) {
        if (event.isMounting()
                || !(event.getEntityBeingMounted() instanceof AbstractVehicle vehicle)
                || vehicle.level().isClientSide()) {
            return;
        }
        // 目的：EntityMountEvent 在摘除完成前发布，此刻 getDriver() 仍指向下机者，
        // 借此区分"驾驶员下机"与"乘员下机"
        if (event.getEntityMounting() == vehicle.getDriver()) {
            resetVehicle(vehicle);
        }
    }

    /** 目的：载具离开世界（卸载/跨维度/消亡）时清理运行时状态，防实体引用滞留。 */
    @SubscribeEvent
    public static void onEntityLeaveLevel(EntityLeaveLevelEvent event) {
        if (event.getEntity() instanceof AbstractVehicle vehicle) {
            STATES.remove(vehicle);
        }
    }

    // ------------------------------------------------------------------
    // C2S 入口（仅驾驶员，服务端校验后调用）
    // ------------------------------------------------------------------

    /**
     * 处理手动切换请求（服务端，已校验发送者为驾驶员且载具类型合法）。
     *
     * @param up   true=↑（进入手动/切换形态），false=↓（恢复自动）
     */
    public static void handleToggle(FixedWingVehicle vehicle, boolean up) {
        SwitchableUnit<?> manualPart = switchablePart(vehicle, PART_MANUAL_ID);
        SwitchableUnit<?> formPart = switchablePart(vehicle, PART_FORM_ID);
        if (manualPart == null || formPart == null) {
            return;
        }
        SweepRuntime state = STATES.computeIfAbsent(vehicle, key -> new SweepRuntime());
        captureBase(vehicle, state);
        if (up) {
            // 语义（文档 B §1.4）：进入手动时取当前有效形态的反值；已手动则再次切换
            state.manual = true;
            state.manualForm = !formPart.isOn();
            manualPart.setOn(true);
            formPart.setOn(state.manualForm);
        } else {
            // ↓ 恢复自动：清除手动，形态立即按滞回口径重算（不等下 tick，避免回弹感）
            state.manual = false;
            manualPart.setOn(false);
            formPart.setOn(autoTarget(vehicle, state));
        }
    }

    // ------------------------------------------------------------------
    // 内部
    // ------------------------------------------------------------------

    /** 每 tick 驱动：形态判定 → 维护部件 → 乘子插值 → 字段直写。 */
    private static void tickVehicle(AbstractVehicle vehicle) {
        SwitchableUnit<?> manualPart = switchablePart(vehicle, PART_MANUAL_ID);
        SwitchableUnit<?> formPart = switchablePart(vehicle, PART_FORM_ID);
        if (manualPart == null || formPart == null) {
            // 非变后掠载具（或部件随损毁移除）：确保无残留状态后直接返回，零开销路径
            STATES.remove(vehicle);
            return;
        }
        if (vehicle.isRemoved()) {
            STATES.remove(vehicle);
            return;
        }
        if (vehicle.isDestroyed()) {
            // 目的：损毁即复位（乘员已被本体强制离机，兜底事件漏发的情况）
            resetVehicle(vehicle);
            return;
        }
        // 目的：气动字段声明在 FixedWingVehicle 上，非固定翼（理论不可达）直接清理
        if (!(vehicle instanceof FixedWingVehicle fixedWing)) {
            STATES.remove(vehicle);
            return;
        }
        SweepRuntime state = STATES.computeIfAbsent(vehicle, key -> new SweepRuntime());
        captureBase(fixedWing, state);
        // 兜底：异常路径（如掉线未触发 MountEvent）导致驾驶员空挂手动态时复位
        if (state.manual && vehicle.getDriver() == null) {
            state.manual = false;
            manualPart.setOn(false);
        }

        // 1) 目标形态：手动优先，否则按滞回双阈值自动判定
        boolean targetSwept = state.manual
                ? state.manualForm
                : autoTarget(fixedWing, state);
        // 目的：部件是动画与乘子的同步状态载体；setOn 相同值时 sync-data 不置脏、不发包
        if (formPart.isOn() != targetSwept) {
            formPart.setOn(targetSwept);
        }

        // 2) 物理乘子插值（BLEND_TICKS 对齐动画 duration:2）后直写本体逐实例字段
        float target = targetSwept ? 1f : 0f;
        float step = 1f / BLEND_TICKS;
        if (Math.abs(state.blend - target) <= step) {
            state.blend = target;
        } else {
            state.blend += Math.signum(target - state.blend) * step;
        }
        RVP_WingSweepConfig cfg = RVP_WingSweepConfigManager.get(vehicle.getVehicleId());
        fixedWing.liftToDragK = state.baseLiftToDragK * factor(cfg.liftToDragKFactor(), state.blend);
        fixedWing.airDragKMin = state.baseAirDragKMin * factor(cfg.dragKFactor(), state.blend);
        fixedWing.airDragKMax = state.baseAirDragKMax * factor(cfg.dragKFactor(), state.blend);
        fixedWing.turnRateBySpeed = state.baseTurnRateBySpeed * factor(cfg.agilityFactor(), state.blend);
    }

    /**
     * 自动模式形态判定（滞回双阈值）：空速 &gt; 阈值 → 后掠；后掠态空速 &lt; 阈值×滞回比 → 展开；
     * 两者之间保持现状。空速口径与本体动画条件一致（{@code getDeltaMovement().length()}）。
     */
    private static boolean autoTarget(FixedWingVehicle vehicle, SweepRuntime state) {
        RVP_WingSweepConfig cfg = RVP_WingSweepConfigManager.get(vehicle.getVehicleId());
        double speed = vehicle.getDeltaMovement().length();
        if (!state.autoSwept && speed > cfg.autoSpeedThreshold()) {
            state.autoSwept = true;
        } else if (state.autoSwept && speed < cfg.autoSpeedThreshold() * cfg.autoSpeedHysteresisRatio()) {
            state.autoSwept = false;
        }
        return state.autoSwept;
    }

    /**
     * 捕获基准气动参数（仅首次；本体 {@code FixedWingVehicleData.apply} 按值拷贝后的
     * 原始值）。之后每 tick 按"基准 × 乘子"重算直写，幂等且自愈（本体若重置字段，
     * 下一 tick 被纠正；捕获值不受自身写入污染）。
     */
    private static void captureBase(FixedWingVehicle vehicle, SweepRuntime state) {
        if (state.captured) {
            return;
        }
        state.baseLiftToDragK = vehicle.liftToDragK;
        state.baseAirDragKMin = vehicle.airDragKMin;
        state.baseAirDragKMax = vehicle.airDragKMax;
        state.baseTurnRateBySpeed = vehicle.turnRateBySpeed;
        state.captured = true;
    }

    /** 复位：形态部件归 false、气动字段还原基准值、丢弃运行时状态。 */
    private static void resetVehicle(AbstractVehicle vehicle) {
        SweepRuntime state = STATES.remove(vehicle);
        // 目的：部件复位（服务端改，sync-data 自动下发客户端）
        SwitchableUnit<?> manualPart = switchablePart(vehicle, PART_MANUAL_ID);
        SwitchableUnit<?> formPart = switchablePart(vehicle, PART_FORM_ID);
        if (manualPart != null && manualPart.isOn()) {
            manualPart.setOn(false);
        }
        if (formPart != null && formPart.isOn()) {
            formPart.setOn(false);
        }
        // 目的：还原基准气动参数，避免复位后残留乘子（若状态已丢失则字段本身未被改写）
        if (state != null && state.captured && vehicle instanceof FixedWingVehicle fixedWing) {
            fixedWing.liftToDragK = state.baseLiftToDragK;
            fixedWing.airDragKMin = state.baseAirDragKMin;
            fixedWing.airDragKMax = state.baseAirDragKMax;
            fixedWing.turnRateBySpeed = state.baseTurnRateBySpeed;
        }
    }

    /** 取武器站/部件（仅接受 SwitchableUnit；不存在或类型不符返回 null）。 */
    @Nullable
    private static SwitchableUnit<?> switchablePart(AbstractVehicle vehicle, String partId) {
        return vehicle.getPartUnit(partId).orElse(null) instanceof SwitchableUnit<?> unit ? unit : null;
    }

    /** 乘子插值：blend=0 → 1.0（不干预），blend=1 → 配置乘子。 */
    private static float factor(float configured, float blend) {
        return 1f + (configured - 1f) * blend;
    }
}
