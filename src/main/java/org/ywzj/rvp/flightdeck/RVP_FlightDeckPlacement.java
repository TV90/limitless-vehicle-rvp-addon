package org.ywzj.rvp.flightdeck;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.joml.Vector3f;
import org.ywzj.rvp.RVP_MOD;
import org.ywzj.vehicle.YwzjVehicle;
import org.ywzj.vehicle.custom.CommonAssetsManager;
import org.ywzj.vehicle.custom.vehicle.BaseVehicleData;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;
import org.ywzj.vehicle.item.VehicleSpawnItem;
import org.ywzj.vehicle.vehicle.structure.OBB;

import java.util.List;
import java.util.Optional;

import static org.ywzj.vehicle.api.entity.ICustomVehicle.TAG_VEHICLE_ID;

/**
 * 允许用载具生成物品右键把载具放到飞行甲板上（2026-10-31）。
 *
 * <p><b>为什么原版放不上去</b>：{@code VehicleSpawnItem.use} 的两道门都会把甲板挡掉——</p>
 * <ol>
 *   <li>先用 {@code getPlayerPOVHitResult}（只打方块）取落点：射线穿过母舰，落到甲板下方几格甚至
 *       几十格外的海床/空气上，落点压根不在甲板上；</li>
 *   <li>再调 {@code VectorUtil.hitEntity} 查遮挡：射线必然先撞到母舰本体，命中实体就直接 {@code pass}。</li>
 * </ol>
 * <p>所以站在航母上右键只会"手臂挥一下、什么都不发生"。</p>
 *
 * <p><b>这里的做法</b>：不做方块射线，直接拿甲板骨骼的 OBB 做射线求交（和停靠判定同一套
 * {@link RVP_FlightDeckManager#deckOBBs}），命中朝上的顶面才算数；同时仍然保留"别的载具挡在前面
 * 就不许放"的语义，避免把飞机放进停着的飞机里。</p>
 *
 * <p><b>为什么不用 mixin</b>：Forge 在 {@code ItemStack#use} 开头就抛
 * {@link PlayerInteractEvent.RightClickItem}，取消即跳过物品原生的 {@code use}
 * （{@code ForgeHooks.onItemRightClick(player, hand, stack)}），因此纯事件就能完全接管，
 * 不必给 {@code VehicleSpawnItem} 打补丁。</p>
 */
