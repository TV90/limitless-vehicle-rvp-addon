package org.ywzj.rvp.mixin;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.ywzj.rvp.ext.AbstractVehicleLinkedUavExt;
import org.ywzj.rvp.uav.RVP_DeployableUavService;
import org.ywzj.vehicle.all.AllEntities;
import org.ywzj.vehicle.entity.misc.FakePlayer;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;
import org.ywzj.vehicle.util.EntityUtil;

import java.util.Optional;
import java.util.UUID;

@Mixin(AbstractVehicle.class)
public abstract class AbstractVehicleLinkedUavMixin implements AbstractVehicleLinkedUavExt {
    @Unique
    private static final String TAG_PARENT_UUID = "ywzj_rvp_linkedParentVehicleUuid";
    @Unique
    private static final String TAG_CHILD_UUID = "ywzj_rvp_linkedChildVehicleUuid";
    @Unique
    private static final String TAG_LAUNCHER_UUID = "ywzj_rvp_linkedLauncherVehicleUuid";
    @Unique
    private static final String TAG_DEPLOYABLE_INSTANCE = "ywzj_rvp_deployableUavInstance";
    @Unique
    private static final String TAG_ALLOW_SWITCH = "ywzj_rvp_deployableUavAllowControlSwitch";
    @Unique
    private static final String TAG_RETURN_SEAT = "ywzj_rvp_deployableUavReturnSeatIndex";
    @Unique
    private static final String TAG_UAV_ROLE = "ywzj_rvp_deployableUavRole";
    @Unique
    private static final String TAG_DATALINK_ROLE = "ywzj_rvp_datalinkRole";
    @Unique
    private static final String TAG_FAKE_POS_X = "ywzj_rvp_fakeOperatorPositionX";
    @Unique
    private static final String TAG_FAKE_POS_Y = "ywzj_rvp_fakeOperatorPositionY";
    @Unique
    private static final String TAG_FAKE_POS_Z = "ywzj_rvp_fakeOperatorPositionZ";
    @Unique
    private static final String TAG_PARENT_LAST_POS_X = "ywzj_rvp_linkedParentLastPositionX";
    @Unique
    private static final String TAG_PARENT_LAST_POS_Y = "ywzj_rvp_linkedParentLastPositionY";
    @Unique
    private static final String TAG_PARENT_LAST_POS_Z = "ywzj_rvp_linkedParentLastPositionZ";
    @Unique
    private static final String TAG_SEAT_LOCK_SEAT_INDEX = "ywzj_rvp_seatLockSeatIndex";
    @Unique
    private static final String TAG_SEAT_LOCK_OWNER_ID = "ywzj_rvp_seatLockOwnerPlayerId";

    /** 母车距无人机超过此区块数时不再强载母车区块（避免无限制远距离强载）。96 区块 = 1536 格。 */
    @Unique
    private static final int YWZJ_RVP$MAX_PARENT_CHUNK_DISTANCE = 96;

    @Shadow public boolean uav;
    @Shadow private Vec3 fakeOperatorPosition;
    @Shadow private FakePlayer fakeOperator;
    @Shadow private long destroyedTime;

    @Shadow public abstract LivingEntity getDriver();
    @Shadow public abstract void onLeaveVehicle(LivingEntity passenger);
    @Shadow public abstract boolean isDestroyed();

    @Unique
    private UUID ywzj_rvp$linkedParentVehicleUuid;
    @Unique
    private UUID ywzj_rvp$linkedChildVehicleUuid;
    @Unique
    private UUID ywzj_rvp$linkedLauncherVehicleUuid;
    @Unique
    private boolean ywzj_rvp$deployableUavInstance;
    @Unique
    private boolean ywzj_rvp$deployableUavAllowControlSwitch = true;
    @Unique
    private int ywzj_rvp$returnSeatIndex = -1;
    @Unique
    private String ywzj_rvp$deployableUavRole = "none";
    @Unique
    private String ywzj_rvp$datalinkRole = "none";
    /** 无人机最近同步到的母车（父车）世界位置；母车实体卸载后传送仍可据此回母车旁。 */
    @Unique
    private Vec3 ywzj_rvp$linkedParentLastPosition;
    /** 玩家驾驶无人机期间，母车被锁定的座位索引（-1 = 无锁）。 */
    @Unique
    private int ywzj_rvp$seatLockSeatIndex = -1;
    /** 座位锁的持有玩家实体 ID（仅该玩家可坐回被锁座位）。 */
    @Unique
    private int ywzj_rvp$seatLockOwnerPlayerId = -1;

