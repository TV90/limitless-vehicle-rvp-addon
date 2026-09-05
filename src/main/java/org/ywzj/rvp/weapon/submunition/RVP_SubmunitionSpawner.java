package org.ywzj.rvp.weapon.submunition;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.network.chat.Component;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.TagParser;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.ywzj.rvp.entity.projectile.RVP_BaseBullet;
import org.ywzj.rvp.debug.RVP_ProjectileLifecycleDebug;
import org.ywzj.rvp.debug.RVP_TopAttackDebug;
import org.ywzj.rvp.weapon.data.RVP_EnumSubmunitionPayloadKind;
import org.ywzj.rvp.weapon.data.RVP_EnumWeaponKind;
import org.ywzj.rvp.weapon.data.RVP_Explosion;
import org.ywzj.rvp.weapon.data.RVP_SubmunitionPayloadData;
import org.ywzj.rvp.weapon.data.RVP_SubmunitionReleaseData;
import org.ywzj.rvp.weapon.data.RVP_SubmunitionSpreadData;
import org.ywzj.rvp.weapon.data.RVP_WeaponData;
import org.ywzj.rvp.weapon.core.RVP_ProjectileEntityFactory;
import org.ywzj.vehicle.custom.CommonAssetsManager;
import org.ywzj.vehicle.custom.weapon.VehicleWeaponIndex;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;
import org.ywzj.vehicle.util.VectorUtil;

/**
 * Spawns RVP projectiles or arbitrary entities for {@link RVP_SubmunitionRunner}.
 */
public final class RVP_SubmunitionSpawner {

    public static final int MAX_DEPTH = 4;

    private RVP_SubmunitionSpawner() {}

    public static int spawnReleaseWave(RVP_BaseBullet parent, RVP_SubmunitionReleaseData release, int eventsThisTick) {
        if (parent.level().isClientSide() || parent.getRvpData() == null) {
            RVP_TopAttackDebug.noteSpawn(parent, "SPAWN skip clientOrRvpDataNull events=" + eventsThisTick);
            return 0;
        }
        if (parent.getSubmunitionDepth() >= MAX_DEPTH) {
            RVP_TopAttackDebug.noteSpawn(parent, "SPAWN skip depth=" + parent.getSubmunitionDepth());
            return 0;
        }
        int spawned = 0;
        int globalPellet = 0;
        for (int event = 0; event < eventsThisTick; event++) {
            for (RVP_SubmunitionPayloadData payload : release.getPayloads()) {
                int count = payload.getCount();
                for (int i = 0; i < count; i++) {
                    if (spawnPayload(parent, release, payload, globalPellet, count)) {
                        spawned++;
                    }
                    globalPellet++;
                }
            }
        }
        return spawned;
    }

    private static boolean spawnPayload(RVP_BaseBullet parent, RVP_SubmunitionReleaseData release,
                                        RVP_SubmunitionPayloadData payload,
                                        int pelletIndex, int pelletCount) {
        return switch (payload.getKind()) {
            case RVP_WEAPON -> spawnRvpWeapon(parent, release, payload, pelletIndex, pelletCount);
            case ENTITY -> spawnEntity(parent, release, payload, pelletIndex, pelletCount);
        };
    }

