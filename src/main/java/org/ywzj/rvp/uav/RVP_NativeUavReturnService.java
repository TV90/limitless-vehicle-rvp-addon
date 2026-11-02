package org.ywzj.rvp.uav;

import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.ywzj.rvp.RVP_MOD;
import org.ywzj.vehicle.entity.misc.FakePlayer;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.BooleanSupplier;

/** 原生 uav:true 的自爆回传：离机前快照、显式传送、最后清理本车假操作员。 */
@Mod.EventBusSubscriber(modid = RVP_MOD.MOD_ID)
public final class RVP_NativeUavReturnService {
    /** 只保存本车生成的假操作员 UUID 与放飞点，不按名字搜索/清理世界中的其它实体。 */
    private static final String OPERATORS_TAG = "rvpNativeUavOperators";

    /** 部署身份优先于原生开关，兼容仍在飞的旧 uav:true 部署实例。 */
    public enum ReturnMode {
        /** 普通有人载具，沿用 MCH 的乘员承伤语义。 */
        MANNED,
        /** 母车部署实例，沿用 RVP 母车/返回锚点事务。 */
        DEPLOYED,
        /** 玩家直接使用的原生 UAV，必须完成放飞点回传。 */
        NATIVE
    }

    /**
     * 不可变放飞记录；原生下车回调清空返回字段后仍能使用。
     * @param dimension 放飞维度
     * @param position 放飞位置
     * @param yaw 放飞朝向，度
     * @param pitch 放飞俯仰，度
     * @param dummyId 本车为该玩家生成的假操作员 UUID
     */
    record OperatorAnchor(ResourceLocation dimension, Vec3 position, float yaw, float pitch, UUID dummyId) {}

    /**
     * 可独立验证的回传步骤；所有快照门禁先通过，全部离机后才开始传送。
     * @param ready 返回点与玩家状态是否有效
     * @param dismount 解除乘坐关系
     * @param dismounted 回读乘坐关系，防事件取消离机
     * @param teleport 显式传送并同步客户端
     * @param returned 最终回读维度、坐标与存活状态
     */
    record ReturnStep(BooleanSupplier ready, Runnable dismount, BooleanSupplier dismounted,
                      BooleanSupplier teleport, BooleanSupplier returned) {}

    private RVP_NativeUavReturnService() {}

    /** 不把“非部署实例”误当成“普通有人载具”。 */
    public static ReturnMode classify(boolean nativeUav, boolean deployed) {
        return deployed ? ReturnMode.DEPLOYED : nativeUav ? ReturnMode.NATIVE : ReturnMode.MANNED;
    }

    /** 三类回传由同一分派点执行；原生回传失败不能退回普通载具语义。 */
    public static boolean returnForMode(ReturnMode mode, BooleanSupplier deployedReturn,
                                        BooleanSupplier nativeReturn) {
        return switch (mode) {
            case MANNED -> true;
            case DEPLOYED -> deployedReturn.getAsBoolean();
            case NATIVE -> nativeReturn.getAsBoolean();
        };
    }

    /**
     * 本体 onEnterVehicle 先为假操作员设置玩家名/位置，再 addFreshEntity。
     * 此时真人的 getVehicle 已指向该车；利用这个生成时刻绑定 UUID，避免起爆时按名字猜实体。
     */
    @SubscribeEvent
    public static void onOperatorAdded(EntityJoinLevelEvent event) {
        if (!(event.getEntity() instanceof FakePlayer dummy)
                || !(event.getLevel() instanceof ServerLevel level)) return;
        ServerPlayer player = level.getServer().getPlayerList()
                .getPlayerByName(dummy.getEntityData().get(FakePlayer.NAME));
        if (player == null || player.level() != level
                || !(player.getVehicle() instanceof AbstractVehicle vehicle) || !vehicle.uav) return;
        CompoundTag operators = vehicle.getPersistentData().getCompound(OPERATORS_TAG);
        CompoundTag previous = operators.getCompound(player.getUUID().toString());
        if (previous.hasUUID("operator") && player.getUUID().equals(previous.getUUID("operator"))
                && previous.hasUUID("dummy") && !dummy.getUUID().equals(previous.getUUID("dummy"))) {
            // 同一 tick 离机又登机时，旧假操作员可能尚未自动消失；不能丢失它的 UUID 后留下伤害转发。
            ResourceLocation oldDimension = ResourceLocation.tryParse(previous.getString("dimension"));
            ServerLevel oldLevel = oldDimension == null ? null : findLevel(level.getServer(), oldDimension);
            if (oldLevel != null && oldLevel.getEntity(previous.getUUID("dummy")) instanceof FakePlayer oldDummy) {
                oldDummy.discard();
            }
        }
        CompoundTag tag = new CompoundTag();
        tag.putUUID("operator", player.getUUID());
        tag.putUUID("dummy", dummy.getUUID());
        tag.putString("dimension", level.dimension().location().toString());
        tag.putDouble("x", dummy.getX());
        tag.putDouble("y", dummy.getY());
        tag.putDouble("z", dummy.getZ());
        tag.putFloat("yaw", dummy.getYRot());
        tag.putFloat("pitch", dummy.getXRot());
        operators.put(player.getUUID().toString(), tag);
        vehicle.getPersistentData().put(OPERATORS_TAG, operators);
    }

