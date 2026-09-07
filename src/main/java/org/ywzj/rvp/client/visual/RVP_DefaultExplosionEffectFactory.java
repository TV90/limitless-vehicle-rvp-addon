package org.ywzj.rvp.client.visual;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.ParticleEngine;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.ywzj.rvp.client.particle.RVP_MchrFlareParticle;
import org.ywzj.rvp.client.particle.RVP_MchrSmokeParticle;
import org.ywzj.rvp.weapon.data.RVP_EnumWeaponKind;
import org.ywzj.rvp.weapon.visual.RVP_DefaultExplosionEventData;
import org.ywzj.rvp.weapon.visual.api.RVP_VisualEffectEvent;

import java.util.Optional;

/**
 * RVP 内置默认爆炸视觉工厂——按 {@code MCH_Explosion.effectExplosion:73-273} 逐条移植。
 *
 * <p>MCHR 的循环遍历"受影响方块列表"；此处以爆心立方体随机采样近似（非空气方块即有效样本），
 * 其余公式（速度缩放、方向偏置、尺寸/寿命/配色）逐条照抄：</p>
 * <ol>
 *   <li>闪光：vanilla {@code hugeexplosion/largeexplode} 的现代形态
 *       {@code EXPLOSION_EMITTER / EXPLOSION}；</li>
 *   <li>方块碎屑：每 4 个有效样本 1 个（vanilla BLOCK 粒子，速度公式照抄）；</li>
 *   <li>翻滚灰黄大烟：每 {@code max(radius,4)} 个有效样本 1 簇，方向随机纵向（dirY×4、水平×0.1）
 *       或横向（dirY×0.2、水平×2）偏置，尺寸/寿命/配色照抄，生成点 = 采样位与爆心中点；</li>
 * </ol>
 * 使用 {@code event.seed()} 派生确定性随机；直接构造粒子实例经 {@link ParticleEngine#add} 加入
 * 原版粒子系统；另返回轻量生命周期实例，按声速延迟推进客户端近远爆炸音。
 */
@OnlyIn(Dist.CLIENT)
public final class RVP_DefaultExplosionEffectFactory implements RVP_ClientVisualEffectFactory {

    /** 工厂注册类型（与服务端 {@code RVP_DefaultExplosionVisualService.EFFECT_TYPE} 一致）。 */
    public static final ResourceLocation EFFECT_TYPE =
            ResourceLocation.fromNamespaceAndPath("rvp", "mchr_explosion");

    /** 采样上限（近似 MCHR 受影响方块列表的遍历规模；radius=8 约 400 样本）。 */
    private static final int MAX_SAMPLES = 600;

