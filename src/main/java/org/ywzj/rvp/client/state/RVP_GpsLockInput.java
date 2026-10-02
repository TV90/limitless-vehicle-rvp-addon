package org.ywzj.rvp.client.state;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.Vec3;
import org.ywzj.vehicle.vehicle.part.WeaponUnit;

/**
 * 锁定键（R）：当前 RVP 武器为 GPS 制导时，替代默认火控锁定并设置 GPS 目标点。
 * 2026-10-02 模式参数化后按模式分流：
 * <ul>
 *   <li>FAST——吞键不标点（开火键才是 FAST 的打击入口，防止绕过节奏），动作栏提示；</li>
 *   <li>RADAR——放行本体 {@code fireControlLock()}（雷达锁定），发射时由服务端
 *       {@code RVP_ProjectileSpawner} 注入锁目标位置并按周期在途改靶；</li>
 *   <li>SINGLE/MULTI——原行为：观瞄射线取点写入 GPS 目标。</li>
 * </ul>
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
        RVP_ClientGPSState.Mode mode = RVP_ClientGPSState.getMode();
        if (mode == RVP_ClientGPSState.Mode.FAST) {
            // FAST 模式 R 键不标点：吞键防止绕过"点击即打"节奏
            mc.player.displayClientMessage(Component.translatable("message.ywzj_rvp.gps.fast_r_disabled"), true);
            return true;
        }
        if (mode == RVP_ClientGPSState.Mode.RADAR) {
            // RADAR 模式：不拦截，落到本体 fireControlLock() 走雷达锁定
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
