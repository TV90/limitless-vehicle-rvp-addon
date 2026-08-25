package org.ywzj.rvp.entity.ecm;

import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.network.PlayMessages;
import org.jetbrains.annotations.NotNull;
import org.ywzj.rvp.all.RVP_Entities;

/**
 * 被动电子战假目标诱饵实体（ECM_PASSIVE 模块生成，替代旧版 {@code EwDecoyEntity}）。
 *
 * <p><b>隐形雷达幻影</b>：世界内不渲染（{@link #shouldRender} 返回 false），
 * 只在敌对雷达探测表/战术地图上呈现为一个带 NCTR 机型名与真实速度的空中触点。</p>
 *
 * <p>行为：</p>
 * <ul>
 *   <li>匀速直线漂移 + 微小航向抖动（无寻路/无碰撞/无重力），模拟空中目标；</li>
 *   <li>每 tick {@code EntityUtil.keepChunkLoaded} 保持自身区块加载
 *       （漂移远离玩家也不冻结，复用热焰弹成熟方案）；</li>
 *   <li>寿命到期自动销毁；不入存档（noSave + persist 关闭）；</li>
 *   <li>被击中时播放小型爆炸特效并立即销毁（"击落幻影"反馈）。</li>
 * </ul>
 *
 * <p>NCTR 名与归属仅服务端使用（NCTR 标签在服务端同步服务里解析），无需 SynchedEntityData。</p>
 */
public class RVP_EcmDecoyEntity extends Entity {

    private static final EntityDataAccessor<Integer> DATA_LIFETIME =
            SynchedEntityData.defineId(RVP_EcmDecoyEntity.class, EntityDataSerializers.INT);

    /** 归属 EW 载具实体 id（仅服务端使用：清理/IFF 过滤/调试）。 */
    private int ownerVehicleId = -1;
    /** NCTR 假标识机型名（仅服务端使用：雷达触点标签解析）。 */
    private String nctrName = "";

    public RVP_EcmDecoyEntity(EntityType<?> type, Level level) {
        super(type, level);
        // 区块保活由每 tick EntityUtil.keepChunkLoaded 承担（同热焰弹 RVP_DecoyEntity）
    }

    /** 客户端生成工厂。 */
    public RVP_EcmDecoyEntity(PlayMessages.SpawnEntity spawn, Level level) {
        this(RVP_Entities.RVP_ECM_DECOY.get(), level);
    }

    /** 服务端生成后初始化：归属、NCTR 名、寿命、漂移速度向量。 */
    public void initDecoy(int ownerVehicleId, String nctrName, int lifetimeTicks, Vec3 driftVelocity) {
        this.ownerVehicleId = ownerVehicleId;
        this.nctrName = nctrName == null ? "" : nctrName;
        this.entityData.set(DATA_LIFETIME, Math.max(1, lifetimeTicks));
        this.setDeltaMovement(driftVelocity);
    }

    public int getOwnerVehicleId() {
        return ownerVehicleId;
    }

    public String getNctrName() {
        return nctrName;
    }

    public int getLifetimeTick() {
        return entityData.get(DATA_LIFETIME);
    }

    @Override
    public void tick() {
        super.tick();
        if (level().isClientSide) {
            // 隐形实体：客户端无粒子无渲染，仅随服务端位置包移动
            return;
        }
        // 区块强载：保持自身与漂移方向区块加载（漂移远离玩家也不冻结）
        org.ywzj.vehicle.util.EntityUtil.keepChunkLoaded(this, this.position());
        if (tickCount >= getLifetimeTick()) {
            this.discard();
            return;
        }
        // 匀速直线漂移 + 极微小航向抖动（模拟飞机机动的不规则感，纯装饰）
        Vec3 v = getDeltaMovement();
        if (tickCount % 40 == 0) {
            double jitter = (this.random.nextDouble() - 0.5D) * 0.05D;
            v = v.add(jitter, 0.0D, jitter).normalize().scale(v.length());
        }
        this.setDeltaMovement(v);
        this.setPos(position().add(v));
    }

    @Override
    public boolean hurt(@NotNull DamageSource source, float amount) {
        // 被击落反馈：小型爆炸视觉/音效后销毁该枚幻影（仅服务端广播，零客户端类引用）
        if (!level().isClientSide && isAlive()) {
            ServerLevel serverLevel = (ServerLevel) level();
            Vec3 pos = position();
            serverLevel.sendParticles(ParticleTypes.EXPLOSION, pos.x, pos.y, pos.z, 3, 0.4D, 0.4D, 0.4D, 0.0D);
            serverLevel.sendParticles(ParticleTypes.POOF, pos.x, pos.y, pos.z, 8, 0.6D, 0.6D, 0.6D, 0.02D);
            level().playSound(null, pos.x, pos.y, pos.z,
                    SoundEvents.GENERIC_EXPLODE, SoundSource.NEUTRAL, 0.8F, 1.2F);
            this.discard();
            return true;
        }
        return false;
    }

    @Override
    protected void defineSynchedData() {
        entityData.define(DATA_LIFETIME, 200);
    }

    @Override
    protected void addAdditionalSaveData(net.minecraft.nbt.CompoundTag tag) {
        // 假目标为短期实体（noSave 不入存档），空实现满足 Entity 抽象要求
    }

    @Override
    protected void readAdditionalSaveData(net.minecraft.nbt.CompoundTag tag) {
        // 无持久化字段，空实现
    }

    @Override
    public boolean isPickable() {
        // 可被弹药命中（触发 §6.4 击落反馈）
        return true;
    }

    @Override
    public boolean isPushable() {
        return false;
    }

    @Override
    public boolean shouldRender(double x, double y, double z) {
        // 隐形：世界内不渲染（已批示），只存在于雷达触点
        return false;
    }

    @Override
    public boolean isNoGravity() {
        // 空中目标：不受重力（保持匀速直线漂移）
        return true;
    }
}
