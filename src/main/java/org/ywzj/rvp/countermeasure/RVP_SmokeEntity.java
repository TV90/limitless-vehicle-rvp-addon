package org.ywzj.rvp.countermeasure;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.network.PlayMessages;
import org.jetbrains.annotations.NotNull;
import org.ywzj.rvp.all.RVP_Entities;
import org.ywzj.vehicle.api.entity.SightObstruction;
import org.ywzj.vehicle.particle.SmokeCloudOption;

/**
 * 烟雾弹实体（地面载具干扰物）。
 *
 * <p>出膛后先做短弹道飞行（初速 / 重力），{@code explodeTick}（默认 10 tick）后爆炸：
 * 置 {@code DATA_EXPLODED}、停止弹道、AABB 从 0 膨胀到目标半径，形成"禁视区"。
 * 实现 {@link SightObstruction}（空标记接口）：目标中心落入 AABB 内 → IR/AIR/光学制导脱锁进惯导；
 * 弹目射线穿过云团同样视为视线遮挡。
 * 双端安全：服务端控制弹道/爆炸/半径膨胀/上浮/存活；客户端按 {@link SynchedEntityData}
 * （存活 / 目标半径 / 爆炸时刻）计算当前半径渲染大灰云 + 粒子，不逐 tick 同步。</p>
 */
public class RVP_SmokeEntity extends Entity implements RVP_Decoy, SightObstruction {