    /** 只读本体公开保存接口的返回字段，不调用会清除返回点的下车位置方法。 */
    static Vec3 readNativeReturnPoint(CompoundTag tag) {
        if (!tag.contains("fakeOperatorPositionX", Tag.TAG_ANY_NUMERIC)
                || !tag.contains("fakeOperatorPositionY", Tag.TAG_ANY_NUMERIC)
                || !tag.contains("fakeOperatorPositionZ", Tag.TAG_ANY_NUMERIC)) return null;
        Vec3 point = new Vec3(tag.getDouble("fakeOperatorPositionX"),
                tag.getDouble("fakeOperatorPositionY"), tag.getDouble("fakeOperatorPositionZ"));
        return finite(point) ? point : null;
    }

    /** 记录必须属于当前乘员，不能拿上一位操作员的放飞点作为兜底。 */
    static OperatorAnchor readOperatorAnchor(CompoundTag tag, UUID operatorId) {
        if (!tag.hasUUID("operator") || !operatorId.equals(tag.getUUID("operator"))
                || !tag.hasUUID("dummy") || !tag.contains("x", Tag.TAG_ANY_NUMERIC)
                || !tag.contains("y", Tag.TAG_ANY_NUMERIC) || !tag.contains("z", Tag.TAG_ANY_NUMERIC)) return null;
        ResourceLocation dimension = ResourceLocation.tryParse(tag.getString("dimension"));
        Vec3 point = new Vec3(tag.getDouble("x"), tag.getDouble("y"), tag.getDouble("z"));
        float yaw = tag.getFloat("yaw"), pitch = tag.getFloat("pitch");
        return dimension != null && finite(point) && Float.isFinite(yaw) && Float.isFinite(pitch)
                ? new OperatorAnchor(dimension, point, yaw, pitch, tag.getUUID("dummy")) : null;
    }

    /** 起爆前构造快照；即使 stopRiding 清掉本体字段，回传动作也不再依赖这些字段。 */
    public static BooleanSupplier snapshotReturn(ServerLevel blastLevel, AbstractVehicle vehicle,
                                                  Vec3 blastCenter, float blastRadius) {
        List<ServerPlayer> players = new ArrayList<>();
        collectPlayers(vehicle, players);
        if (players.isEmpty()) return () -> true;
        CompoundTag saved = new CompoundTag();
        // 调用本体公开保存方法，只向新建的内存 CompoundTag 写快照，不重载或改写载具。
        vehicle.addAdditionalSaveData(saved);
        Vec3 nativePoint = readNativeReturnPoint(saved);
        CompoundTag operators = vehicle.getPersistentData().getCompound(OPERATORS_TAG);
        List<ReturnStep> steps = new ArrayList<>();
        for (ServerPlayer player : players) {
            OperatorAnchor anchor = readOperatorAnchor(operators.getCompound(player.getUUID().toString()), player.getUUID());
            // 本体原有坐标字段没有维度；既有实例沿用其同维返回语义，新记录则使用明确的放飞维度。
            ServerLevel targetLevel = anchor == null ? blastLevel : findLevel(blastLevel.getServer(), anchor.dimension());
            Vec3 point = anchor == null ? nativePoint : anchor.position();
            float yaw = anchor == null ? player.getYRot() : anchor.yaw();
            float pitch = anchor == null ? player.getXRot() : anchor.pitch();
            if (anchor != null && targetLevel != null
                    && targetLevel.getEntity(anchor.dummyId()) instanceof FakePlayer dummy && dummy.isAlive()) {
                // 假操作员可能自然下落；优先快照真实假玩家当前的位置，而不是悬空的初始点。
                point = dummy.position();
                yaw = dummy.getYRot();
                pitch = dummy.getXRot();
            }
            Vec3 destination = point;
            float targetYaw = yaw, targetPitch = pitch;
            steps.add(new ReturnStep(
                    () -> !player.isRemoved() && player.isAlive() && targetLevel != null && finite(destination)
                            && (targetLevel != blastLevel || outsideBlast(destination, blastCenter, blastRadius)),
                    player::stopRiding,
                    () -> !player.isPassenger(),
                    () -> {
                        if (player.isRemoved() || !player.isAlive()) return false;
                        // 调用服务端传送 API：建立传送同步/确认状态，并给返回区块增加临时加载票。
                        boolean teleported = player.teleportTo(targetLevel, destination.x, destination.y, destination.z,
                                Set.of(), targetYaw, targetPitch);
                        if (teleported) {
                            player.setDeltaMovement(Vec3.ZERO);
                            player.fallDistance = 0;
                        }
                        return teleported;
                    },
                    () -> !player.isRemoved() && player.isAlive() && !player.isPassenger()
                            && player.level() == targetLevel && player.position().distanceToSqr(destination) <= 0.25
                            && (player.level() != blastLevel || outsideBlast(player.position(), blastCenter, blastRadius))));
        }
        return () -> executeSteps(steps);
    }

