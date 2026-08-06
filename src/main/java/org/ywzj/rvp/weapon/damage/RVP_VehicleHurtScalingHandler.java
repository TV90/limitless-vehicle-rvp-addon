package org.ywzj.rvp.weapon.damage;

import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.ywzj.rvp.RVP_MOD;
import org.ywzj.rvp.physics.RVP_PhysicsOnlyCollisionHelper;
import org.ywzj.vehicle.api.event.VehicleAttackEvent;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;
import org.ywzj.vehicle.util.VectorUtil;
import org.ywzj.vehicle.vehicle.structure.OBB;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 命中箱伤害系数的非 mixin 恢复（替代被删 {@code AbstractVehicleHitboxDamageFactorMixin}）。
 *
 * <p>原 mixin 通过注入 {@code AbstractVehicle.hurt} 实现：HEAD 捕获伤害来源/命中线段 →
 * 物理碰撞过滤 → 距离衰减调制 + 命中箱系数 → INVOKE AFTER 改写最终血量。
 * 本类改用本体公开事件 {@link VehicleAttackEvent}（在 {@code DamageSystem.hurt} 之前触发、
 * 可取消），在事件中：
 * <ol>
 *   <li>物理-only 碰撞过滤（取消事件）；</li>
 *   <li>解析命中箱系数与核心距离衰减倍率；</li>
 *   <li>反推一个"输入伤害量"，使本体 {@code DamageSystem.hurt} 自身缩放后恰好得到期望伤害；</li>
 *   <li>取消原事件并重新调用 {@code vehicle.hurt()}（重入守卫防环），让本体的
 *       音效 / HitVehicleEvent / 死亡逻辑 / markHurt 全部以正确伤害量执行一次。</li>
 * </ol>
 *
 * <p>RVP 弹体/激光在命中时已自行结算命中箱系数，通过 {@link #pushSkip}/{@link #popSkip}
 * 深度计数跳过本类的全局缩放，避免重复应用。
 */
@Mod.EventBusSubscriber(modid = RVP_MOD.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class RVP_VehicleHurtScalingHandler {

    /** 服务端全局命中箱缩放跳过深度（替代原 mixin 的 per-vehicle 字段）。 */
    private static final Map<UUID, Integer> SKIP_DEPTH = new HashMap<>();
    /** 重入守卫：取消事件后再次调用 vehicle.hurt() 时防止本 handler 重复处理。 */
    private static final Set<Integer> REAPPLY_GUARD = new HashSet<>();

    private RVP_VehicleHurtScalingHandler() {}

    // ─────────────────────────────────────────────────────────────
    // 跳过表（供 RVP 弹体/激光在已自行结算命中箱系数时使用）
    // ─────────────────────────────────────────────────────────────

    public static void pushSkip(AbstractVehicle vehicle) {
        if (vehicle == null) {
            return;
        }
        SKIP_DEPTH.merge(vehicle.getUUID(), 1, Integer::sum);
    }

    public static void popSkip(AbstractVehicle vehicle) {
        if (vehicle == null) {
            return;
        }
        UUID id = vehicle.getUUID();
        Integer depth = SKIP_DEPTH.get(id);
        if (depth == null) {
            return;
        }
        if (depth <= 1) {
            SKIP_DEPTH.remove(id);
        } else {
            SKIP_DEPTH.put(id, depth - 1);
        }
    }

    public static boolean shouldSkip(AbstractVehicle vehicle) {
        return vehicle != null && SKIP_DEPTH.containsKey(vehicle.getUUID());
    }

    // ─────────────────────────────────────────────────────────────
    // 事件处理（服务端）
    // ─────────────────────────────────────────────────────────────

    @SubscribeEvent
    public static void onVehicleAttack(VehicleAttackEvent event) {
        if (event.isCanceled()) {
            return;
        }
        AbstractVehicle self = event.getVehicle();
        if (self == null || self.level().isClientSide()) {
            return;
        }
        if (REAPPLY_GUARD.contains(self.getId())) {
            return;
        }
        if (shouldSkip(self)) {
            return; // RVP 弹体/激光已自行结算命中箱系数
        }

        DamageSource source = event.getSource();
        float amount = event.getAmount();
        if (amount <= 0f) {
            return;
        }

        Entity direct = source.getDirectEntity();
        Entity attacker = source.getEntity();
        if (direct == null && attacker == null) {
            return;
        }

        Vec3[] segment = resolveSegment(self, direct, attacker);
        if (segment[0] == null) {
            return;
        }

        boolean explosion = "ywzj_vehicle.explosion".equals(source.getMsgId());

        // ── 物理-only 碰撞过滤（只拦非爆炸投射物，与原 mixin 一致）──
        if (!explosion && direct instanceof Projectile
                && !RVP_PhysicsOnlyCollisionHelper.getPhysicsOnlyCubes(self).isEmpty()
                && RVP_PhysicsOnlyCollisionHelper.closestNonPhysicsOnlyHitPosition(self, segment[0], segment[1]) == null
                && RVP_PhysicsOnlyCollisionHelper.closestPhysicsOnlyHitPosition(self, segment[0], segment[1]) != null) {
            event.setCanceled(true);
            return;
        }

        float coreMult = RVP_VehicleHitboxFactorManager.INSTANCE.resolveCoreDistanceScaleMultiplier(self);
        RVP_VehicleHitboxFactorManager.HitboxDamageResult res = null;
        if (!explosion) {
            res = RVP_VehicleHitboxFactorManager.INSTANCE.resolveHitboxDamage(self, segment[0], segment[1]);
        }
        boolean hitboxEnabled = res != null && res.enabled();
        // 爆炸不参与命中箱缩放；无任何覆盖时完全放行（与原 mixin 净效果一致）
        if (!hitboxEnabled && (coreMult == 1f || explosion)) {
            return;
        }

        float predicted = predictBaseDamage(self, source, amount);
        float coreFalloff = resolveCoreFalloffScale(self, source, amount);
        float hitboxMult = hitboxEnabled ? res.factor() : 1f;
        if (!Float.isFinite(hitboxMult)) {
            return;
        }

        // ── 期望最终伤害（复刻原 mixin INVOKE AFTER 的数学）──
        float deltaAfterCore = predicted;
        if (coreMult != 1f && Float.isFinite(coreFalloff) && Math.abs(coreFalloff) > 1.0E-6f) {
            float deltaNoFalloff = predicted / coreFalloff;
            float effectiveScale = 1f + (coreFalloff - 1f) * coreMult;
            if (Float.isFinite(deltaNoFalloff) && Float.isFinite(effectiveScale)) {
                deltaAfterCore = deltaNoFalloff * effectiveScale;
            }
        }
        float desiredFinal = deltaAfterCore * hitboxMult;
        if (!(desiredFinal > 0f) || !Float.isFinite(desiredFinal)) {
            return;
        }

        // ── 反推输入量：本体 DamageSystem 内部会再乘一次自身缩放（predicted/amount）──
        float damageSystemScale = predicted / amount;
        if (!(damageSystemScale > 0f) || !Float.isFinite(damageSystemScale)) {
            return;
        }
        float adjustedAmount = desiredFinal / damageSystemScale;

        // ── 取消原事件并重放，使本体后处理（音效/HitVehicleEvent/死亡/markHurt）以正确伤害执行 ──
        event.setCanceled(true);
        REAPPLY_GUARD.add(self.getId());
        try {
            self.hurt(source, adjustedAmount);
        } finally {
            REAPPLY_GUARD.remove(self.getId());
        }

        // ERA 触发 + 调试消息（与原 mixin 一致）
        if (res != null) {
            RVP_VehicleHitboxFactorManager.INSTANCE.tryTriggerEra(self, res, predicted);
        }
        Player debugPlayer = resolveDebugPlayer(attacker);
        if (debugPlayer != null) {
            RVP_VehicleHitboxFactorManager.HitboxDamageResult dbgRes = hitboxEnabled
                    ? res
                    : RVP_VehicleHitboxFactorManager.HitboxDamageResult.defaulted(1f, null, 0, Double.NaN);
            RVP_VehicleHitboxFactorManager.INSTANCE.maybeSendHitboxDebug(
                    debugPlayer, self, predicted, desiredFinal, dbgRes, coreFalloff, coreMult);
        }
    }

    // ─────────────────────────────────────────────────────────────
    // 复刻原 mixin 的辅助逻辑
    // ─────────────────────────────────────────────────────────────

    /** 解析命中线段 [start, end]，与原 mixin HEAD 逻辑一致。 */
    private static Vec3[] resolveSegment(AbstractVehicle self, Entity direct, Entity attacker) {
        Vec3 segmentStart = null;
        Vec3 segmentEnd = null;
        Vec3 vehicleCenter = self.getBoundingBox().getCenter();

        if (direct instanceof Projectile projectile) {
            segmentStart = projectile.position();
            Vec3 delta = projectile.getDeltaMovement();
            if (delta.lengthSqr() > 1.0E-8) {
                segmentEnd = segmentStart.add(delta);
            } else {
                Vec3 old = new Vec3(projectile.xOld, projectile.yOld, projectile.zOld);
                if (old.distanceToSqr(segmentStart) > 1.0E-6) {
                    segmentStart = old;
                    segmentEnd = projectile.position();
                }
            }
        }
        if (segmentStart == null && attacker instanceof LivingEntity living) {
            segmentStart = living.getEyePosition();
        }
        if (segmentStart == null && direct instanceof AbstractVehicle ramming) {
            LivingEntity driver = ramming.getDriver();
            if (driver != null) {
                segmentStart = driver.getEyePosition();
            }
        }
        if (segmentStart == null && attacker != null) {
            segmentStart = attacker.position().add(0, attacker.getBbHeight() * 0.5, 0);
        }
        if (segmentStart == null && direct != null) {
            Vec3 end = direct.position();
            Vec3 delta = direct.getDeltaMovement();
            segmentStart = delta.lengthSqr() > 1.0E-8 ? end.subtract(delta) : end;
        }
        if (segmentEnd == null) {
            segmentEnd = vehicleCenter;
        }
        return new Vec3[] {segmentStart, segmentEnd};
    }

    /** 核心距离衰减比例（与原 mixin HEAD 一致；爆炸/非投射物/低于阈值返回 NaN）。 */
    private static float resolveCoreFalloffScale(AbstractVehicle self, DamageSource source, float amount) {
        boolean explosion = "ywzj_vehicle.explosion".equals(source.getMsgId());
        if (explosion) {
            return Float.NaN;
        }
        Entity direct = source.getDirectEntity();
        if (!(direct instanceof Projectile) || amount < self.defenseStats.damageThreshold) {
            return Float.NaN;
        }
        Vec3 hitPos = direct.position();
        Vec3 hitVec = direct.getDeltaMovement();
        if (hitVec.lengthSqr() <= 1.0E-8) {
            return Float.NaN;
        }
        OBB obb = self.getMainCubeOBB().obb();
        Vec3 corePos = self.relativeRotPos(new Vec3(obb.center()), false);
        Vec3 diff = corePos.subtract(hitPos);
        Vec3 cross = diff.cross(hitVec);
        double distanceToCore = cross.length() / hitVec.length();
        double distanceMax = obb.extents().get(obb.extents().maxComponent()) * 2;
        if (!Double.isFinite(distanceMax) || distanceMax == 0d) {
            return Float.NaN;
        }
        double s = (distanceMax - distanceToCore) / distanceMax;
        return Double.isFinite(s) ? (float) s : Float.NaN;
    }

    /** 预测本体 DamageSystem 对原始伤害量的结算结果（复刻原 mixin rvp$predictBaseDamage）。 */
    private static float predictBaseDamage(AbstractVehicle self, DamageSource source, float amount) {
        float effectiveAmount = amount;
        double scale = 1d;
        boolean explosion = "ywzj_vehicle.explosion".equals(source.getMsgId());
        Entity direct = source.getDirectEntity();
        Vec3 hitPos = null;
        if (direct instanceof Projectile projectile) {
            hitPos = VectorUtil.closestHitObbPosition(
                    self,
                    projectile.position(),
                    projectile.position().add(projectile.getDeltaMovement())
            );
        }
        if (explosion && direct != null) {
            hitPos = direct.position();
        }
        if (effectiveAmount < 0.1f) {
            effectiveAmount = 0f;
        } else if (effectiveAmount < self.defenseStats.damageThreshold) {
            effectiveAmount = 0.1f;
        } else if (hitPos == null) {
            scale = 0.2d;
        } else if (!explosion && direct != null) {
            Vec3 hitVec = direct.getDeltaMovement();
            if (hitVec.lengthSqr() > 1.0E-8) {
                OBB obb = self.getMainCubeOBB().obb();
                Vec3 corePos = self.relativeRotPos(new Vec3(obb.center()), false);
                Vec3 diff = corePos.subtract(hitPos);
                Vec3 cross = diff.cross(hitVec);
                double distanceToCore = cross.length() / hitVec.length();
                double distanceMax = obb.extents().get(obb.extents().maxComponent()) * 2;
                if (Double.isFinite(distanceMax) && distanceMax != 0d) {
                    scale = (distanceMax - distanceToCore) / distanceMax;
                }
            }
        }
        return effectiveAmount * (float) scale;
    }

    private static Player resolveDebugPlayer(Entity attacker) {
        if (attacker instanceof Player player) {
            return player;
        }
        if (attacker instanceof AbstractVehicle attackerVehicle && attackerVehicle.getDriver() instanceof Player player) {
            return player;
        }
        return null;
    }
}
