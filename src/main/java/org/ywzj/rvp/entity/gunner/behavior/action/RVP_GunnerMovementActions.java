package org.ywzj.rvp.entity.gunner.behavior.action;

import org.ywzj.rvp.entity.gunner.GunnerEntity;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;
import org.ywzj.vehicle.entity.vehicle.RotaryWingVehicle;
import org.ywzj.vehicle.vehicle.control.ControlUnit;

/** 将一个已经确定的移动命令一次性写入本体 {@link ControlUnit}。 */
public final class RVP_GunnerMovementActions {

    /**
     * 单 tick 移动命令。
     *
     * <p>字段保持与本体 ControlUnit 一一对应；算法只修改本命令，不能直接写载具。</p>
     */
    public static final class Command {
        /** 是否前进。 */
        public boolean forward;
        /** 是否倒车。 */
        public boolean backward;
        /** 是否向左转向。 */
        public boolean left;
        /** 是否向右转向。 */
        public boolean right;
        /** 是否增加升力或上升。 */
        public boolean up;
        /** 是否减小升力或下降。 */
        public boolean down;
        /** 是否执行固定翼左偏航输入。 */
        public boolean leftYaw;
        /** 是否执行固定翼右偏航输入。 */
        public boolean rightYaw;
        /** 目标俯仰角（度）。 */
        public float xRot;
        /** 是否保持当前俯仰角。 */
        public boolean xRotKeep;
        /** 目标偏航角（度）。 */
        public float yRot;
        /** 是否保持当前偏航角。 */
        public boolean yRotKeep;
        /** 是否需要同时切换旋翼机悬停模式。 */
        public boolean setHoverMode;
        /** 旋翼机期望悬停模式，仅在 setHoverMode 为 true 时生效。 */
        public boolean hoverMode;
    }

    RVP_GunnerMovementActions() {
    }

    /** 创建默认全 false 的显式停车命令。 */
    public Command stopCommand() {
        return new Command();
    }

    /**
     * 清空上 tick 输入并应用本 tick 唯一移动命令。
     *
     * @return 司机身份有效时为 EXECUTED，否则为 NOT_DRIVER
     */
    public RVP_GunnerActionResult apply(GunnerEntity gunner, AbstractVehicle vehicle, Command command) {
        if (gunner == null || vehicle == null || command == null || !vehicle.isAlive()) {
            return RVP_GunnerActionResult.INVALID;
        }
        // 调用本体司机查询，确保非司机行为不能写真实移动控制。
        if (!isDriver(vehicle, gunner)) {
            return RVP_GunnerActionResult.NOT_DRIVER;
        }
        ControlUnit control = vehicle.controlUnit;
        // 调用本体 ControlUnit.reset，保证每 tick 只在动作边界统一清理旧输入。
        control.reset();
        control.forward = command.forward;
        control.backward = command.backward;
        control.left = command.left;
        control.right = command.right;
        control.up = command.up;
        control.down = command.down;
        control.leftYaw = command.leftYaw;
        control.rightYaw = command.rightYaw;
        control.xRot = command.xRot;
        control.xRotKeep = command.xRotKeep;
        control.yRot = command.yRot;
        control.yRotKeep = command.yRotKeep;
        if (command.setHoverMode && vehicle instanceof RotaryWingVehicle rotaryWingVehicle) {
            rotaryWingVehicle.hoverMode = command.hoverMode;
        }
        return RVP_GunnerActionResult.EXECUTED;
    }

    /** 使用本体司机入口并兼容座位表尚未完成 driver 缓存解析的时刻。 */
    private static boolean isDriver(AbstractVehicle vehicle, GunnerEntity gunner) {
        if (vehicle.getDriver() == gunner) {
            return true;
        }
        for (AbstractVehicle.Seat seat : vehicle.seats) {
            if (seat.passengerId == gunner.getId()) {
                return seat.seatIndex == 0;
            }
        }
        return false;
    }
}
