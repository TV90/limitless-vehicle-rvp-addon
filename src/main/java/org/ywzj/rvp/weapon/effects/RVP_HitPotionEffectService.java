package org.ywzj.rvp.weapon.effects;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.ywzj.rvp.weapon.data.RVP_PotionEffectEntry;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;

import java.util.ArrayList;
import java.util.List;

/**
 * 命中药水效果施加服务（2026-09-22 MCHeli {@code AddPotionEffect} 机制移植）。
 *
 * <p>两个入口：{@link #applyForDirectHit}（直击命中，满时长；命中载具波及全体乘员）
 * 与 {@link #applyForExplosion}（爆炸，时长按离爆心距离线性衰减）。目标过滤复用
 * {@link RVP_DetonateApplier#matchesTarget}。全部仅服务端调用。</p>
 */
public final class RVP_HitPotionEffectService {

    /** 衰减后时长低于该值（tick）视为 0，不施加剧短垃圾效果。 */
    private static final int MIN_EFFECTIVE_TICKS = 20;

    /** 杀伤半径边缘处的时长保留比例（2026-09-22 用户定版：边缘衰减到爆心的 30%）。 */
    private static final float EDGE_SCALE = 0.3F;

    private RVP_HitPotionEffectService() {}

    /**
     * 直击命中（满时长）：命中载具 → 对全体乘员 LivingEntity 施加；命中普通生物 → 对其本体施加。
     * 每个目标各自 new {@link MobEffectInstance}（MCHeli 同语义，多目标互不共享实例）。
     */
    public static void applyForDirectHit(ServerLevel level, Entity hitEntity,
                                         List<RVP_PotionEffectEntry> entries,
                                         @Nullable Entity owner, @Nullable AbstractVehicle shooterVehicle) {
        if (entries == null || entries.isEmpty()) {
            return;
        }
        if (hitEntity instanceof AbstractVehicle vehicle) {
            // 命中载具：枚举全体乘员（含玩家与 AI gunner），逐人施加——同 RVP_LaserBlindService 范式
            for (Entity passenger : vehicle.getPassengers()) {
                if (passenger instanceof LivingEntity living) {
                    applyAll(living, entries, owner, shooterVehicle, 1.0F);
                }
            }
        } else if (hitEntity instanceof LivingEntity living) {
            applyAll(living, entries, owner, shooterVehicle, 1.0F);
        }
    }

    /**
     * 爆炸（时长按离爆心距离线性衰减）：杀伤半径盒内全体 LivingEntity（载具乘员作为独立
     * 实体各自按自身与爆心的距离判定），爆心 = 满时长、半径边缘 = 0。
     */
    public static void applyForExplosion(ServerLevel level, Vec3 center, float radius,
                                         List<RVP_PotionEffectEntry> entries,
                                         @Nullable Entity owner, @Nullable AbstractVehicle shooterVehicle) {
        if (entries == null || entries.isEmpty() || radius <= 0) {
            return;
        }
        // 先一次性解析药水 ID 并过滤无效条目，避免逐实体重复查注册表
        List<RVP_PotionEffectEntry> active = new ArrayList<>(entries.size());
        for (RVP_PotionEffectEntry entry : entries) {
            if (entry.isActive() && resolveEffect(entry.getEffect()) != null) {
                active.add(entry);
            }
        }
        if (active.isEmpty()) {
            return;
        }
        AABB box = new AABB(center, center).inflate(radius);
        for (LivingEntity living : level.getEntitiesOfClass(LivingEntity.class, box)) {
            double distance = living.position().distanceTo(center);
            applyAll(living, active, owner, shooterVehicle, durationScale(distance, radius));
        }
    }

    /**
     * 距离时长缩放（纯函数，供单测）：爆心 1.0 → 杀伤半径边缘衰减到 {@link #EDGE_SCALE}
     * （30%），线性；缩放后不足 {@value #MIN_EFFECTIVE_TICKS} tick 返回 0。
     */
    public static int scaledDuration(int baseTicks, double distance, float radius) {
        if (radius <= 0 || baseTicks <= 0) {
            return 0;
        }
        int scaled = (int) Math.round(baseTicks * durationScale(distance, radius));
        return scaled >= MIN_EFFECTIVE_TICKS ? scaled : 0;
    }

    /** 时长缩放系数（爆心 1.0 → 边缘 0.3），与 {@link #scaledDuration} 同公式，供逐条计算。 */
    private static float durationScale(double distance, float radius) {
        if (radius <= 0) {
            return 0f;
        }
        double t = Math.min(Math.max(distance / radius, 0.0), 1.0);
        return (float) (1.0 - (1.0 - EDGE_SCALE) * t);
    }

    /** 对单个目标按列表逐条施加；scale 为时长缩放（直击 1.0），缩放后不足阈值跳过该条。 */
    private static void applyAll(LivingEntity target, List<RVP_PotionEffectEntry> entries,
                                 @Nullable Entity owner, @Nullable AbstractVehicle shooterVehicle,
                                 float durationScale) {
        for (RVP_PotionEffectEntry entry : entries) {
            if (!entry.isActive()) {
                continue;
            }
            int duration = (int) Math.round(entry.getDurationTicks() * durationScale);
            if (duration < MIN_EFFECTIVE_TICKS) {
                continue;
            }
            if (!RVP_DetonateApplier.matchesTarget(entry.getTargets(), target, owner, shooterVehicle)) {
                continue;
            }
            MobEffect effect = resolveEffect(entry.getEffect());
            if (effect == null) {
                continue;
            }
            target.addEffect(new MobEffectInstance(effect, duration, entry.getAmplifier()));
        }
    }

    /** 药水 ID 解析（与 RVP_DetonateApplier.parseId 同语义：缺省命名空间补 minecraft:）。 */
    @Nullable
    private static MobEffect resolveEffect(String id) {
        if (id == null || id.isBlank()) {
            return null;
        }
        String trimmed = id.trim();
        if (!trimmed.contains(":")) {
            trimmed = "minecraft:" + trimmed;
        }
        ResourceLocation key = ResourceLocation.tryParse(trimmed);
        if (key == null || !BuiltInRegistries.MOB_EFFECT.containsKey(key)) {
            return null;
        }
        return BuiltInRegistries.MOB_EFFECT.get(key);
    }
}