    private static boolean spawnRvpWeapon(RVP_BaseBullet parent, RVP_SubmunitionReleaseData release,
                                          RVP_SubmunitionPayloadData payload,
                                          int pelletIndex, int pelletCount) {
        ResourceLocation parentId = parent.getWeaponId();
        ResourceLocation childId = payload.resolveWeaponId(parentId);
        if (childId == null) {
            RVP_TopAttackDebug.noteSpawn(parent, "SPAWN fail childId=null");
            return false;
        }
        RVP_WeaponData childData = loadWeaponData(childId);
        if (childData == null) {
            RVP_TopAttackDebug.noteSpawn(parent, "SPAWN fail childData=null childId=" + childId
                    + " (子武器 JSON 未加载：检查 weapons/" + childId.getPath() + ".json 的 ammo 字段等)");
            return false;
        }
        RVP_EnumWeaponKind kind = childData.getWeaponKind();
        // 调用本项目统一实体工厂：子弹药与载具/炮火投送共享同一份 kind → EntityType 映射。
        EntityType<? extends Projectile> entityType = RVP_ProjectileEntityFactory.entityTypeFor(kind);
        if (entityType == null) {
            RVP_TopAttackDebug.noteSpawn(parent, "SPAWN fail entityType=null kind=" + kind + " childId=" + childId);
            return false;
        }
        Level level = parent.level();
        // 调用本项目统一实体工厂构造类型化 RVP 弹体，避免三条生成链维护重复 switch。
        RVP_BaseBullet child = RVP_ProjectileEntityFactory.create(kind, entityType, level, childData);
        if (child == null) {
            RVP_TopAttackDebug.noteSpawn(parent, "SPAWN fail createProjectile=null kind=" + kind + " childId=" + childId);
            return false;
        }
        LivingEntity shooter = parent.getOwner() instanceof LivingEntity living ? living : null;
        AbstractVehicle vehicle = parent.getShooterVehicle();
        RVP_BaseBullet.AimRot refAim = referenceAim(parent);
        RVP_BaseBullet.AimRot launchAim = resolveLaunchAim(payload, refAim);
        // 调用本项目释放云采样工具：先把子体权威初始位置散布到 release 配置的球体积内。
        Vec3 cloudPos = RVP_SubmunitionReleaseCloudUtil.samplePosition(
                parent.position(), release, level.getRandom());
        // 调用本项目 payload 位置散布器：在释放云位置之上继续叠加载荷自身的位置偏移。
        Vec3 pos = RVP_SubmunitionSpreadApplicator.applyPositionOffset(
                cloudPos, launchAim.xRot(), launchAim.yRot(),
                payload.getSpread(), pelletIndex, pelletCount, level.getRandom());
        // 调用本项目速度生成器：把最终出生点相对释放点的偏移传入，使云径向模式沿云心外向采样。
        SpawnVelocity spawnVelocity = buildVelocity(parent, payload, launchAim, refAim,
                pos.subtract(parent.position()), pelletIndex, pelletCount);
        Vec3 velocity = spawnVelocity.totalVelocity();
        child.setSubmunitionDepth(parent.getSubmunitionDepth() + 1);
        child.initFromWeapon(childData, kind, vehicle, shooter, pos,
                launchAim, velocity);
        // 调用本项目弹体风向捕获：按子体配置固化世界风向或释放 Tick 母弹当前朝向的反向。
        child.captureWindDirectionFromParent(parent);
        child.setShooterWeaponUnit(parent.getShooterWeaponUnit());
        child.name = Component.translatable(childData.getName());
        // allow_submunition on the *spawn payload*: child may run its own weapon submunition_data (multi-stage).
        if (!payload.isAllowSubmunition()) {
            child.disableSubmunitionReleases();
        }
        Float damageMult = payload.getDamageMultiplier();
        if (damageMult != null) {
            child.damage = Math.max(0.01f, child.damage * damageMult);
        }
        if (payload.isSuppressExplosion() && child.explosion != null) {
            child.explosion = RVP_Explosion.disabled();
        }
        child.setDeltaMovement(velocity);
        // 调用本项目部署运动初始化：把散布速度及母弹水平继承交给各轴独立半衰期，显式冲量保持基础弹道语义。
        child.initializeSubmunitionDeploymentMotion(spawnVelocity.deploymentVelocity());
        child.finalizeSpawnOrientation(new RVP_BaseBullet.AimRot(child.getXRot(), child.getYRot()));
        RVP_ProjectileLifecycleDebug.noteSpawnReady(child, parent);
        // 子弹药同样在成功入世后预热，避免其首 Tick 缺少动态路径 Ticket。
        boolean added = level.addFreshEntity(child);
        RVP_TopAttackDebug.noteSpawn(parent, "SPAWN ok child=" + childId + " kind=" + kind
                + " pos=(" + String.format("%.1f,%.1f,%.1f", pos.x, pos.y, pos.z) + ")");
        if (added) {
            child.primeDynamicChunkPath();
        }
        return added;
    }

