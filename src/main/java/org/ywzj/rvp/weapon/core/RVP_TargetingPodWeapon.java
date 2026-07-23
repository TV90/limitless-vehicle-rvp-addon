package org.ywzj.rvp.weapon.core;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.ywzj.rvp.network.RVP_Network;
import org.ywzj.rvp.network.S2CGpsStateSync;
import org.ywzj.rvp.network.S2CMarkedBlockSync;
import org.ywzj.rvp.targeting.RVP_MarkedTargetManager;
import org.ywzj.rvp.weapon.data.RVP_TargetingPodData;
import org.ywzj.rvp.weapon.data.RVP_WeaponData;
import org.ywzj.rvp.weapon.gps.GPSTargetManager;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;
import org.ywzj.vehicle.vehicle.part.WeaponUnit;
import org.ywzj.vehicle.vehicle.pojo.AimContext;

import java.util.List;

/**
 * 目标指示吊舱武器。按下发射键时执行一次标记扫描，支持实体标记和方块标记。
 * 标记结果写入 {@link RVP_MarkedTargetManager}，通过战术揭示同步包广播给客户端。
 */
public class RVP_TargetingPodWeapon extends RVP_WeaponBase {

    public RVP_TargetingPodWeapon(AbstractVehicle vehicle, WeaponUnit unit, int index, RVP_WeaponData data, String serializeId) {
        super(vehicle, unit, index, data, serializeId);
    }

    @Override
    public void tick() {
        // 修复负数弹药残留（旧存档 max_capacity=-1 遗留），重置为 0 触发自动 reload
        if (getRemainAmmo() < 0) {
            setRemainAmmo(0);
        }
        super.tick();
    }

    @Override
    public boolean shoot(List<AimContext> aimContexts, LivingEntity shooter) {
        if (isCoolingDown() || isReloading() || aimContexts.isEmpty()) {
            return false;
        }
        if (!consumeAmmo(aimContexts)) {
            return false;
        }
        this.lastShootTime = System.currentTimeMillis();
        if (!(shooter instanceof ServerPlayer player)) {
            return true;
        }

        RVP_TargetingPodData podData = getData().getTargetingPodData();
        AimContext aim = aimContexts.get(0);
        Vec3 start = RVP_AimContexts.muzzle(aim);
        Vec3 look = Vec3.directionFromRotation(aim.direction.x, aim.direction.y).normalize();
        long gameTime = player.level().getGameTime();
        long expireTick = gameTime + podData.getMarkDuration();

        int entityMarked = 0;
        Vec3 blockPos = null;

        // 实体标记
        if (podData.isEntityMode()) {
            entityMarked = performEntityMark(player, start, look, podData, expireTick);
        }

        // 方块标记
        if (podData.isBlockMode()) {
            blockPos = performBlockMark(player, start, look, podData, expireTick);
        }

        // 标记完成提示
        if (entityMarked > 0 && blockPos != null) {
            player.displayClientMessage(Component.translatable("message.ywzj_rvp.targeting_pod.marked_both",
                    entityMarked, String.format("%.0f, %.0f, %.0f", blockPos.x, blockPos.y, blockPos.z)), true);
        } else if (entityMarked > 0) {
            player.displayClientMessage(Component.translatable("message.ywzj_rvp.targeting_pod.marked_entity",
                    entityMarked), true);
        } else if (blockPos != null) {
            player.displayClientMessage(Component.translatable("message.ywzj_rvp.targeting_pod.marked_block",
                    String.format("%.0f, %.0f, %.0f", blockPos.x, blockPos.y, blockPos.z)), true);
        } else {
            player.displayClientMessage(Component.translatable("message.ywzj_rvp.targeting_pod.no_target"), true);
        }

        return true;
    }

