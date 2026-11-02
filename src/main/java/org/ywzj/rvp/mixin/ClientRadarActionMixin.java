package org.ywzj.rvp.mixin;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraftforge.network.NetworkEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.ywzj.rvp.countermeasure.RVP_ChaffJamState;
import org.ywzj.rvp.radar.RVP_RadarRoleHelper;
import org.ywzj.rvp.radar.RVP_RadarSelfFilter;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;
import org.ywzj.vehicle.network.message.ClientRadarAction;
import org.ywzj.vehicle.vehicle.part.PartUnit;
import org.ywzj.vehicle.vehicle.part.RadarUnit;
import org.ywzj.vehicle.vehicle.part.WeaponUnit;

import java.util.function.Supplier;

@Mixin(value = ClientRadarAction.class, remap = false)
public class ClientRadarActionMixin {
    @Inject(method = "onClientMessageReceived", at = @At("HEAD"), cancellable = true, remap = false)
    private static void ywzj_rvp$routeLockToPreferredRadar(ClientRadarAction message, Supplier<NetworkEvent.Context> ctxSupplier, CallbackInfo ci) {
        if (message.action != ClientRadarAction.Action.LOCK
                && message.action != ClientRadarAction.Action.DETECT) {
            return;
        }
        NetworkEvent.Context context = ctxSupplier.get();
        context.setPacketHandled(true);
        context.enqueueWork(() -> {
            ServerPlayer player = context.getSender();
            if (player == null || !(player.getVehicle() instanceof AbstractVehicle vehicle)) {
                return;
            }
            PartUnit<?> partUnit = vehicle.getOwnOperatorUnit(player);
            if (!(partUnit instanceof WeaponUnit weaponUnit)) {
                return;
            }
            Level level = player.level();
            Entity target = message.toEntityId < 0 ? null : level.getEntity(message.toEntityId);
            if (message.action == ClientRadarAction.Action.DETECT) {
                // 调用本体兼容的探测写入，仅额外拒绝自身，防旧客户端重新污染服务端表。
                RVP_RadarSelfFilter.detectNonSelf(weaponUnit, target);
                return;
            }
            RadarUnit lockRadar = RVP_RadarRoleHelper.getPreferredLockRadar(weaponUnit);
            if (lockRadar == null) {
                return;
            }
            // 自身锁定按无效目标处理，沿用既有解锁路径并清除待定锁。
            if (target == null || RVP_RadarSelfFilter.isSelf(vehicle, target)) {
                RVP_RadarRoleHelper.clearPendingRadarLock(weaponUnit);
                RVP_RadarRoleHelper.clearAllRadarLocks(weaponUnit);
                weaponUnit.setLockedEntity(null);
                return;
            }
            // 箔条禁锁期：目标被箔条干扰脱锁后短时间内不可被选中/锁定（仍可被扫描），
            // 拒绝客户端在此期间的重新锁定，否则客户端每 tick 重发 LOCK 会令脱锁瞬间被还原
            if (RVP_ChaffJamState.isInCooldown(target.getUUID(), level.getGameTime())) {
                RVP_RadarRoleHelper.clearAllRadarLocks(weaponUnit);
                weaponUnit.setLockedEntity(null);
                return;
            }
            RVP_RadarRoleHelper.clearPendingRadarLock(weaponUnit);
            weaponUnit.setLockedEntity(target);
            if (!RVP_RadarRoleHelper.entityMatches(lockRadar.getLockedEntity(), target.getId())) {
                RVP_RadarRoleHelper.clearAllRadarLocks(weaponUnit);
                lockRadar.setLockedEntity(target);
            }
        });
        ci.cancel();
    }
}
