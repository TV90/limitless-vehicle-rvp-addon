package org.ywzj.rvp.countermeasure;

import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.network.PlayMessages;
import org.jetbrains.annotations.NotNull;
import org.ywzj.rvp.all.RVP_Entities;
import org.ywzj.vehicle.particle.SmokeCloudOption;

/**
 * 干扰物实体（热焰弹 / 箔条共用）。
 *
 * <p>双端安全：服务端负责运动（出膛初速 + 阻力 + 重力）与 {@code lifetimeTick} 存活销毁；
 * 客户端仅按 spawn 数据渲染（{@link #getGlowColor()} / {@link #getHaloScale()} / 类型）。
 * 干扰物不参与碰撞、不可拾取、不触发近炸（近炸/命中忽略见 M4 的引信判定）。
 * 外观数据经 {@link SynchedEntityData} 随生成包同步，无需逐 tick 同步。</p>
 */
public class RVP_DecoyEntity extends Entity implements RVP_Decoy {

    private static final EntityDataAccessor<Integer> DATA_TYPE =
            SynchedEntityData.defineId(RVP_DecoyEntity.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Integer> DATA_LIFETIME =
            SynchedEntityData.defineId(RVP_DecoyEntity.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Integer> DATA_GLOW_COLOR =
            SynchedEntityData.defineId(RVP_DecoyEntity.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Float> DATA_HALO_SCALE =
            SynchedEntityData.defineId(RVP_DecoyEntity.class, EntityDataSerializers.FLOAT);

    /** 空气阻力系数（仅服务端运动用，无需同步）。 */
    private float drag;
    /** 下落加速度（仅服务端运动用，无需同步）。 */
    private float gravity;

    public RVP_DecoyEntity(EntityType<?> type, Level level) {
        super(type, level);
    }

    /** 客户端生成工厂（spawn 数据经 SynchedEntityData 随包同步）。 */
    public RVP_DecoyEntity(PlayMessages.SpawnEntity spawn, Level level) {
        this(RVP_Entities.RVP_DECOY.get(), level);
    }

    /** 服务端生成后初始化（类型 / 存活 / 外观 / 运动参数）。 */
    public void initDecoy(RVP_EnumCountermeasureType type, RVP_CountermeasureDecoyData decoy) {
        this.drag = decoy.getDrag();
        this.gravity = decoy.getGravity();
        this.entityData.set(DATA_TYPE, type.ordinal());
        this.entityData.set(DATA_LIFETIME, decoy.getLifetimeTick());
        this.entityData.set(DATA_GLOW_COLOR, decoy.getGlowColor());
        this.entityData.set(DATA_HALO_SCALE, decoy.getHaloScale());
    }

    @Override
    public @NotNull RVP_EnumCountermeasureType rvp$decoyType() {
        int ordinal = entityData.get(DATA_TYPE);
        RVP_EnumCountermeasureType[] values = RVP_EnumCountermeasureType.values();
        return ordinal >= 0 && ordinal < values.length ? values[ordinal] : RVP_EnumCountermeasureType.FLARE;
    }

    public int getLifetimeTick() {
        return entityData.get(DATA_LIFETIME);
    }

    public int getGlowColor() {
        return entityData.get(DATA_GLOW_COLOR);
    }

    public float getHaloScale() {
        return entityData.get(DATA_HALO_SCALE);
    }

    @Override
    public void tick() {
        super.tick();
        if (level().isClientSide) {
            tickParticle();
            return;
        }
        // 存活期到期销毁
        if (tickCount >= getLifetimeTick()) {
            this.discard();
            return;
        }
        // 阻力：velocity *= (1 - drag)；重力：y -= gravity
        Vec3 v = getDeltaMovement().scale(Math.max(0.0, 1.0 - drag));
        v = v.subtract(0, gravity, 0);
        this.setDeltaMovement(v);
        this.setPos(position().add(v));
    }

    /** 客户端粒子：热焰弹 = 爆闪 + 后段浓烟；箔条 = 灰色细粒子云。 */
    private void tickParticle() {
        Vec3 pos = position();
        if (rvp$decoyType() == RVP_EnumCountermeasureType.FLARE) {
            if (tickCount % 2 == 0) {
                level().addParticle(ParticleTypes.FLASH, true, pos.x, pos.y, pos.z, 0, 0, 0);
            }
            int smokeCount = tickCount < 60 ? 1 : 3;
            for (int i = 0; i < smokeCount; i++) {
                level().addParticle(ParticleTypes.CAMPFIRE_COSY_SMOKE, true,
                        pos.x, pos.y, pos.z, 0, 0, 0);
            }
        } else {
            level().addParticle(new SmokeCloudOption(0.7F, 0.7F, 0.7F, 20, 0.3F, 0.0F), true,
                    pos.x, pos.y, pos.z, 0, 0, 0);
        }
    }

    @Override
    protected void defineSynchedData() {
        entityData.define(DATA_TYPE, 0);
        entityData.define(DATA_LIFETIME, 160);
        entityData.define(DATA_GLOW_COLOR, 0xFFFFFF);
        entityData.define(DATA_HALO_SCALE, 1.0F);
    }

    @Override
    protected void addAdditionalSaveData(net.minecraft.nbt.CompoundTag tag) {
        // 干扰物为短期实体（noSave），无需持久化；保留空实现以满足 Entity 抽象要求
    }

    @Override
    protected void readAdditionalSaveData(net.minecraft.nbt.CompoundTag tag) {
        // 无持久化字段，空实现
    }

    @Override
    public boolean isPickable() {
        return false;
    }

    @Override
    public boolean isPushable() {
        return false;
    }

    @Override
    public boolean shouldRender(double x, double y, double z) {
        return true;
    }
}