    @Inject(method = "addAdditionalSaveData", at = @At("TAIL"))
    private void ywzj_rvp$saveLinkedUavState(CompoundTag compound, CallbackInfo ci) {
        if (ywzj_rvp$linkedParentVehicleUuid != null) {
            compound.putUUID(TAG_PARENT_UUID, ywzj_rvp$linkedParentVehicleUuid);
        }
        if (ywzj_rvp$linkedChildVehicleUuid != null) {
            compound.putUUID(TAG_CHILD_UUID, ywzj_rvp$linkedChildVehicleUuid);
        }
        if (ywzj_rvp$linkedLauncherVehicleUuid != null) {
            compound.putUUID(TAG_LAUNCHER_UUID, ywzj_rvp$linkedLauncherVehicleUuid);
        }
        compound.putBoolean(TAG_DEPLOYABLE_INSTANCE, ywzj_rvp$deployableUavInstance);
        compound.putBoolean(TAG_ALLOW_SWITCH, ywzj_rvp$deployableUavAllowControlSwitch);
        compound.putInt(TAG_RETURN_SEAT, ywzj_rvp$returnSeatIndex);
        if (!ywzj_rvp$deployableUavRole.isBlank()) {
            compound.putString(TAG_UAV_ROLE, ywzj_rvp$deployableUavRole);
        }
        if (!ywzj_rvp$datalinkRole.isBlank()) {
            compound.putString(TAG_DATALINK_ROLE, ywzj_rvp$datalinkRole);
        }
        if (ywzj_rvp$isInstanceUavOnly() && fakeOperatorPosition != null) {
            compound.putDouble(TAG_FAKE_POS_X, fakeOperatorPosition.x);
            compound.putDouble(TAG_FAKE_POS_Y, fakeOperatorPosition.y);
            compound.putDouble(TAG_FAKE_POS_Z, fakeOperatorPosition.z);
        }
        if (ywzj_rvp$linkedParentLastPosition != null) {
            compound.putDouble(TAG_PARENT_LAST_POS_X, ywzj_rvp$linkedParentLastPosition.x);
            compound.putDouble(TAG_PARENT_LAST_POS_Y, ywzj_rvp$linkedParentLastPosition.y);
            compound.putDouble(TAG_PARENT_LAST_POS_Z, ywzj_rvp$linkedParentLastPosition.z);
        }
        if (ywzj_rvp$seatLockSeatIndex >= 0) {
            compound.putInt(TAG_SEAT_LOCK_SEAT_INDEX, ywzj_rvp$seatLockSeatIndex);
            compound.putInt(TAG_SEAT_LOCK_OWNER_ID, ywzj_rvp$seatLockOwnerPlayerId);
        }
    }