    /** 全部离机回调返回后才允许传送；传送返回 true 仍需最终回读位置。 */
    static boolean executeSteps(List<ReturnStep> steps) {
        if (steps.stream().anyMatch(step -> !step.ready().getAsBoolean())) return false;
        steps.forEach(step -> step.dismount().run());
        if (steps.stream().anyMatch(step -> !step.dismounted().getAsBoolean())) return false;
        for (ReturnStep step : steps) if (!step.teleport().getAsBoolean()) return false;
        return steps.stream().allMatch(step -> step.returned().getAsBoolean());
    }

    /** 与部署版采用同样的两格安全余量，返回点仍在杀伤区内时不准起爆。 */
    static boolean outsideBlast(Vec3 point, Vec3 center, float radius) {
        double safeRadius = (double) radius + 2.0;
        return finite(point) && finite(center) && Float.isFinite(radius) && radius >= 0
                && point.distanceToSqr(center) > safeRadius * safeRadius;
    }

    /**
     * 真人已返回后立即移除本车绑定的假操作员，防 FakePlayer.hurt 在本次爆炸里再次回传伤害/坐标。
     * 只按生成时绑定的 UUID 清理，不遍历/按名字删除其它载具的假玩家。
     */
    public static boolean discardReturnedOperators(ServerLevel level, AbstractVehicle vehicle) {
        CompoundTag operators = vehicle.getPersistentData().getCompound(OPERATORS_TAG);
        List<FakePlayer> dummies = new ArrayList<>();
        for (String key : operators.getAllKeys()) {
            CompoundTag tag = operators.getCompound(key);
            if (!tag.hasUUID("operator") || !tag.hasUUID("dummy")) continue;
            ServerPlayer player = level.getServer().getPlayerList().getPlayer(tag.getUUID("operator"));
            if (player != null && player.getRootVehicle() == vehicle) return false;
            ResourceLocation dimension = ResourceLocation.tryParse(tag.getString("dimension"));
            ServerLevel dummyLevel = dimension == null ? null : findLevel(level.getServer(), dimension);
            if (dummyLevel != null && dummyLevel.getEntity(tag.getUUID("dummy")) instanceof FakePlayer dummy) {
                dummies.add(dummy);
            }
        }
        // 调用实体生命周期移除；不是文件删除，也不触发 FakePlayer.hurt 的真人伤害转发。
        dummies.forEach(Entity::discard);
        vehicle.getPersistentData().remove(OPERATORS_TAG);
        return true;
    }

    /** 返回点必须完整且有限，不能把缺失 NBT 的默认零坐标当作放飞点。 */
    private static boolean finite(Vec3 point) {
        return point != null && Double.isFinite(point.x) && Double.isFinite(point.y) && Double.isFinite(point.z);
    }

    /** 通过服务端维度注册表解析记录，绝不猜测当前维度为放飞维度。 */
    private static ServerLevel findLevel(MinecraftServer server, ResourceLocation dimension) {
        return server.getLevel(ResourceKey.create(Registries.DIMENSION, dimension));
    }

    /** 覆盖座位实体等间接乘坐关系，不能只保护直接乘客。 */
    private static void collectPlayers(Entity vehicle, List<ServerPlayer> players) {
        for (Entity passenger : vehicle.getPassengers()) {
            if (passenger instanceof ServerPlayer player) players.add(player);
            collectPlayers(passenger, players);
        }
    }
}