    private static final EntityDataAccessor<Integer> DATA_LIFETIME =
            SynchedEntityData.defineId(RVP_SmokeEntity.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Float> DATA_RADIUS =
            SynchedEntityData.defineId(RVP_SmokeEntity.class, EntityDataSerializers.FLOAT);
    private static final EntityDataAccessor<Integer> DATA_EXPLODE_TICK =
            SynchedEntityData.defineId(RVP_SmokeEntity.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Boolean> DATA_EXPLODED =
            SynchedEntityData.defineId(RVP_SmokeEntity.class, EntityDataSerializers.BOOLEAN);

    /** 膨胀时长上限（tick）：爆炸后云团在 min(40, (lifetime-explode)/2) tick 内从 0 膨胀到目标半径。 */
    private static final int GROW_TICKS_CAP = 40;

    /** 下落加速度（仅服务端弹道飞行段用）。 */
    private float gravity;

    public RVP_SmokeEntity(EntityType<?> type, Level level) {
        super(type, level);
    }

    /** 客户端生成工厂（存活/半径/爆炸时刻经 SynchedEntityData 随生成包同步）。 */
    public RVP_SmokeEntity(PlayMessages.SpawnEntity spawn, Level level) {
        this(RVP_Entities.RVP_SMOKE.get(), level);
    }

    /** 服务端生成后初始化（存活 / 目标半径 / 爆炸时刻 / 运动参数）。 */
    public void initSmoke(RVP_CountermeasureSmokeData data) {
        this.gravity = data.getGravity();
        this.entityData.set(DATA_LIFETIME, data.getLifetimeTick());
        this.entityData.set(DATA_RADIUS, data.getRadius());
        this.entityData.set(DATA_EXPLODE_TICK, data.getExplodeTick());
        this.entityData.set(DATA_EXPLODED, false);
    }

    /** 目标（最终）半径（格）。 */
    public float getTargetRadius() {
        return entityData.get(DATA_RADIUS);
    }

    /** 存活时长（tick）。 */
    public int getLifetimeTick() {
        return entityData.get(DATA_LIFETIME);
    }

    /** 出膛后延迟多少 tick 爆炸生成烟幕。 */
    public int getExplodeTick() {
        return Math.max(0, entityData.get(DATA_EXPLODE_TICK));
    }

    /** 是否已爆炸（生成烟幕）。 */
    public boolean isExploded() {
        return entityData.get(DATA_EXPLODED);
    }

    /**
     * 当前半径（格）：爆炸前为 0；爆炸后从 0 随时间膨胀到目标半径。
     * 客户端用同一公式（基于 tickCount 与 DATA_EXPLODE_TICK），无需逐 tick 同步当前半径。
     */
    public float getCurrentRadius() {
        if (!isExploded()) {
            return 0.0F;
        }
        int explode = getExplodeTick();
        int age = Math.max(0, tickCount - explode);
        int lifetime = Math.max(1, getLifetimeTick());
        int growTicks = Math.max(1, Math.min(GROW_TICKS_CAP, Math.max(1, lifetime - explode) / 2));
        return Math.min(getTargetRadius(), (float) age / growTicks * getTargetRadius());
    }

    @Override
    public @NotNull RVP_EnumCountermeasureType rvp$decoyType() {
        return RVP_EnumCountermeasureType.SMOKE;
    }

    /**
     * 客户端位置直接快照，不做插值追赶：位置包一到就落位，避免爆炸后客户端还在 lerp
     * 追赶旧位置导致粒子沿移动轨迹生成拖尾/位移。
     */
    @Override
    public void lerpTo(double pX, double pY, double pZ, float pYaw, float pPitch,
                       int pPosRotationIncrements, boolean pTeleport) {
        this.setPos(pX, pY, pZ);
        this.setRot(pYaw, pPitch);
    }

    @Override
    public void tick() {
        super.tick();
        // 半径/形态变化后刷新碰撞箱，使 AABB 与当前状态一致（禁视区实时生效）
        this.setBoundingBox(makeBoundingBox());
        if (level().isClientSide) {
            tickParticle();
            return;
        }
        // 存活到期销毁
        if (tickCount >= getLifetimeTick()) {
            this.discard();
            return;
        }
        if (tickCount >= getExplodeTick()) {
            // 爆炸：置位（同步客户端），播放烟雾爆炸音效（对齐本体 SmokeGrenadeEntity.onHit），
            // 停止弹道运动。爆炸后严格静止在 AABB 中心不动（不做上浮/位移，
            // 避免客户端渲染插值把烟云拉向车体、与 AABB 位置不一致）
            if (!isExploded()) {
                this.entityData.set(DATA_EXPLODED, true);
                level().playSound(null, getX(), getY(), getZ(),
                        org.ywzj.vehicle.all.AllSounds.SMOKE_GRENADE_EXPLOSION.get(),
                        net.minecraft.sounds.SoundSource.NEUTRAL, 1.0F, 1.0F);
            }
            this.setDeltaMovement(0, 0, 0);
        } else {
            // 弹道飞行段：重力 + 位移（出膛初速由生成方 setDeltaMovement 注入）
            Vec3 v = getDeltaMovement();
            if (gravity > 0.0F) {
                v = v.subtract(0, gravity, 0);
            }
            this.setDeltaMovement(v);
            this.setPos(getX() + v.x, getY() + v.y, getZ() + v.z);
        }
    }

    /** 客户端粒子：爆炸后在云团体积内撒大量不透明大灰烟粒子（对齐本体 SmokeGrenadeEntity 样式，
     * 形成真正可见的烟幕遮蔽）。 */
    private void tickParticle() {
        if (!isExploded()) {
            return;
        }
        float cur = getCurrentRadius();
        if (cur <= 0.0F) {
            return;
        }
        Vec3 pos = position();
        // 本体同款：白色不透明、size 1→1.5、life 20，每帧 48 粒填满云体积
        SmokeCloudOption option = new SmokeCloudOption(true, 1, 1, 1, 1, 1, 1, 1f, 1f, 20, 1f, 1.5f, 0f);
        for (int i = 0; i < 48; i++) {
            // random.triangle(base, spread) = base + (r1-r2)*spread：
            // 必须 base=0 才得到对称区间 [-cur, cur]；若写成 triangle(-cur, cur) 会得到 [-2cur, 0]，
            // 粒子全部偏向一侧 → 云团整体位移
            double ox = this.random.triangle(0.0, cur);
            double oy = this.random.triangle(0.0, cur);
            double oz = this.random.triangle(0.0, cur);
            level().addParticle(option, true, pos.x + ox, pos.y + oy, pos.z + oz, 0, 0, 0);
        }
    }

    @Override
    protected AABB makeBoundingBox() {
        if (isExploded()) {
            float cur = getCurrentRadius();
            return AABB.ofSize(position(), cur * 2.0, cur * 2.0, cur * 2.0);
        }
        // 爆炸前：小弹体（禁视区未形成）
        return AABB.ofSize(position(), 0.5, 0.5, 0.5);
    }

    @Override
    protected void defineSynchedData() {
        entityData.define(DATA_LIFETIME, 200);
        entityData.define(DATA_RADIUS, 10.0F);
        entityData.define(DATA_EXPLODE_TICK, 10);
        entityData.define(DATA_EXPLODED, false);
    }

    @Override
    protected void readAdditionalSaveData(CompoundTag tag) {
        // 短期实体（noSave），无需持久化
    }

    @Override
    protected void addAdditionalSaveData(CompoundTag tag) {
        // 无持久化字段
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