    @Inject(method = "readAdditionalSaveData", at = @At("TAIL"))
    private void ywzj_rvp$readLinkedUavState(CompoundTag compound, CallbackInfo ci) {
        ywzj_rvp$linkedParentVehicleUuid = compound.hasUUID(TAG_PARENT_UUID) ? compound.getUUID(TAG_PARENT_UUID) : null;
        ywzj_rvp$linkedChildVehicleUuid = compound.hasUUID(TAG_CHILD_UUID) ? compound.getUUID(TAG_CHILD_UUID) : null;
        ywzj_rvp$linkedLauncherVehicleUuid = compound.hasUUID(TAG_LAUNCHER_UUID) ? compound.getUUID(TAG_LAUNCHER_UUID) : null;
        ywzj_rvp$deployableUavInstance = compound.getBoolean(TAG_DEPLOYABLE_INSTANCE);
        ywzj_rvp$deployableUavAllowControlSwitch = !compound.contains(TAG_ALLOW_SWITCH) || compound.getBoolean(TAG_ALLOW_SWITCH);
        ywzj_rvp$returnSeatIndex = compound.contains(TAG_RETURN_SEAT) ? compound.getInt(TAG_RETURN_SEAT) : -1;
        ywzj_rvp$deployableUavRole = compound.getString(TAG_UAV_ROLE);
        ywzj_rvp$datalinkRole = compound.getString(TAG_DATALINK_ROLE);
        if (ywzj_rvp$isInstanceUavOnly() && compound.contains(TAG_FAKE_POS_X)) {
            fakeOperatorPosition = new Vec3(
                    compound.getDouble(TAG_FAKE_POS_X),
                    compound.getDouble(TAG_FAKE_POS_Y),
                    compound.getDouble(TAG_FAKE_POS_Z)
            );
        }
        if (compound.contains(TAG_PARENT_LAST_POS_X)) {
            ywzj_rvp$linkedParentLastPosition = new Vec3(
                    compound.getDouble(TAG_PARENT_LAST_POS_X),
                    compound.getDouble(TAG_PARENT_LAST_POS_Y),
                    compound.getDouble(TAG_PARENT_LAST_POS_Z)
            );
        }
        ywzj_rvp$seatLockSeatIndex = compound.contains(TAG_SEAT_LOCK_SEAT_INDEX)
                ? compound.getInt(TAG_SEAT_LOCK_SEAT_INDEX) : -1;
        ywzj_rvp$seatLockOwnerPlayerId = compound.contains(TAG_SEAT_LOCK_OWNER_ID)
                ? compound.getInt(TAG_SEAT_LOCK_OWNER_ID) : -1;
    }

    /** 残骸遗留时间（毫秒）：本体硬编码 60 秒，RVP 缩短为 10 秒。 */
    @Unique
    private static final long WRECK_EXPIRE_MS = 10_000L;

    @Inject(method = "tick", at = @At("TAIL"))
    private void ywzj_rvp$tickDeployableUavChunkLoading(CallbackInfo ci) {
        AbstractVehicle self = (AbstractVehicle) (Object) this;
        if (self.level().isClientSide()) {
            return;
        }
        // 残骸遗留时间：本体 60 秒 → RVP 缩短为 10 秒
        ywzj_rvp$expireWreckEarly(self);
        if (!ywzj_rvp$deployableUavInstance) {
            return;
        }
        EntityUtil.keepChunkLoaded(self, self.position());
        EntityUtil.keepChunkLoaded(self, self.position().add(self.getLookAngle().normalize().scale(16)));
        if (fakeOperatorPosition != null) {
            EntityUtil.keepChunkLoaded(self, fakeOperatorPosition);
        }
        ywzj_rvp$refreshParentPosition(self);
        // 母车被击毁 → 无人机自动判定被击毁
        ywzj_rvp$checkParentDestroyed(self);
    }

    /** 残骸提前清除：被击毁超过 {@link #WRECK_EXPIRE_MS} 的载具直接移除。 */
    @Unique
    private void ywzj_rvp$expireWreckEarly(AbstractVehicle self) {
        if (isDestroyed() && System.currentTimeMillis() - destroyedTime > WRECK_EXPIRE_MS) {
            self.discard();
        }
    }

    /** 母车（父车）被击毁时，联动无人机也判定被击毁（自毁成残骸）。 */
    @Unique
    private void ywzj_rvp$checkParentDestroyed(AbstractVehicle self) {
        if (!ywzj_rvp$deployableUavInstance
                || isDestroyed()
                || ywzj_rvp$linkedParentVehicleUuid == null
                || !(self.level() instanceof ServerLevel serverLevel)) {
            return;
        }
        Entity parent = serverLevel.getEntity(ywzj_rvp$linkedParentVehicleUuid);
        if (parent instanceof AbstractVehicle parentVehicle && parentVehicle.isDestroyed()) {
            ywzj_rvp$destroyVehicle(self);
        }
    }

    /** 仿照本体 hurt() 的击毁逻辑：踢出乘客、置 DESTROYED、回满血、记录残骸计时。 */
    @Unique
    private void ywzj_rvp$destroyVehicle(AbstractVehicle self) {
        if (self.level().isClientSide()) {
            return;
        }
        self.getPassengers().forEach(Entity::stopRiding);
        self.getEntityData().set(AbstractVehicle.DESTROYED, true);
        self.setHealth(self.getMaxHealth());
        destroyedTime = System.currentTimeMillis();
    }