    /**
     * 锥形扫描标记实体。瞄准方向为锥轴，spotAngle为半角，spotRange为最大距离。
     * 扫描使用 {@link Entity} 而非 {@link LivingEntity}，以同时捕获载具（AbstractVehicle
     * 不继承 LivingEntity）与生物。
     *
     * @return 标记的实体数量
     */
    private int performEntityMark(ServerPlayer player, Vec3 start, Vec3 look,
                                   RVP_TargetingPodData podData, long expireTick) {
        float range = podData.getSpotRange();
        float halfAngleRad = (float) Math.toRadians(podData.getSpotAngle());
        double cosHalfAngle = Math.cos(halfAngleRad);

        AABB scanBox = new AABB(start.add(-range, -range, -range), start.add(range, range, range));
        AbstractVehicle ownVehicle = getVehicle();
        int ownVehicleId = ownVehicle.getId();
        List<Entity> candidates = player.level().getEntitiesOfClass(Entity.class, scanBox,
                e -> e != null && e.isAlive() && e != player && e.getId() != ownVehicleId);

        int marked = 0;
        for (Entity entity : candidates) {
            // 类型过滤
            if (!passesFilter(entity, podData)) {
                continue;
            }
            // IFF：友军跳过
            if (isFriendly(player, entity)) {
                continue;
            }
            // 距离检查
            double distSq = entity.distanceToSqr(start);
            if (distSq > range * range) {
                continue;
            }
            // 锥形角度检查
            Vec3 toEntity = entity.position().subtract(start).normalize();
            double dot = look.dot(toEntity);
            if (dot < cosHalfAngle) {
                continue;
            }
            // 视线检查
            Vec3 eyePos = entity.position().add(0, entity.getEyeHeight(), 0);
            BlockHitResult blockHit = player.level().clip(new ClipContext(start, eyePos,
                    ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, ownVehicle));
            if (blockHit.getType() == HitResult.Type.BLOCK) {
                continue;
            }
            // 标记
            RVP_MarkedTargetManager.markEntity(entity.getId(), expireTick);
            marked++;
        }
        return marked;
    }

    /**
     * 射线检测标记方块。命中方块位置写入标记管理器，可选写入GPS目标点。
     *
     * @return 标记的方块坐标
     */
    private Vec3 performBlockMark(ServerPlayer player, Vec3 start, Vec3 look,
                                  RVP_TargetingPodData podData, long expireTick) {
        float blockRange = podData.getBlockRange() != null
                ? podData.getBlockRange()
                : getData().getTargetingPodRange();
        Vec3 end = start.add(look.scale(blockRange));
        BlockHitResult blockHit = player.level().clip(new ClipContext(start, end,
                ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, getVehicle()));
        Vec3 target;
        if (blockHit.getType() == HitResult.Type.MISS) {
            target = end;
        } else {
            target = blockHit.getLocation();
        }

        // 写入方块标记
        RVP_MarkedTargetManager.markBlock(
                player.level().dimension().location(), target, expireTick);

        // 广播方块标记给客户端
        S2CMarkedBlockSync msg = new S2CMarkedBlockSync();
        msg.dimension = player.level().dimension().location();
        msg.blocks = RVP_MarkedTargetManager.getMarkedBlocks(player.level().dimension().location())
                .stream()
                .map(b -> new S2CMarkedBlockSync.MarkedBlockEntry(
                        (float) b.pos().x, (float) b.pos().y, (float) b.pos().z))
                .toList();
        RVP_Network.CHANNEL.send(
                net.minecraftforge.network.PacketDistributor.ALL.noArg(), msg);

        // 可选写入 GPS 目标点
        if (podData.isWriteGpsTarget()) {
            var snapshot = GPSTargetManager.applyCurrentMode(
                    player, player.level().dimension().location(), target);
            RVP_Network.CHANNEL.sendTo(
                    S2CGpsStateSync.of(snapshot),
                    player.connection.connection,
                    net.minecraftforge.network.NetworkDirection.PLAY_TO_CLIENT
            );
        }

        return target;
    }

    private boolean passesFilter(Entity entity, RVP_TargetingPodData podData) {
        if (entity instanceof AbstractVehicle) {
            return podData.isFilterVehicle();
        }
        if (entity instanceof net.minecraft.world.entity.player.Player) {
            return podData.isFilterPlayer();
        }
        if (entity instanceof LivingEntity) {
            return podData.isFilterLiving();
        }
        // 非载具、非玩家、非生物的实体（如掉落物、经验球）不标记
        return false;
    }

    private boolean isFriendly(ServerPlayer observer, Entity target) {
        // 简单 IFF：同队伍为友军
        var observerTeam = observer.getTeam();
        if (observerTeam == null) {
            return false;
        }
        return observerTeam.isAlliedTo(target.getTeam());
    }
}
