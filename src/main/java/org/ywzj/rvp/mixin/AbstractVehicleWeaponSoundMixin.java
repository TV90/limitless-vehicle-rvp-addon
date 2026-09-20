package org.ywzj.rvp.mixin;

import net.minecraft.client.Minecraft;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.ywzj.vehicle.vehicle.weapon.AbstractVehicleWeapon;

/**
 * 为武器射击声音添加距离衰减。
 * <p>
 * 所有载具实体的 {@code clientTrackingRange = 32 chunks}（约 512 格），
 * 导致 {@code ServerVehicleFire} 包被分发给 512 格内的所有玩家。
 * 但原版 {@code level.playSound(..., 4f, 1f)} 使用 OpenAL 线性衰减时，
 * 最大可听距离只有 {@code 16 × volume = 64} 格。
 * <p>
 * 本 Mixin 在 0-64 格保持原样，64-500 格逐步降低音量，500+ 格不播放。
 */
@Mixin(value = AbstractVehicleWeapon.class, remap = false)
public abstract class AbstractVehicleWeaponSoundMixin {

    private static final double MAX_DIST = 500.0;

    @Redirect(
            method = "onClientFire",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/Level;playSound(Lnet/minecraft/world/entity/player/Player;DDDLnet/minecraft/sounds/SoundEvent;Lnet/minecraft/sounds/SoundSource;FF)V",
                    // remap 显式 true：原版 playSound 在生产 SRG 环境需 refmap 翻译，
                    // 否则 require=0 会静默失效（2026-09-20 排查：07-27 起生产上此衰减一直未生效）
                    remap = true),
            require = 0
    )
    private void ywzj_rvp$redirectPlaySound(Level level, @Nullable Player except,
                                             double x, double y, double z,
                                             SoundEvent event, SoundSource source,
                                             float volume, float pitch) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) {
            level.playSound(except, x, y, z, event, source, volume, pitch);
            return;
        }

        double distSq = mc.player.distanceToSqr(x, y, z);
        if (distSq > MAX_DIST * MAX_DIST) {
            return;
        }

        // 64-500 格：二次方衰减音量 (4->0.05)
        if (distSq > 64.0 * 64.0) {
            double dist = Math.sqrt(distSq);
            float t = (float) ((dist - 64.0) / (MAX_DIST - 64.0));
            volume = volume * (1.0f - t) * (1.0f - t);
            if (volume < 0.01f) {
                return;
            }
        }

        level.playSound(except, x, y, z, event, source, volume, pitch);
    }
}
