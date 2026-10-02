package org.ywzj.rvp.entity.projectile;

import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.network.PlayMessages;
import org.ywzj.rvp.all.RVP_Entities;
import org.ywzj.rvp.weapon.data.RVP_EnumWeaponKind;
import org.ywzj.rvp.weapon.data.RVP_EffectsData;
import org.ywzj.rvp.weapon.data.RVP_TorpedoData;
import org.ywzj.rvp.weapon.data.RVP_WeaponData;

/**
 * [RVP] 鱼雷实体（2026-10-02，{@code rvp:torpedo} 公开类型，运行时四态状态机）：
 *
 * <pre>
 * 出管(空中) ──入水边沿──▶ 入水过渡 ──lerp完成──▶ 水中巡航 ──出水边沿──▶ 出管(空中)
 *     │                        │                     │
 *     └───────── 命中 / 近炸 / delay延时 / 寿终自爆 ◀────┘
 * </pre>
 *
 * <ul>
 *   <li><b>出管段（空中）</b>：完全复用 {@link RVP_BaseBullet} 现有弹道
 *       （{@code projectile_data} 初速/重力/阻力/继承载具速度），与普通火箭弹无异；</li>
 *   <li><b>入水判定</b>：{@code isInWater()} 逐 tick 边沿对比 + {@code water_entry_min_depth}
 *       消抖（实体中心上方仍为水才算完全入水，防贴水皮抖动）；</li>
 *   <li><b>入水过渡</b>：速率从入水瞬间值向 {@code water_speed} 线性收敛
 *       （{@code water_entry_lerp_tick}，模拟入水减速与螺旋桨起转）；</li>
 *   <li><b>水中巡航</b>：方向保持（首版直航无转向）、速率恒为 {@code water_speed}
 *       （独立于 constant_speed 峰值回填体系）、Y 分量每 tick 乘 {@code depth_damping}
 *       后归一化（被动深度摆平）；</li>
 *   <li><b>出水</b>：回到空中弹道（受 gravity 下坠），再次入水再次过渡，状态天然循环。</li>
 * </ul>
 *
 * <p>命中链/近炸/攻顶/自毁/寿命/爆炸全部复用基类（近炸候选收集不判水，水下对舰近炸天然可用；
 * 巨型载具 §48 分节盲区补筛自动生效）；水下爆炸独立威力经
 * {@code RVP_Explosion.damage_in_water/radius_in_water} 在基类 {@code triggerExplosion}
 * 参数解析点生效。运动仅服务端执行（对标本体 MissileEntity：客户端用同步位置）；
 * 入水花与水中气泡尾迹由<b>客户端本地</b>判定入水状态生成，零网络包。</p>
 *
 * <p>设计文档：{@code docs/plan/RVP鱼雷武器实现方案_20261002.md}。</p>
 */
public class RVP_TorpedoEntity extends RVP_BaseBullet {

    /** 入水花粒子簇数量（客户端，一次性）。 */
    private static final int SPLASH_PARTICLE_COUNT = 16;
    /** 入水花水平散布半径（格）。 */
    private static final double SPLASH_SPREAD = 1.2;

    /** 服务端状态机：当前是否处于水中（出管段=false）。客户端不模拟运动，不读此字段。 */
    private boolean torpedoInWater = false;
    /** 服务端状态机：入水过渡剩余 tick（0 = 过渡完成，水中速率恒为 water_speed）。 */
    private int waterEntryLerpTicksLeft = 0;
    /** 服务端状态机：入水瞬间实际速率（过渡期的线性收敛起点）。 */
    private double waterEntrySpeed = 0.0;
    /** 客户端特效状态：上一 tick 是否处于水中（入水花边沿检测，仅客户端使用）。 */
    private boolean clientInWater = false;

    public RVP_TorpedoEntity(EntityType<? extends Projectile> type, Level level) {
        super(type, level);
    }

    public RVP_TorpedoEntity(EntityType<? extends Projectile> type, Level level, ResourceLocation weaponId) {
        super(type, level, weaponId);
    }

    public RVP_TorpedoEntity(PlayMessages.SpawnEntity msg, Level level) {
        super(RVP_Entities.RVP_TORPEDO.get(), level);
    }

    /** 客户端 Tick（基类 tick 客户端分支提前 return，特效需在其后独立驱动）。 */
    @Override
    public void tick() {
        super.tick();
        if (level().isClientSide()) {
            tickClientWaterEffects();
        }
    }

    /**
     * [RVP] 鱼雷运动分派：有效入水 → 水中定速直航；空中/出水 → 基类现有弹道。
     * 仅服务端执行（基类 tick 的运动调用点在服务端分支内）。
     */
    @Override
    protected void tickMotion() {
        RVP_TorpedoData data = resolveTorpedoData();
        if (data == null) {
            // 非鱼雷 kind 配置（防御）或配置缺失：完全退回基类行为
            super.tickMotion();
            return;
        }
        updateTorpedoWaterState(data);
        if (torpedoInWater) {
            tickTorpedoWaterMove(data);
            return;
        }
        super.tickMotion();
    }