    @Override
    public Optional<RVP_ClientVisualEffect> create(ClientLevel level, RVP_VisualEffectEvent event) {
        float radius = Math.max(0.5f, event.baseExplosionRadius());
        float density = Math.max(0.25f, event.density());
        RandomSource random = RandomSource.create(event.seed());
        ParticleEngine engine = Minecraft.getInstance().particleEngine;
        Vec3 center = event.position();

        // ── 1) 起爆闪光：flare.png 大光斑快速放大淡出（替代 vanilla EXPLOSION_EMITTER）──
        if (event.flash()) {
            RVP_MchrFlareParticle.spawnFlash(engine, level,
                    center.x, center.y, center.z, radius * 2.0f * Math.min(event.scale(), 2f));
        }

        // ── 2) 翻滚灰黄大烟：数量直接随半径平方增长（大爆炸不再被采样上限稀释），烟团随半径放大 ──
        // 烟雾视觉规模独立缩放到 0.7 倍：规模 10 的爆炸按 7 计算（数量/大小/散布/速度/寿命），整体收敛一档
        float smokeRadius = radius * 0.7f;
        int smokeCount = (int) Math.min(smokeRadius * smokeRadius * 6.0f * density, 500);
        float smokeSize = smokeRadius < 8.0f
                ? (smokeRadius < 2.0f ? 1.5f : smokeRadius * 1.5f)
                : Math.min(12.0f + (smokeRadius - 8.0f) * 0.5f, 20.0f);
        for (int i = 0; i < smokeCount; i++) {
            // 爆心 ± smokeRadius 球域内随机（含空气——空爆时烟铺满球面）
            double randX = center.x + (random.nextDouble() - 0.5) * 2.0 * smokeRadius;
            double randY = center.y + (random.nextDouble() - 0.5) * 2.0 * smokeRadius;
            double randZ = center.z + (random.nextDouble() - 0.5) * 2.0 * smokeRadius;
            double distance = Math.sqrt(
                    (randX - center.x) * (randX - center.x)
                            + (randY - center.y) * (randY - center.y)
                            + (randZ - center.z) * (randZ - center.z));
            if (distance < 1.0E-4) {
                continue;
            }
            double dirX = (randX - center.x) / distance;
            double dirY = (randY - center.y) / distance;
            double dirZ = (randZ - center.z) / distance;
            // MCHR :160-175：速度缩放因子
            double velocityScale = 0.5 / (distance / smokeRadius + 0.1)
                    * (random.nextDouble() * random.nextDouble() + 0.3);
            double smokeX = dirX * velocityScale * 0.5;
            double smokeY = dirY * velocityScale * 0.5;
            double smokeZ = dirZ * velocityScale * 0.5;
            // 方向偏置二选一：纵向烟柱（dirY×4、水平×0.1）或横向翻滚（dirY×0.2、水平×2）
            if (random.nextBoolean()) {
                smokeY *= 4.0;
                smokeX *= 0.1;
                smokeZ *= 0.1;
            } else {
                smokeY *= 0.2;
                smokeX *= 2.0;
                smokeZ *= 2.0;
            }
            // 发射源 = 采样位与爆心的中点
            int lifetime = (int) ((5 + random.nextInt(10)) * Math.max(smokeRadius / 2.0f, 6.0f));
            engine.add(RVP_MchrSmokeParticle.of(level,
                    (randX + center.x) / 2.0, (randY + center.y) / 2.0, (randZ + center.z) / 2.0,
                    smokeX, smokeY, smokeZ, smokeSize, lifetime));
        }

        // ── 3) 受影响方块采样环（碎屑 + 曳光火星）──
        int sampleCount = (int) Math.min(radius * radius * 6.0f * density, 300);
        // 火星数量分段：8 以下大幅缩减（此前散不开显得少，散开后无需那么多），
        // 8 以上中等削减；仍随半径与 density 增长
        int flaresRemaining;
        if (radius >= 8.0f) {
            flaresRemaining = (int) Math.min(radius * 1.0f * density, 60);
        } else if (radius >= 5.0f) {
            flaresRemaining = Math.max(2, (int) (radius * 0.5f * density));
        } else {
            flaresRemaining = 0;
        }
        int affectedCount = 0;
        for (int spawnCount = 0; spawnCount < sampleCount; spawnCount++) {
            double randX = center.x + (random.nextDouble() - 0.5) * 2.0 * radius;
            double randY = center.y + (random.nextDouble() - 0.5) * 2.0 * radius;
            double randZ = center.z + (random.nextDouble() - 0.5) * 2.0 * radius;
            BlockPos blockPos = BlockPos.containing(randX, randY, randZ);
            BlockState state = level.getBlockState(blockPos);
            if (state.isAir()) {
                continue; // 碎屑贴图取自方块本身，空气位无碎屑
            }
            affectedCount++;
            double distance = Math.sqrt(
                    (randX - center.x) * (randX - center.x)
                            + (randY - center.y) * (randY - center.y)
                            + (randZ - center.z) * (randZ - center.z));
            if (distance < 1.0E-4) {
                continue;
            }
            double dirY = (randY - center.y) / distance;
            double angle = Math.PI * random.nextInt(360) / 180.0;

            // 曳光火星：radius ≥ 5，数量随半径增长（≤90）；从爆心上部向外抛射，
            // 速度随半径增强；下坠缓慢（长滞空）+ 拖烟
            if (radius >= 5.0f && flaresRemaining > 0) {
                double flareSpeed = Math.min(radius / 8.0f, 1.4) * (0.5 + random.nextDouble() * 0.5);
                RVP_MchrFlareParticle.spawnEmber(engine, level,
                        center.x, center.y + radius * 0.3 + random.nextDouble() * radius * 0.3, center.z,
                        Math.sin(angle) * flareSpeed,
                        0.15 + random.nextDouble() * 0.45,
                        Math.cos(angle) * flareSpeed);
                flaresRemaining--;
            }

            // 方块碎屑（MCHR :204-223）：每 4 个受影响方块位 1 个，速度公式照抄
            if (affectedCount % 4 == 0) {
                float dustVelocity = Math.min(radius / 3.0f, 2.0f) * (0.5f + random.nextFloat() * 0.5f);
                level.addParticle(new BlockParticleOption(ParticleTypes.BLOCK, state),
                        blockPos.getX() + 0.5, blockPos.getY() - 0.5, blockPos.getZ() + 0.5,
                        Math.sin(angle) * dustVelocity,
                        0.5 + dirY / 5.0 * dustVelocity,
                        Math.cos(angle) * dustVelocity);
            }
        }

        // ── 4) 飞溅火星：flare.png 小光斑向外快速抛洒，重力下坠、收缩淡出 ──
        if (radius >= 2.0f) {
            int sparkCount = (int) Math.min(6 + radius * 2 * density, 28);
            for (int i = 0; i < sparkCount; i++) {
                // 仰角对称且以水平为主（±0.55 rad），抛射初速水平占优 → 弧线下坠可见
                double angle = Math.PI * 2.0 * random.nextDouble();
                double elevation = (random.nextDouble() - 0.5) * Math.PI * 0.35;
                double speed = (0.5 + random.nextDouble() * 0.5) * Math.min(radius, 8.0f) * 0.3;
                RVP_MchrFlareParticle.spawnSpark(engine, level,
                        center.x, center.y + radius * 0.2, center.z,
                        Math.sin(angle) * Math.cos(elevation) * speed,
                        Math.sin(elevation) * speed * 0.5 + 0.05,
                        Math.cos(angle) * Math.cos(elevation) * speed,
                        0.35f + random.nextFloat() * 0.3f,
                        20 + random.nextInt(16));
            }
        }

        // 调用 RVP 默认爆炸事件解码器，从公共事件载荷恢复服务端确定的类型化武器分类。
        RVP_EnumWeaponKind weaponKind = RVP_DefaultExplosionEventData.decodeWeaponKind(
                event.canonicalPresetDataJson());
        long elapsed = Math.max(0L, level.getGameTime() - event.startGameTime());
        int initialAge = elapsed >= Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) elapsed;
        return Optional.of(new RVP_DefaultExplosionEffectInstance(level, event, weaponKind, initialAge));
    }
}
