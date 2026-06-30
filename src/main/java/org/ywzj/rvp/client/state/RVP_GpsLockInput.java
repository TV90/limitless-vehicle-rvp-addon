package org.ywzj.rvp.client.state;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.Vec3;
import org.ywzj.vehicle.vehicle.part.WeaponUnit;

/**
 * 锁定键（R）：当前 RVP 武器为 GPS 制导时，替代默认火控锁定并设置 GPS 目标点。
 */
public final class RVP_GpsLockInput {

    private RVP_GpsLockInput() {}

    /**
     * @return true 表示已处理 GPS 目标设置，调用方应跳过 {@link WeaponUnit#fireControlLock()}。
     */
    public static boolean trySetOnLockKey(WeaponUnit weaponUnit) {
        if (!RVP_ClientGPSUtil.isGPSBombSelected()) {
            return false;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) {
            return false;
        }
        Vec3 target = RVP_ClientGPSUtil.raycastGPSTarget(mc);
        if (target == null) {
            mc.player.displayClientMessage(Component.translatable("message.ywzj_rvp.gps.no_block"), true);
            return true;
        }
        RVP_ClientGPSUtil.setGpsTarget(mc.player, mc.player.level().dimension().location(), target);
        return true;
    }
}