    /**
     * 每 tick 同步母车（父车）最新位置并（在距离上限内）强载母车区块。
     * 母车离开无人机视距后仍可能被服务端卸载，此时实时位置取不到；
     * 同步到 {@link #ywzj_rvp$linkedParentLastPosition} 的位置用于被击毁传送回母车旁的兜底。
     */
    @Unique
    private void ywzj_rvp$refreshParentPosition(AbstractVehicle self) {
        if (!(self.level() instanceof ServerLevel serverLevel) || ywzj_rvp$linkedParentVehicleUuid == null) {
            return;
        }
        Entity parent = serverLevel.getEntity(ywzj_rvp$linkedParentVehicleUuid);
        if (parent instanceof AbstractVehicle parentVehicle) {
            ywzj_rvp$linkedParentLastPosition = parentVehicle.position();
            if (ywzj_rvp$isWithinChunkDistance(self, parentVehicle)) {
                EntityUtil.keepChunkLoaded(self, parentVehicle.position());
            }
        }
    }

    @Unique
    private boolean ywzj_rvp$isWithinChunkDistance(AbstractVehicle a, AbstractVehicle b) {
        int dx = Math.abs(a.blockPosition().getX() - b.blockPosition().getX()) >> 4;
        int dz = Math.abs(a.blockPosition().getZ() - b.blockPosition().getZ()) >> 4;
        return dx <= YWZJ_RVP$MAX_PARENT_CHUNK_DISTANCE && dz <= YWZJ_RVP$MAX_PARENT_CHUNK_DISTANCE;
    }

    @Inject(method = "onRemovedFromWorld", at = @At("HEAD"), remap = false)
    private void ywzj_rvp$restoreOperatorWhenInstanceUavRemoved(CallbackInfo ci) {
        AbstractVehicle self = (AbstractVehicle) (Object) this;
        // 注意不能用 isInstanceUavOnly()（= deployableUavInstance && !uav）：
        // rvp 部署无人机（suav）模板自带 "uav": true，该条件恒为 false，
        // 直接移除（未走 stopRiding）时玩家会被留在无人机处。
        if (!ywzj_rvp$deployableUavInstance || self.level().isClientSide()) {
            return;
        }
        RVP_DeployableUavService.handleDeployableUavRemoved(self);
        if (getDriver() instanceof ServerPlayer serverPlayer) {
            onLeaveVehicle(serverPlayer);
            serverPlayer.unRide();
            Vec3 target = ywzj_rvp$resolveReturnPosition(self, serverPlayer);
            if (target != null) {
                serverPlayer.teleportTo(target.x, target.y, target.z);
            }
            fakeOperatorPosition = null;
        }
    }

    @Inject(method = "onEnterVehicle", at = @At("HEAD"), remap = false)
    private void ywzj_rvp$spawnFakeOperatorForInstanceUav(LivingEntity livingEntity, CallbackInfo ci) {
        AbstractVehicle self = (AbstractVehicle) (Object) this;
        if (!ywzj_rvp$isInstanceUavOnly() || self.level().isClientSide()) {
            return;
        }
        if (livingEntity instanceof ServerPlayer serverPlayer && self.tickCount != 0) {
            // 只保存玩家原位置，不生成假玩家实体（避免母车旁出现玩家模型）
            fakeOperatorPosition = livingEntity.position();
            livingEntity.teleportTo(self.position().x, self.position().y, self.position().z);
        }
    }

    @Inject(method = "interact", at = @At("HEAD"), cancellable = true)
    private void ywzj_rvp$blockDirectInteractWhenInstanceUav(Player player, InteractionHand hand, CallbackInfoReturnable<InteractionResult> cir) {
        if (ywzj_rvp$isInstanceUavOnly()) {
            cir.setReturnValue(InteractionResult.PASS);
        }
    }

    @Inject(method = "getDismountLocationForPassenger", at = @At("HEAD"), cancellable = true)
    private void ywzj_rvp$getDismountForInstanceUav(LivingEntity passenger, CallbackInfoReturnable<Vec3> cir) {
        if (!ywzj_rvp$isInstanceUavOnly()) {
            return;
        }
        if (fakeOperatorPosition != null) {
            cir.setReturnValue(fakeOperatorPosition);
        }
    }