@Mod.EventBusSubscriber(modid = RVP_MOD.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class RVP_FlightDeckPlacement {

    /** 放置射线长度（格）。比原版方块交互距离略宽，创造/生存都够用。 */
    private static final double PLACE_REACH = 5.0D;

    /** 精确遮挡判定的余量：甲板面本身不能把自己的命中点判成遮挡。 */
    private static final double OCCLUSION_MARGIN = 0.05D;

    /** 只认朝上的面（与停靠判定 {@code isOnDeck} 同一口径）。 */
    private static final double TOP_NORMAL_Y = 0.25D;

    /** 落点沿 +Y 抬一点，避免和平坦甲板共面导致的支撑判定抖动。 */
    private static final double SPAWN_LIFT = 0.02D;

    private RVP_FlightDeckPlacement() {}

    /** 甲板命中结果。 */
    public record DeckHit(AbstractVehicle ship, Vec3 point) {}

    @SubscribeEvent
    public static void onRightClickItem(PlayerInteractEvent.RightClickItem event) {
        // 只在服务端真正生成；客户端保持原版路径（原版客户端 use 只挥手臂，不生成实体）
        if (event.getEntity() == null || event.getLevel().isClientSide()) {
            return;
        }
        Player player = event.getEntity();
        if (player.isSpectator()) {
            return;
        }
        ItemStack stack = event.getItemStack();
        if (!(stack.getItem() instanceof VehicleSpawnItem)) {
            return;
        }
        CompoundTag tag = stack.getTag();
        if (tag == null || !tag.contains(TAG_VEHICLE_ID)) {
            return;
        }
        DeckHit hit = pickDeck(event.getLevel(), player);
        if (hit == null) {
            return;
        }
        ResourceLocation vehicleId = YwzjVehicle.resourceLocation(tag.getString(TAG_VEHICLE_ID));
        Optional<BaseVehicleData> data = CommonAssetsManager.vehicleDataManager().getVehicleData(vehicleId);
        if (data.isEmpty()) {
            return;
        }
        // 朝向跟母舰艏向走：停到甲板上的载具本来就该顺着甲板摆，用玩家朝向经常横着卡在舷边
        AbstractVehicle spawned = data.get().construct(event.getLevel(),
                hit.point().add(0.0D, SPAWN_LIFT, 0.0D), 0.0F, hit.ship().getYRot());
        if (!event.getLevel().addFreshEntity(spawned)) {
            return;
        }
        stack.shrink(1);
        // 立刻登记停靠：否则新生载具会先走一 tick 物理，那一 tick 的相对速度足以触发上千点撞击伤害
        RVP_FlightDeckManager.dockNow(spawned);
        // 取消原版 use：原版射线打到母舰实体会直接 pass，这里已经处理完了
        event.setCancellationResult(InteractionResult.SUCCESS);
        event.setCanceled(true);
    }

    /**
     * 沿视线找最近的甲板顶面命中点。
     *
     * @return 未命中甲板、或被别的载具挡在前面时返回 null
     */
    private static DeckHit pickDeck(Level level, Player player) {
        Vec3 eye = player.getEyePosition();
        Vec3 end = eye.add(player.getViewVector(1.0F).scale(PLACE_REACH));
        AABB search = player.getBoundingBox().inflate(PLACE_REACH + 2.0D);
        Entity riding = player.getVehicle();
        List<AbstractVehicle> ships = level.getEntitiesOfClass(AbstractVehicle.class, search,
                ship -> ship != riding && RVP_FlightDeckManager.hasFlightDeck(ship));
        if (ships.isEmpty()) {
            return null;
        }
        Vector3f start = new Vector3f((float) eye.x, (float) eye.y, (float) eye.z);
        Vector3f stop = new Vector3f((float) end.x, (float) end.y, (float) end.z);
        AbstractVehicle bestShip = null;
        Vec3 bestPoint = null;
        double bestDist = Double.MAX_VALUE;
        for (AbstractVehicle ship : ships) {
            for (OBB obb : RVP_FlightDeckManager.deckCollisionOBBs(ship)) {
                Optional<Vector3f> clip = obb.clip(start, stop);
                if (clip.isEmpty()) {
                    continue;
                }
                Vec3 point = new Vec3(clip.get());
                int face = obb.embeddingFace(point);
                Vector3f axis = obb.getAxes()[Math.abs(face) - 1];
                double sign = face > 0 ? 1.0D : -1.0D;
                // 侧面/底面不算：从甲板下方、船体侧壁瞄过来不应该能放
                if (axis.y * sign <= TOP_NORMAL_Y) {
                    continue;
                }
                double dist = eye.distanceTo(point);
                if (dist < bestDist) {
                    bestDist = dist;
                    bestShip = ship;
                    bestPoint = point;
                }
            }
        }
        if (bestShip == null) {
            return null;
        }
        // 遮挡：先撞到别的载具（停着的飞机、另一艘船）就不许隔空放置
        AbstractVehicle target = bestShip;
        for (AbstractVehicle other : level.getEntitiesOfClass(AbstractVehicle.class, search,
                other -> other != target && other != riding)) {
            Optional<Vec3> clip = other.getBoundingBox().inflate(0.1D).clip(eye, end);
            if (clip.isPresent() && eye.distanceTo(clip.get()) < bestDist - OCCLUSION_MARGIN) {
                return null;
            }
        }
        return new DeckHit(bestShip, bestPoint);
    }
}
