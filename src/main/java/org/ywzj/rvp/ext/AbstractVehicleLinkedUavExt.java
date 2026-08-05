package org.ywzj.rvp.ext;

import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

/**
 * 为载具实例附加 deployable UAV 主从关系与实例级 UAV 状态。
 */
public interface AbstractVehicleLinkedUavExt {
    UUID ywzj_rvp$getLinkedParentVehicleUuid();
    void ywzj_rvp$setLinkedParentVehicleUuid(UUID uuid);

    UUID ywzj_rvp$getLinkedChildVehicleUuid();
    void ywzj_rvp$setLinkedChildVehicleUuid(UUID uuid);

    UUID ywzj_rvp$getLinkedLauncherVehicleUuid();
    void ywzj_rvp$setLinkedLauncherVehicleUuid(UUID uuid);

    boolean ywzj_rvp$isDeployableUavInstance();
    void ywzj_rvp$setDeployableUavInstance(boolean value);

    boolean ywzj_rvp$isDeployableUavControlSwitchAllowed();
    void ywzj_rvp$setDeployableUavControlSwitchAllowed(boolean value);

    int ywzj_rvp$getReturnSeatIndex();
    void ywzj_rvp$setReturnSeatIndex(int seatIndex);

    String ywzj_rvp$getDeployableUavRole();
    void ywzj_rvp$setDeployableUavRole(String role);

    String ywzj_rvp$getDatalinkRole();
    void ywzj_rvp$setDatalinkRole(String role);

    /** 无人机最近同步到的母车（父车）世界位置；母车实体卸载（离开视距）时仍可用来传送回母车旁。 */
    @Nullable
    Vec3 ywzj_rvp$getLinkedParentLastPosition();

    void ywzj_rvp$setLinkedParentLastPosition(@Nullable Vec3 position);

    /** 玩家驾驶无人机期间被锁定的母车座位索引（-1 = 无锁）。 */
    int ywzj_rvp$getSeatLockSeatIndex();

    void ywzj_rvp$setSeatLockSeatIndex(int seatIndex);

    /** 座位锁的持有玩家实体 ID（仅该玩家可坐回被锁座位）。 */
    int ywzj_rvp$getSeatLockOwnerPlayerId();

    void ywzj_rvp$setSeatLockOwnerPlayerId(int playerId);
}