    /**
     * [RVP] 水中尾迹服务端跳过：水中巡航段不再广播 trajectory_particle 服务端烟
     * （非推进弹体的广播烟会落在水下观感错误），水中尾迹由客户端本地气泡通道承担；
     * 空中出管段保持基类广播行为（出管烟迹）。
     */
    @Override
    protected void broadcastTrailParticles() {
        RVP_TorpedoData data = resolveTorpedoData();
        if (data != null && isEffectivelyInWater(data)) {
            return;
        }
        super.broadcastTrailParticles();
    }

    /**
     * [RVP] 客户端尾迹分派：水中巡航段生成水中气泡尾迹（water_trail_particle ×
     * water_trail_count）；空中段保持基类行为（无发动机时不产生本地尾迹，烟迹走服务端广播）。
     * 仅客户端调用（基类 tick 客户端分支）。
     */
    @Override
    protected void spawnTrailParticles() {
        RVP_TorpedoData data = resolveTorpedoData();
        if (data == null || !isEffectivelyInWater(data)) {
            super.spawnTrailParticles();
            return;
        }
        spawnWaterTrailParticles();
    }

    // ───────────────────────── 服务端状态机 ─────────────────────────

    /**
     * 解析鱼雷数据（<b>双端安全</b>，2026-10-02 修复）：非鱼雷 kind 返回 null。
     * ⚠️ {@code rvpData} 只在服务端 {@code initFromWeapon} 赋值、不随生成包同步
     * （{@code RVP_BaseBullet} :965 注释）——客户端必须经 {@link #resolveWeaponConfig()}
     * 走配置索引解析（与基类客户端特效同一条路），否则客户端特效分支整体静默失效
     * （首版实机"无入水花/无气泡尾迹"的根因）。
     */
    private RVP_TorpedoData resolveTorpedoData() {
        if (getWeaponKind() != RVP_EnumWeaponKind.TORPEDO) {
            return null;
        }
        RVP_WeaponData config = resolveWeaponConfig();
        return config == null ? null : config.getTorpedoData();
    }

    /** 有效入水判定：实体在水中，且中心上方 water_entry_min_depth 处仍为水（消抖）。 */
    private boolean isEffectivelyInWater(RVP_TorpedoData data) {
        return isInWater()
                && isWaterAt(level(), position().add(0, data.getWaterEntryMinDepth(), 0));
    }

    /**
     * 入水/出水边沿更新：由干转湿记录入水瞬间速率并启动过渡计时；由湿转干（跃出水面）
     * 清除过渡状态，回到空中弹道。
     */
    private void updateTorpedoWaterState(RVP_TorpedoData data) {
        boolean deepWet = isEffectivelyInWater(data);
        if (!torpedoInWater && deepWet) {
            torpedoInWater = true;
            waterEntrySpeed = getDeltaMovement().length();
            waterEntryLerpTicksLeft = data.getWaterEntryLerpTick();
        } else if (torpedoInWater && !deepWet) {
            torpedoInWater = false;
            waterEntryLerpTicksLeft = 0;
        }
    }

    /**
     * 水中运动（每 tick）：
     * <ol>
     *   <li>叠加水中重力 {@code gravity_in_water}（默认 0；depth_damping=1 时产生下沉/上浮弧线）；</li>
     *   <li>垂直速度乘 {@code depth_damping}（被动深度保持，俯仰自然摆平）；</li>
     *   <li>速率向目标收敛——过渡期从入水瞬间速率线性收敛到 {@code water_speed}，
     *       过渡完成后恒为 {@code water_speed}（定速直航）；</li>
     *   <li>方向保持（首版无转向），按 {@code rotate_to_motion} 配置更新姿态。</li>
     * </ol>
     * 位置积分/飞行速度状态/航程累计与基类弹道同序，保证自毁距离与寿命链路一致。
     */
    private void tickTorpedoWaterMove(RVP_TorpedoData data) {
        Vec3 velocity = getDeltaMovement();
        velocity = velocity.add(0, rvpData.getGravityInWater(), 0);
        velocity = new Vec3(velocity.x, velocity.y * data.getDepthDamping(), velocity.z);
        double targetSpeed = data.getWaterSpeed();
        if (waterEntryLerpTicksLeft > 0) {
            waterEntryLerpTicksLeft--;
            int total = Math.max(1, data.getWaterEntryLerpTick());
            double progress = 1.0 - (waterEntryLerpTicksLeft / (double) total);
            targetSpeed = waterEntrySpeed + (data.getWaterSpeed() - waterEntrySpeed) * progress;
        }
        double speed = velocity.length();
        Vec3 direction = speed > 1.0E-6 ? velocity.scale(1.0 / speed) : getLookAngle();
        velocity = direction.scale(targetSpeed);
        setDeltaMovement(velocity);
        setPos(position().add(velocity));
        updateFlightSpeedState(velocity);
        flightDistance += velocity.length();
        RVP_ProjectileMotion.applyRotationFromVelocity(this, velocity);
    }

    // ───────────────────────── 客户端特效（零网络包） ─────────────────────────