    @Inject(method = "onLeaveVehicle", at = @At("HEAD"), remap = false)
    private void ywzj_rvp$teleportOperatorBackWhenUavLeave(LivingEntity passenger, CallbackInfo ci) {
        AbstractVehicle self = (AbstractVehicle) (Object) this;
        // 注意不能用 isInstanceUavOnly()（= deployableUavInstance && !uav）：
        // 实际部署无人机 rvp:suav 的模板自带 "uav": true，该条件恒为 false，
        // 导致被击毁踢下车时玩家被留在无人机残骸处。这里只按「是否 RVP 部署实例」判断。
        if (!ywzj_rvp$deployableUavInstance || self.level().isClientSide()) {
            return;
        }
        if (!(passenger instanceof ServerPlayer serverPlayer)) {
            return;
        }
        // 无人机被击毁时本体会在 hurt() 里强制 stopRiding 把玩家当场踢下车
        // （此时既不走 onRemovedFromWorld，也不走 getDismountLocationForPassenger），
        // 导致玩家原地留在无人机处。这里统一在离机时把操作员传送回母车旁边。
        Vec3 target = ywzj_rvp$resolveReturnPosition(self, serverPlayer);
        if (target != null) {
            serverPlayer.teleportTo(target.x, target.y, target.z);
        }
        // 传送回母车旁后自动坐上母车原座位（母车实体不可用时进入延迟重试队列）
        RVP_DeployableUavService.tryAutoRideParent(serverPlayer, self);
    }

    /**
     * 座位锁：玩家驾驶无人机期间，母车被锁座位（通常为驾驶位）只允许锁的持有者使用。
     * 本注入在座位分配之后执行：非持有者被分到了被锁座位 → 换到其他空座位，无空位则踢下车。
     */
    @Inject(method = "onEnterVehicle", at = @At("TAIL"), remap = false)
    private void ywzj_rvp$redirectLockedSeatPassenger(LivingEntity passenger, CallbackInfo ci) {
        AbstractVehicle self = (AbstractVehicle) (Object) this;
        if (self.level().isClientSide() || ywzj_rvp$seatLockSeatIndex < 0) {
            return;
        }
        if (!(passenger instanceof ServerPlayer serverPlayer)) {
            return;
        }
        if (serverPlayer.getId() == ywzj_rvp$seatLockOwnerPlayerId) {
            return; // 锁的持有者放行（自动上车/切回母车）
        }
        Optional<AbstractVehicle.Seat> lockedSeat = self.seats.stream()
                .filter(seat -> seat.seatIndex == ywzj_rvp$seatLockSeatIndex)
                .findFirst();
        if (lockedSeat.isEmpty() || lockedSeat.get().passengerId != passenger.getId()) {
            return; // 未被分到被锁座位，正常乘坐
        }
        Optional<AbstractVehicle.Seat> emptySeat = self.seats.stream()
                .filter(seat -> seat.passengerId == -1 && seat.seatIndex != ywzj_rvp$seatLockSeatIndex)
                .findFirst();
        if (emptySeat.isPresent()) {
            self.changeSeat(serverPlayer, emptySeat.get().seatIndex);
        } else {
            serverPlayer.stopRiding();
        }
    }

    /**
     * 座位锁：禁止非持有者换座到被锁座位（覆盖下车后再上车 / 手动换座路径）。
     */
    @Inject(method = "changeSeat", at = @At("HEAD"), cancellable = true, remap = false)
    private void ywzj_rvp$blockChangeSeatToLockedSeat(LivingEntity passenger, int toSeatIndex, CallbackInfoReturnable<Boolean> cir) {
        AbstractVehicle self = (AbstractVehicle) (Object) this;
        if (self.level().isClientSide() || ywzj_rvp$seatLockSeatIndex < 0 || toSeatIndex != ywzj_rvp$seatLockSeatIndex) {
            return;
        }
        if (passenger instanceof ServerPlayer serverPlayer && serverPlayer.getId() == ywzj_rvp$seatLockOwnerPlayerId) {
            return; // 持有者换回被锁座位放行
        }
        cir.setReturnValue(false);
    }

