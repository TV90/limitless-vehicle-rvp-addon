package org.ywzj.rvp.uav;

import net.minecraft.world.phys.Vec3;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 联动 UAV 实例状态侧表（替代被删 {@code AbstractVehicleLinkedUavMixin} 注入的实例字段）。
 *
 * <p>按载具实体 UUID 存储父/子/发射车 UUID、部署实例标记、控制切换允许、返回座位、
 * 角色等状态；{@link RVP_DeployableUavLinkRegistry} 继续负责父↔子映射的运行时注册表。</p>
 */
public final class RVP_LinkedUavStateTable {

    private static final ConcurrentHashMap<UUID, State> STATES = new ConcurrentHashMap<>();

    private static final class State {
        UUID linkedParentVehicleUuid;
        UUID linkedChildVehicleUuid;
        UUID linkedLauncherVehicleUuid;
        boolean deployableUavInstance;
        boolean deployableUavAllowControlSwitch = true;
        int returnSeatIndex = -1;
        String deployableUavRole = "none";
        String datalinkRole = "none";
        Vec3 fakeOperatorPosition;
    }

    private RVP_LinkedUavStateTable() {}

    private static State of(AbstractVehicle vehicle) {
        if (vehicle == null) {
            return null;
        }
        return STATES.computeIfAbsent(vehicle.getUUID(), ignored -> new State());
    }

    /** 载具实体移除/离开世界时清理其条目。 */
    public static void remove(AbstractVehicle vehicle) {
        if (vehicle != null) {
            STATES.remove(vehicle.getUUID());
        }
    }

    public static UUID getLinkedParentVehicleUuid(AbstractVehicle vehicle) {
        State state = of(vehicle);
        return state == null ? null : state.linkedParentVehicleUuid;
    }

    public static void setLinkedParentVehicleUuid(AbstractVehicle vehicle, UUID uuid) {
        State state = of(vehicle);
        if (state != null) {
            state.linkedParentVehicleUuid = uuid;
        }
    }

    public static UUID getLinkedChildVehicleUuid(AbstractVehicle vehicle) {
        State state = vehicle == null ? null : STATES.get(vehicle.getUUID());
        return state == null ? null : state.linkedChildVehicleUuid;
    }

    public static void setLinkedChildVehicleUuid(AbstractVehicle vehicle, UUID uuid) {
        State state = of(vehicle);
        if (state != null) {
            state.linkedChildVehicleUuid = uuid;
        }
    }

    public static UUID getLinkedLauncherVehicleUuid(AbstractVehicle vehicle) {
        State state = of(vehicle);
        return state == null ? null : state.linkedLauncherVehicleUuid;
    }

    public static void setLinkedLauncherVehicleUuid(AbstractVehicle vehicle, UUID uuid) {
        State state = of(vehicle);
        if (state != null) {
            state.linkedLauncherVehicleUuid = uuid;
        }
    }

    public static boolean isDeployableUavInstance(AbstractVehicle vehicle) {
        State state = vehicle == null ? null : STATES.get(vehicle.getUUID());
        return state != null && state.deployableUavInstance;
    }

    public static void setDeployableUavInstance(AbstractVehicle vehicle, boolean value) {
        State state = of(vehicle);
        if (state != null) {
            state.deployableUavInstance = value;
        }
    }

    public static boolean isDeployableUavControlSwitchAllowed(AbstractVehicle vehicle) {
        State state = of(vehicle);
        return state == null || state.deployableUavAllowControlSwitch;
    }

    public static void setDeployableUavControlSwitchAllowed(AbstractVehicle vehicle, boolean value) {
        State state = of(vehicle);
        if (state != null) {
            state.deployableUavAllowControlSwitch = value;
        }
    }

    public static int getReturnSeatIndex(AbstractVehicle vehicle) {
        State state = of(vehicle);
        return state == null ? -1 : state.returnSeatIndex;
    }

    public static void setReturnSeatIndex(AbstractVehicle vehicle, int seatIndex) {
        State state = of(vehicle);
        if (state != null) {
            state.returnSeatIndex = seatIndex;
        }
    }

    public static String getDeployableUavRole(AbstractVehicle vehicle) {
        State state = of(vehicle);
        return state == null ? "none" : state.deployableUavRole;
    }

    public static void setDeployableUavRole(AbstractVehicle vehicle, String role) {
        State state = of(vehicle);
        if (state != null) {
            state.deployableUavRole = role == null ? "none" : role;
        }
    }

    public static String getDatalinkRole(AbstractVehicle vehicle) {
        State state = of(vehicle);
        return state == null ? "none" : state.datalinkRole;
    }

    public static void setDatalinkRole(AbstractVehicle vehicle, String role) {
        State state = of(vehicle);
        if (state != null) {
            state.datalinkRole = role == null ? "none" : role;
        }
    }

    public static Vec3 getFakeOperatorPosition(AbstractVehicle vehicle) {
        State state = of(vehicle);
        return state == null ? null : state.fakeOperatorPosition;
    }

    public static void setFakeOperatorPosition(AbstractVehicle vehicle, Vec3 position) {
        State state = of(vehicle);
        if (state != null) {
            state.fakeOperatorPosition = position;
        }
    }
}
