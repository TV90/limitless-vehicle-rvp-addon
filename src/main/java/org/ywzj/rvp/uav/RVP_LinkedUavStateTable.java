package org.ywzj.rvp.uav;

import net.minecraft.world.phys.Vec3;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
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
        String linkedParentDimension = "";
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

    /** 将父子/部署身份写入实体 NBT；静态表只作为当前服务端运行缓存。 */
    public static void persist(AbstractVehicle vehicle) {
        State s = vehicle == null ? null : STATES.get(vehicle.getUUID());
        if (s == null) return;
        CompoundTag tag = new CompoundTag();
        if (s.linkedParentVehicleUuid != null) tag.putUUID("parent", s.linkedParentVehicleUuid);
        if (s.linkedChildVehicleUuid != null) tag.putUUID("child", s.linkedChildVehicleUuid);
        if (s.linkedLauncherVehicleUuid != null) tag.putUUID("launcher", s.linkedLauncherVehicleUuid);
        if (!s.linkedParentDimension.isBlank()) tag.putString("parentDimension", s.linkedParentDimension);
        tag.putBoolean("deployable", s.deployableUavInstance);
        tag.putBoolean("switchAllowed", s.deployableUavAllowControlSwitch);
        tag.putInt("returnSeat", s.returnSeatIndex);
        tag.putString("role", s.deployableUavRole);
        tag.putString("datalinkRole", s.datalinkRole);
        if (s.fakeOperatorPosition != null) {
            tag.putDouble("fakeX", s.fakeOperatorPosition.x);
            tag.putDouble("fakeY", s.fakeOperatorPosition.y);
            tag.putDouble("fakeZ", s.fakeOperatorPosition.z);
            tag.putBoolean("hasFakePosition", true);
        }
        vehicle.getPersistentData().put("rvpLinkedUav", tag);
    }

    /** 从实体 NBT 恢复关系；不在这里操作座位、部件或配置，join 时机早于本体初始化。 */
    public static void restore(AbstractVehicle vehicle) {
        if (vehicle == null) return;
        CompoundTag tag = vehicle.getPersistentData().getCompound("rvpLinkedUav");
        if (tag.isEmpty()) return;
        State s = STATES.computeIfAbsent(vehicle.getUUID(), ignored -> new State());
        s.linkedParentVehicleUuid = tag.hasUUID("parent") ? tag.getUUID("parent") : null;
        s.linkedChildVehicleUuid = tag.hasUUID("child") ? tag.getUUID("child") : null;
        s.linkedLauncherVehicleUuid = tag.hasUUID("launcher") ? tag.getUUID("launcher") : null;
        s.linkedParentDimension = tag.getString("parentDimension");
        s.deployableUavInstance = tag.getBoolean("deployable");
        s.deployableUavAllowControlSwitch = !tag.contains("switchAllowed") || tag.getBoolean("switchAllowed");
        s.returnSeatIndex = tag.contains("returnSeat") ? tag.getInt("returnSeat") : -1;
        s.deployableUavRole = tag.contains("role") ? tag.getString("role") : "none";
        s.datalinkRole = tag.contains("datalinkRole") ? tag.getString("datalinkRole") : "none";
        s.fakeOperatorPosition = tag.getBoolean("hasFakePosition")
                ? new Vec3(tag.getDouble("fakeX"), tag.getDouble("fakeY"), tag.getDouble("fakeZ")) : null;
    }

    /** 服务器切换前清除内存缓存，实体 NBT 已在 unload path 保存。 */
    public static void clearRuntime() {
        STATES.clear();
    }

    /** 永久移除或终态墓碑处理时清理所有父子/部署身份字段。 */
    public static void clearLinks(AbstractVehicle vehicle) {
        State s = vehicle == null ? null : STATES.get(vehicle.getUUID());
        if (s == null) return;
        s.linkedParentVehicleUuid = null;
        s.linkedChildVehicleUuid = null;
        s.linkedLauncherVehicleUuid = null;
        s.linkedParentDimension = "";
        s.deployableUavInstance = false;
        s.returnSeatIndex = -1;
        s.fakeOperatorPosition = null;
        s.deployableUavRole = "none";
        s.datalinkRole = "none";
        persist(vehicle);
    }

    public static void clearPersistent(AbstractVehicle vehicle) {
        if (vehicle != null) vehicle.getPersistentData().remove("rvpLinkedUav");
    }

    public static UUID getLinkedParentVehicleUuid(AbstractVehicle vehicle) {
        State state = of(vehicle);
        return state == null ? null : state.linkedParentVehicleUuid;
    }

    public static void setLinkedParentVehicleUuid(AbstractVehicle vehicle, UUID uuid) {
        State state = of(vehicle);
        if (state != null) {
            state.linkedParentVehicleUuid = uuid;
            if (uuid == null) state.linkedParentDimension = "";
            persist(vehicle);
        }
    }

    public static void setLinkedParentDimension(AbstractVehicle vehicle, ResourceLocation dimension) {
        State state = of(vehicle);
        if (state != null) {
            state.linkedParentDimension = dimension == null ? "" : dimension.toString();
            persist(vehicle);
        }
    }

    public static ResourceLocation getLinkedParentDimension(AbstractVehicle vehicle) {
        State state = vehicle == null ? null : STATES.get(vehicle.getUUID());
        if (state == null || state.linkedParentDimension.isBlank()) return null;
        return ResourceLocation.tryParse(state.linkedParentDimension);
    }

    public static UUID getLinkedChildVehicleUuid(AbstractVehicle vehicle) {
        State state = vehicle == null ? null : STATES.get(vehicle.getUUID());
        return state == null ? null : state.linkedChildVehicleUuid;
    }

    public static void setLinkedChildVehicleUuid(AbstractVehicle vehicle, UUID uuid) {
        State state = of(vehicle);
        if (state != null) {
            state.linkedChildVehicleUuid = uuid;
            persist(vehicle);
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
            persist(vehicle);
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
            persist(vehicle);
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
            persist(vehicle);
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
            persist(vehicle);
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
            persist(vehicle);
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
            persist(vehicle);
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
            persist(vehicle);
        }
    }
}