    @Unique
    private Vec3 ywzj_rvp$resolveReturnPosition(AbstractVehicle uav, ServerPlayer player) {
        // 优先传回母车（父车）当前的位置：linkedParentVehicleUuid 指向发射母车。
        // 不依赖 fakeOperatorPosition 这个快照——它在无人机出生同 tick 切入时为 null
        // （本体 onEnterVehicle 带 tickCount != 0 条件），且母车移动后会变成旧位置。
        if (ywzj_rvp$linkedParentVehicleUuid != null && uav.level() instanceof ServerLevel serverLevel) {
            Entity parent = serverLevel.getEntity(ywzj_rvp$linkedParentVehicleUuid);
            if (parent instanceof AbstractVehicle parentVehicle) {
                // 与本体 removePassenger 的下车点计算一致：母车右侧外沿，避免落在车体内
                return parentVehicle.relativeRotPos(
                        parentVehicle.position().add(parentVehicle.getMainCubeOBB().obb().extents().x + 1, 1, 0),
                        false);
            }
            // 母车实体已卸载（离开无人机视距，区块卸载）：用无人机最近同步到的母车位置兜底，
            // 该位置由 ywzj_rvp$refreshParentPosition 每 tick 更新，远优于上机时的旧快照。
            if (ywzj_rvp$linkedParentLastPosition != null) {
                return ywzj_rvp$linkedParentLastPosition.add(0, 1, 0);
            }
        }
        // 母车找不到时兜底用上车时记录的位置
        return fakeOperatorPosition;
    }

    @Unique
    private boolean ywzj_rvp$isInstanceUavOnly() {
        return ywzj_rvp$deployableUavInstance && !uav;
    }

    @Override
    public UUID ywzj_rvp$getLinkedParentVehicleUuid() {
        return ywzj_rvp$linkedParentVehicleUuid;
    }

    @Override
    public void ywzj_rvp$setLinkedParentVehicleUuid(UUID uuid) {
        ywzj_rvp$linkedParentVehicleUuid = uuid;
    }

    @Override
    public UUID ywzj_rvp$getLinkedChildVehicleUuid() {
        return ywzj_rvp$linkedChildVehicleUuid;
    }

    @Override
    public void ywzj_rvp$setLinkedChildVehicleUuid(UUID uuid) {
        ywzj_rvp$linkedChildVehicleUuid = uuid;
    }

    @Override
    public UUID ywzj_rvp$getLinkedLauncherVehicleUuid() {
        return ywzj_rvp$linkedLauncherVehicleUuid;
    }

    @Override
    public void ywzj_rvp$setLinkedLauncherVehicleUuid(UUID uuid) {
        ywzj_rvp$linkedLauncherVehicleUuid = uuid;
    }

    @Override
    public boolean ywzj_rvp$isDeployableUavInstance() {
        return ywzj_rvp$deployableUavInstance;
    }

    @Override
    public void ywzj_rvp$setDeployableUavInstance(boolean value) {
        ywzj_rvp$deployableUavInstance = value;
    }

    @Override
    public boolean ywzj_rvp$isDeployableUavControlSwitchAllowed() {
        return ywzj_rvp$deployableUavAllowControlSwitch;
    }

    @Override
    public void ywzj_rvp$setDeployableUavControlSwitchAllowed(boolean value) {
        ywzj_rvp$deployableUavAllowControlSwitch = value;
    }

    @Override
    public int ywzj_rvp$getReturnSeatIndex() {
        return ywzj_rvp$returnSeatIndex;
    }

    @Override
    public void ywzj_rvp$setReturnSeatIndex(int seatIndex) {
        ywzj_rvp$returnSeatIndex = seatIndex;
    }

    @Override
    public String ywzj_rvp$getDeployableUavRole() {
        return ywzj_rvp$deployableUavRole;
    }

    @Override
    public void ywzj_rvp$setDeployableUavRole(String role) {
        ywzj_rvp$deployableUavRole = role == null ? "none" : role;
    }

    @Override
    public String ywzj_rvp$getDatalinkRole() {
        return ywzj_rvp$datalinkRole;
    }

    @Override
    public void ywzj_rvp$setDatalinkRole(String role) {
        ywzj_rvp$datalinkRole = role == null ? "none" : role;
    }

    @Override
    public Vec3 ywzj_rvp$getLinkedParentLastPosition() {
        return ywzj_rvp$linkedParentLastPosition;
    }

    @Override
    public void ywzj_rvp$setLinkedParentLastPosition(@Nullable Vec3 position) {
        ywzj_rvp$linkedParentLastPosition = position;
    }
}