    /**
     * 客户端入水花边沿检测：由干转湿的瞬间在入水点生成水花粒子簇 + 播放入水音效。
     * 只使用 Level 公共 API（客户端实现、服务端空转），方法不会被服务端执行。
     */
    private void tickClientWaterEffects() {
        RVP_TorpedoData data = resolveTorpedoData();
        if (data == null) {
            clientInWater = false;
            return;
        }
        boolean deepWet = isEffectivelyInWater(data);
        if (deepWet && !clientInWater) {
            spawnWaterEntrySplash();
        }
        clientInWater = deepWet;
    }

    /** 入水瞬间水花（water_entry_particle，默认 minecraft:splash）+ 入水音效。 */
    private void spawnWaterEntrySplash() {
        RVP_WeaponData config = resolveWeaponConfig();
        RVP_EffectsData effects = config != null ? config.getEffectsData() : new RVP_EffectsData();
        Vec3 pos = position();
        ParticleOptions particle = resolveWaterParticle(effects.getWaterEntryParticle());
        if (particle != null) {
            for (int i = 0; i < SPLASH_PARTICLE_COUNT; i++) {
                // addParticle(force=true) 重载：绕过原版 32 格生成距离剔除，远观入水也见水花
                level().addParticle(particle, true,
                        pos.x + (random.nextFloat() - 0.5F) * SPLASH_SPREAD,
                        pos.y + random.nextFloat() * 0.2F,
                        pos.z + (random.nextFloat() - 0.5F) * SPLASH_SPREAD,
                        (random.nextFloat() - 0.5F) * 0.3,
                        0.2 + random.nextFloat() * 0.3,
                        (random.nextFloat() - 0.5F) * 0.3);
            }
        }
        playWaterEntrySound(effects.getWaterEntrySound());
    }

    /**
     * 水中巡航尾迹（两层）：①固定原版 {@code BUBBLE} ×2——水下气泡是鱼雷固有观感
     * （MCHR 基类对所有入水弹体同款，不随配置）；②可配 {@code water_trail_particle}
     * × water_trail_count（现役 JSON 配 {@code minecraft:cloud} 白浪团，远距可见）。
     */
    private void spawnWaterTrailParticles() {
        RVP_WeaponData config = resolveWeaponConfig();
        RVP_EffectsData effects = config != null ? config.getEffectsData() : new RVP_EffectsData();
        Vec3 pos = position();
        Vec3 behind = getLookAngle().scale(-0.4);
        // 1) 固定气泡层（近距水下细节）
        for (int i = 0; i < 2; i++) {
            level().addParticle(net.minecraft.core.particles.ParticleTypes.BUBBLE, true,
                    pos.x + behind.x + (random.nextFloat() - 0.5F) * 0.15,
                    pos.y + (random.nextFloat() - 0.5F) * 0.15,
                    pos.z + behind.z + (random.nextFloat() - 0.5F) * 0.15,
                    0, 0.02, 0);
        }
        // 2) 可配尾迹层（远距可见主尾迹）
        ParticleOptions particle = resolveWaterParticle(effects.getWaterTrailParticle());
        if (particle == null) {
            return;
        }
        int count = effects.getWaterTrailCount();
        for (int i = 0; i < count; i++) {
            // addParticle(force=true) 重载：原版不带 force 的 7 参 addParticle 在离相机 32 格外静默不生成
            // （ClientLevel.addParticle 距离剔除），远程尾迹必须用 force 重载（2026-10-02 用户实测）
            level().addParticle(particle, true,
                    pos.x + behind.x + (random.nextFloat() - 0.5F) * 0.1,
                    pos.y + (random.nextFloat() - 0.5F) * 0.1,
                    pos.z + behind.z + (random.nextFloat() - 0.5F) * 0.1,
                    0, 0, 0);
        }
    }

    /** 入水音效（water_entry_sound，空白 = 无声；注册表缺失时静默跳过）。 */
    private void playWaterEntrySound(String soundId) {
        if (soundId == null || soundId.isBlank()) {
            return;
        }
        ResourceLocation location = ResourceLocation.tryParse(soundId);
        if (location == null) {
            return;
        }
        BuiltInRegistries.SOUND_EVENT.getOptional(location).ifPresent(sound ->
                level().playLocalSound(position().x, position().y, position().z, sound,
                        SoundSource.AMBIENT, 1.0F, 1.0F, false));
    }

    /**
     * 水中特效粒子 id 解析（原版粒子命名空间全名，如 {@code minecraft:bubble}）：
     * {@code none}/{@code minecraft:none} 或解析失败/注册表缺失返回 null（跳过生成）。
     * 原版 {@code ParticleType} 本身实现 {@code ParticleOptions}，可直接用于 addParticle。
     */
    private static ParticleOptions resolveWaterParticle(String id) {
        if (id == null || id.isBlank() || "none".equals(id) || "minecraft:none".equals(id)) {
            return null;
        }
        ResourceLocation location = ResourceLocation.tryParse(id);
        if (location == null) {
            return null;
        }
        ParticleType<?> type = BuiltInRegistries.PARTICLE_TYPE.getOptional(location).orElse(null);
        return type instanceof ParticleOptions options ? options : null;
    }
}
