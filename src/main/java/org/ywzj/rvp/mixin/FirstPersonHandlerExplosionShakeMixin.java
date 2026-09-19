package org.ywzj.rvp.mixin;

import net.minecraft.util.Mth;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.ywzj.rvp.config.RVP_ClientConfig;
import org.ywzj.vehicle.client.handler.FirstPersonHandler;

/**
 * 本体爆炸视角晃动强度钳制（2026-09-20 用户需求）：本体
 * {@code FirstPersonHandler.addExplosionShake} 是爆炸冲击晃动的唯一写入入口
 * （本体/ RVP 弹体爆炸的客户端 effect() 与大口径导弹飞行每 5t 的 tickShake 都走这里，
 * 强度钳上限 27°、时长 3.8s，大半径核档爆炸观感"视角剧烈晃动"）。
 *
 * <p>本 Mixin 把强度 clamp 的输入值乘以 {@code RVP_ClientConfig.explosionShakeIntensity}
 * （0..1，0 = 完全关闭本体爆炸晃动）；乘后仍过本体原 clamp（2.4~27 上限），语义安全。
 * RVP 声波抵达屏震有独立配置项不受本项影响；本体全局 {@code cameraShakeMultiplier}
 * 在应用末端另行相乘，两者叠加互不冲突。</p>
 *
 * <p>注册于 client 数组：目标类 {@code FirstPersonHandler} 为本体纯客户端类。
 * {@code Mth.clamp(DDD)D} ordinal 0 = 方法体内第一处 double clamp（强度），
 * ordinal 1 是时长——只改强度不缩时长，保持晃动节奏。</p>
 */
@Mixin(value = FirstPersonHandler.class, remap = false)
public abstract class FirstPersonHandlerExplosionShakeMixin {

    @ModifyArg(
            method = "addExplosionShake",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/util/Mth;clamp(DDD)D",
                    ordinal = 0
            ),
            index = 1
    )
    private static double rvp$scaleExplosionShakeStrength(double strength) {
        return strength * RVP_ClientConfig.getExplosionShakeIntensity();
    }
}