    private static boolean spawnEntity(RVP_BaseBullet parent, RVP_SubmunitionReleaseData release,
                                       RVP_SubmunitionPayloadData payload,
                                       int pelletIndex, int pelletCount) {
        ResourceLocation typeId = payload.resolveEntityType();
        if (typeId == null) {
            return false;
        }
        Level level = parent.level();
        EntityType<?> type = net.minecraftforge.registries.ForgeRegistries.ENTITY_TYPES.getValue(typeId);
        if (type == null) {
            return false;
        }
        Entity entity = type.create(level);
        if (entity == null) {
            return false;
        }
        RVP_BaseBullet.AimRot refAim = referenceAim(parent);
        RVP_BaseBullet.AimRot launchAim = resolveLaunchAim(payload, refAim);
        // 调用本项目释放云采样工具：普通实体载荷与 RVP 弹体使用相同的权威球体积位置。
        Vec3 cloudPos = RVP_SubmunitionReleaseCloudUtil.samplePosition(
                parent.position(), release, level.getRandom());
        // 调用本项目 payload 位置散布器：允许配置作者继续叠加载荷级位置散布。
        Vec3 pos = RVP_SubmunitionSpreadApplicator.applyPositionOffset(
                cloudPos, launchAim.xRot(), launchAim.yRot(),
                payload.getSpread(), pelletIndex, pelletCount, level.getRandom());
        entity.moveTo(pos.x, pos.y, pos.z, launchAim.yRot(), launchAim.xRot());
        applyEntityNbt(entity, payload.getEntityNbt());
        // 调用本项目速度生成器：普通实体载荷也复用最终出生点对应的云心外向采样规则。
        SpawnVelocity spawnVelocity = buildVelocity(parent, payload, launchAim, refAim,
                pos.subtract(parent.position()), pelletIndex, pelletCount);
        entity.setDeltaMovement(spawnVelocity.totalVelocity());
        if (entity instanceof Projectile projectile) {
            projectile.setOwner(parent.getOwner());
        }
        level.addFreshEntity(entity);
        return true;
    }

    private static SpawnVelocity buildVelocity(RVP_BaseBullet parent, RVP_SubmunitionPayloadData payload,
                                               RVP_BaseBullet.AimRot launchAim, RVP_BaseBullet.AimRot baseRefAim,
                                               Vec3 spawnOffset,
                                               int pelletIndex, int pelletCount) {
        RandomSource random = parent.level().getRandom();
        if (payload.isLaunchAnglesEnabled()) {
            // 发射角度模式：方向固定为 launchAim（绝对或相对基准），速度取 launch_speed 或父弹速度长度 × scale
            float speed = payload.getLaunchSpeed() > 0f
                    ? payload.getLaunchSpeed()
                    : (float) parent.getDeltaMovement().length() * payload.getVelocityScale();
            Vec3 velocity = VectorUtil.rotToVec(launchAim.xRot(), launchAim.yRot()).normalize().scale(speed);
            Vec3 spreadVelocity = RVP_SubmunitionSpreadApplicator.applyVelocitySpread(
                    velocity, launchAim.xRot(), launchAim.yRot(),
                    payload.getSpread(), spawnOffset, pelletIndex, pelletCount, random);
            // 调用本项目水平继承工具：速度散布生成后只追加母弹 X/Z × velocity_scale，不继承 Y。
            Vec3 parentHorizontal = RVP_SubmunitionVelocityUtil.resolveParentHorizontalVelocity(
                    parent.getDeltaMovement(), payload);
            Vec3 deploymentHorizontal = new Vec3(
                    spreadVelocity.x + parentHorizontal.x, 0.0D,
                    spreadVelocity.z + parentHorizontal.z);
            Vec3 baseVelocity = Vec3.ZERO;
            // 调用本项目速度工具：显式 payloads_velocity 属于基础弹道，不纳入部署半衰期。
            baseVelocity = RVP_SubmunitionVelocityUtil.applyConfiguredImpulse(baseVelocity, payload, random);
            Vec3 deploymentVelocity = deploymentHorizontal.add(0.0D, spreadVelocity.y, 0.0D);
            return new SpawnVelocity(baseVelocity.add(deploymentVelocity), deploymentVelocity);
        }
        Vec3 velocity = Vec3.ZERO;
        if (payload.isInheritParentVelocity() && !payload.isInheritParentHorizontalVelocity()) {
            velocity = parent.getDeltaMovement();
        }
        if (payload.isInheritVehicleVelocity() && parent.getShooterVehicle() != null) {
            velocity = velocity.add(parent.getShooterVehicle().getDeltaMovement());
        }
        float scale = payload.getVelocityScale();
        if (scale != 1f) {
            velocity = velocity.scale(scale);
        }
        if (velocity.lengthSqr() < 1.0E-8) {
            velocity = parent.getDeltaMovement().normalize().scale(0.5);
        }
        Vec3 spreadVelocity = RVP_SubmunitionSpreadApplicator.applyVelocitySpread(
                velocity, baseRefAim.xRot(), baseRefAim.yRot(),
                payload.getSpread(), spawnOffset, pelletIndex, pelletCount, random);
        // 调用本项目水平继承工具：显式水平模式覆盖完整父弹继承，且只在散布后追加一次。
        Vec3 parentHorizontal = RVP_SubmunitionVelocityUtil.resolveParentHorizontalVelocity(
                parent.getDeltaMovement(), payload);
        Vec3 deploymentHorizontal = new Vec3(
                spreadVelocity.x + parentHorizontal.x, 0.0D,
                spreadVelocity.z + parentHorizontal.z);
        Vec3 baseVelocity = Vec3.ZERO;
        // 调用本项目速度工具：显式 payloads_velocity 属于基础弹道，不纳入部署半衰期。
        baseVelocity = RVP_SubmunitionVelocityUtil.applyConfiguredImpulse(baseVelocity, payload, random);
        Vec3 deploymentVelocity = deploymentHorizontal.add(0.0D, spreadVelocity.y, 0.0D);
        return new SpawnVelocity(baseVelocity.add(deploymentVelocity), deploymentVelocity);
    }

