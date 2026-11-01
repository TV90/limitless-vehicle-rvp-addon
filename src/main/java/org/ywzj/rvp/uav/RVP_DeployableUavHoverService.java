package org.ywzj.rvp.uav;

import org.ywzj.rvp.event.RVP_AutoCollectiveHandler;
import org.ywzj.rvp.helidock.RVP_HeliDockManager;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;
import org.ywzj.vehicle.entity.vehicle.RotaryWingVehicle;
import org.ywzj.vehicle.util.PhysicsHelper;

/** 部署旋翼机的无人值守模式：使用本体悬停，重新接管时交还手动控制。 */
public final class RVP_DeployableUavHoverService {
    /** 自动悬停的所有权标记；随实体 NBT 保存，用于卸载/重启后恢复及区分玩家手动悬停。 */
    private static final String AUTO_HOVER_KEY = "rvpDeployableAutoHover";

    private RVP_DeployableUavHoverService() {}

    /** 新部署旋翼机在首个物理 tick 前启动旋翼并设置平衡总距，避免空中等待接管时自由落体。 */
    public static void initializeDeployment(RotaryWingVehicle vehicle) {
        if (vehicle.level().isClientSide() || vehicle.isDestroyed() || vehicle.isRemoved()) return;
        // 调用本体动力接口，只在新部署时预热；后续悬停仍遵守燃油、损伤和发动机出力限制。
        vehicle.toggleEngine(true);
        vehicle.setPower(100f);
        // 读取本体注入后的质量、旋翼力、阻力和高度效率，求零速水平姿态的起始总距。
        vehicle.setCollectivePitch(initialCollectivePitch(vehicle.physicsEngine.physicsInfo.mass,
                vehicle.mainRotorForce, vehicle.physicsEngine.physicsInfo.friction,
                vehicle.getY(), vehicle.ceiling));
        tick(vehicle);
    }

    /**
     * 既由成功切换调用，也由已有联动 tick 调用。后者覆盖主动下车、跨维回传、离线及重新加载；
     * 不在可取消的 Mount 事件中改模式，避免切换失败却夺走驾驶员控制权。
     */
    public static void tick(AbstractVehicle child) {
        if (!(child instanceof RotaryWingVehicle vehicle) || vehicle.level().isClientSide()
                || !RVP_LinkedUavStateTable.isDeployableUavInstance(vehicle)) return;
        if (vehicle.isDestroyed() || vehicle.isRemoved() || !vehicle.isAlive()) return;

        // 清除历史旋翼盘旋计划，防其屏蔽驾驶输入或在重载后重新抢占悬停控制。
        if (RVP_UavLoiterManager.get(vehicle.getUUID()) != null) {
            RVP_UavLoiterTickService.stopLoiterAndResetControls(vehicle);
            RVP_UavLoiterManager.remove(vehicle.getUUID());
            RVP_UavLoiterManager.clearPersistent(vehicle);
        }
        boolean managedHover = vehicle.getPersistentData().getBoolean(AUTO_HOVER_KEY);
        // 着舰状态机已有独占飞控；只有本服务设置的悬停才能在交接时被关闭。
        boolean reserved = RVP_HeliDockManager.isControlLocked(vehicle.getUUID());
        // 读取实际驾驶关系，不能把旧 operator 引用或非驾驶位乘客当成仍在驾驶。
        var operator = vehicle.getDriver();
        boolean piloted = operator != null && operator.isAlive() && operator.getVehicle() == vehicle;
        if (reserved || piloted) {
            if (managedHover) {
                // 同步三态检测快照，防程序切换被误认成玩家按 Z。
                RVP_AutoCollectiveHandler.setHoverMode(vehicle, false);
                vehicle.getPersistentData().remove(AUTO_HOVER_KEY);
            }
            return;
        }
        if (!managedHover || !vehicle.hoverMode) {
            // 清空离机时锁存的总距/俯仰/滚转/偏航输入，再交给本体回平和垂直速度阻尼。
            vehicle.controlUnit.reset();
            vehicle.controlUnit.yRot = vehicle.getYRot();
            // 自动进入悬停不补燃油、不改速度、不瞬移；发动机正常爬升出力。
            vehicle.toggleEngine(true);
            RVP_AutoCollectiveHandler.setHoverMode(vehicle, true);
            vehicle.getPersistentData().putBoolean(AUTO_HOVER_KEY, true);
        }
    }

    /**
     * 零速水平旋翼的静态总距百分比（0..100）。公式对应本体 tickMove 的推力→线性阻力→重力；
     * 高度单位格、质量 kg、旋翼力 N、阻力沿用本体 friction 系数。只用于部署初值，不替代后续运动学。
     */
    static float initialCollectivePitch(double mass, double rotorForce, double friction,
                                        double altitude, double ceiling) {
        if (!Double.isFinite(mass) || mass <= 0 || !Double.isFinite(rotorForce) || rotorForce <= 0
                || !Double.isFinite(friction) || friction < 0 || !Double.isFinite(altitude)
                || !Double.isFinite(ceiling) || ceiling <= 64) return 100f;
        double airFactor = altitude < 64 ? 1.0
                : Math.pow(Math.max(0, ceiling - altitude) / (ceiling - 64), 0.2);
        double retainedSpeed = 1.0 - friction / mass / PhysicsHelper.TICKS_PER_SECOND_SQUARED;
        double availableForce = rotorForce * airFactor * retainedSpeed;
        if (availableForce <= 0) return 100f;
        return (float) Math.min(100.0, 100.0 * mass * PhysicsHelper.GRAVITY / availableForce);
    }
}
