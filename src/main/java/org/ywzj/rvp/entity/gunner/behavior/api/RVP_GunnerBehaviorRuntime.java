package org.ywzj.rvp.entity.gunner.behavior.api;

import org.jetbrains.annotations.Nullable;
import org.ywzj.rvp.entity.gunner.behavior.debug.RVP_GunnerBehaviorDebugSnapshot;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;
import org.ywzj.vehicle.vehicle.part.WeaponUnit;

import java.lang.ref.WeakReference;
import java.util.HashMap;
import java.util.Map;

/** Gunner 跨 tick 的行为运行时容器，按行为实例 ID 隔离临时状态。 */
public final class RVP_GunnerBehaviorRuntime {

    /** 行为实例 ID 到强类型状态对象的映射。 */
    private final Map<String, Object> states = new HashMap<>();
    /** 上 tick 载具弱引用，仅用于离座/Profile 切换时释放动作租约。 */
    private WeakReference<AbstractVehicle> previousVehicle = new WeakReference<>(null);
    /** 上 tick 武器站弱引用，用于退出时清理本车/外置雷达与制导状态。 */
    private WeakReference<WeaponUnit> previousWeaponUnit = new WeakReference<>(null);
    /** 上 tick Profile ID。 */
    private String profileId = "";
    /** 上 tick Profile 加载代次。 */
    private long profileGeneration = -1L;
    /** 最近一次调试快照。 */
    private RVP_GunnerBehaviorDebugSnapshot debugSnapshot = RVP_GunnerBehaviorDebugSnapshot.empty();

    /** 判断本 tick 是否发生换车、换 Profile 或资源重载。 */
    public boolean requiresExit(RVP_GunnerBehaviorContext context) {
        AbstractVehicle previous = previousVehicle.get();
        return previous != null && (previous != context.vehicle()
                || !profileId.equals(context.profileId())
                || profileGeneration != context.profileGeneration());
    }

    /** 记录本 tick 身份，用弱引用避免跨 tick 强持有载具实体。 */
    public void bind(RVP_GunnerBehaviorContext context) {
        previousVehicle = new WeakReference<>(context.vehicle());
        previousWeaponUnit = new WeakReference<>(context.weaponUnit());
        profileId = context.profileId();
        profileGeneration = context.profileGeneration();
    }

    /** 返回上 tick 载具；可能已被回收。 */
    @Nullable
    public AbstractVehicle previousVehicle() {
        return previousVehicle.get();
    }

    /** 返回上 tick 武器站；可能已被回收。 */
    @Nullable
    public WeaponUnit previousWeaponUnit() {
        return previousWeaponUnit.get();
    }

    /** 获取行为实例的强类型状态；类型不匹配时返回 null。 */
    @Nullable
    public <T> T getState(String behaviorId, Class<T> type) {
        Object state = states.get(behaviorId);
        return type.isInstance(state) ? type.cast(state) : null;
    }

    /** 写入行为实例状态。 */
    public void putState(String behaviorId, Object state) {
        states.put(behaviorId, state);
    }

    /** 删除一个已退出行为实例的状态。 */
    public void removeState(String behaviorId) {
        states.remove(behaviorId);
    }

    /** 清除全部行为状态及绑定信息。 */
    public void clear() {
        states.clear();
        previousVehicle.clear();
        previousWeaponUnit.clear();
        profileId = "";
        profileGeneration = -1L;
        debugSnapshot = RVP_GunnerBehaviorDebugSnapshot.empty();
    }

    /** 返回最近一次调试快照。 */
    public RVP_GunnerBehaviorDebugSnapshot debugSnapshot() {
        return debugSnapshot;
    }

    /** 更新最近一次调试快照。 */
    public void setDebugSnapshot(RVP_GunnerBehaviorDebugSnapshot debugSnapshot) {
        this.debugSnapshot = debugSnapshot;
    }
}