    /** 子弹药生成总速度及其中可由水平、纵向半衰期分别衰减的部署分量。 */
    private record SpawnVelocity(Vec3 totalVelocity, Vec3 deploymentVelocity) {}

    /**
     * 解析发射基准角：payload 启用发射角度时，
     * {@code absolute} = 世界系固定角度 {@code (launch_pitch, launch_yaw)}；
     * {@code relative} = 母弹当前姿态（baseRefAim）叠加 {@code (launch_pitch, launch_yaw)}；
     * 未启用时原样返回母弹弹轴方向。
     */
    private static RVP_BaseBullet.AimRot resolveLaunchAim(RVP_SubmunitionPayloadData payload,
                                                          RVP_BaseBullet.AimRot baseRefAim) {
        if (!payload.isLaunchAnglesEnabled()) {
            return baseRefAim;
        }
        if (payload.isLaunchAngleAbsolute()) {
            return new RVP_BaseBullet.AimRot(payload.getLaunchPitch(), payload.getLaunchYaw());
        }
        return new RVP_BaseBullet.AimRot(
                baseRefAim.xRot() + payload.getLaunchPitch(),
                baseRefAim.yRot() + payload.getLaunchYaw());
    }

    private static RVP_BaseBullet.AimRot referenceAim(RVP_BaseBullet parent) {
        Vec3 velocity = parent.getDeltaMovement();
        if (velocity.lengthSqr() > 1.0E-8) {
            Vec2 rot = VectorUtil.vecToRot(velocity.normalize());
            return new RVP_BaseBullet.AimRot(rot.x, rot.y);
        }
        return new RVP_BaseBullet.AimRot(parent.getXRot(), parent.getYRot());
    }

    private static void applyEntityNbt(Entity entity, String snbt) {
        if (snbt == null || snbt.isBlank()) {
            return;
        }
        try {
            CompoundTag tag = TagParser.parseTag(snbt);
            entity.load(tag);
        } catch (CommandSyntaxException ignored) {
            // Invalid SNBT in JSON — skip silently.
        }
    }

    @Nullable
    public static RVP_WeaponData loadWeaponData(ResourceLocation weaponId) {
        return CommonAssetsManager.vehicleWeaponManager().getIndex(weaponId)
                .map(VehicleWeaponIndex::data)
                .filter(RVP_WeaponData.class::isInstance)
                .map(RVP_WeaponData.class::cast)
                .orElse(null);
    }

}
