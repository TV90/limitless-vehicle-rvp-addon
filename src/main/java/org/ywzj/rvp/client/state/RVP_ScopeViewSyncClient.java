package org.ywzj.rvp.client.state;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.ywzj.rvp.RVP_MOD;
import org.ywzj.rvp.network.C2SScopeViewSync;
import org.ywzj.rvp.network.RVP_Network;
import org.ywzj.rvp.util.RVP_CcipUtil;
import org.ywzj.rvp.util.RVP_WeaponResolveHelper;
import org.ywzj.rvp.weapon.core.RVP_AimContexts;
import org.ywzj.vehicle.custom.part.data.WeaponUnitData;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;
import org.ywzj.vehicle.util.VectorUtil;
import org.ywzj.vehicle.vehicle.LocalVehiclePlayer;
import org.ywzj.vehicle.vehicle.part.PartUnit;
import org.ywzj.vehicle.vehicle.part.WeaponUnit;
import org.ywzj.vehicle.vehicle.weapon.AbstractVehicleWeapon;

import javax.annotation.Nullable;
import java.util.HashMap;
import java.util.Map;

/**
 * 客户端观瞄视角状态差分上行：进入/退出观瞄或换操作站时发送
 * {@link C2SScopeViewSync}，观瞄中每 30t 心跳续期（服务端条目 90t 过期）。
 *
 * <p>只同步状态、不随每发弹上行：实际出弹点覆盖在服务端出弹器统一执行，
 * 改装弹种代理上传链路与点射后续弹天然覆盖。座舱（OPERATOR）/第三人称不发送。</p>
 */
@Mod.EventBusSubscriber(value = Dist.CLIENT, modid = RVP_MOD.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class RVP_ScopeViewSyncClient {

    /** 观瞄中心跳续期间隔（tick）。 */
    private static final int HEARTBEAT_TICKS = 30;

    /** 观瞄准星 RVP 弹道覆写的上一帧值（键=vehicleId*100000+站序号），供本体准星 lerp 帧间插值。 */
    private static final Map<Long, Vec3> LAST_CROSSHAIR_HIT = new HashMap<>();
    /** 当前正在覆写的站键；条件失效时据此清表，交还本体原逻辑。 */
    private static long lastAdaptedKey = Long.MIN_VALUE;

    private static boolean lastInScope;
    private static int lastVehicleId = -1;
    private static int lastPartUnitIndex = -1;
    private static long nextHeartbeatGameTime;

    private RVP_ScopeViewSyncClient() {
    }

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        Level level = mc.level;
        if (player == null || level == null) {
            reset();
            return;
        }
        boolean inScope = LocalVehiclePlayer.instance != null
                && LocalVehiclePlayer.instance.viewType == LocalVehiclePlayer.ViewType.SCOPE;
        int vehicleId = -1;
        int partUnitIndex = -1;
        if (inScope && player.getVehicle() instanceof AbstractVehicle vehicle) {
            PartUnit<?> operator = vehicle.getOwnOperatorUnit(player);
            WeaponUnit root = operator instanceof WeaponUnit weaponUnit
                    ? weaponUnit.getRootParentWeaponUnit()
                    : null;
            int index = root == null ? -1 : stationIndexOf(vehicle, root);
            if (index < 0) {
                // 座位不在武器站上（驾驶员等）：观瞄状态无出弹覆盖语义
                inScope = false;
            } else {
                vehicleId = vehicle.getId();
                partUnitIndex = index;
            }
        }
        boolean changed = inScope != lastInScope
                || vehicleId != lastVehicleId
                || partUnitIndex != lastPartUnitIndex;
        long gameTime = level.getGameTime();
        if (changed || (inScope && gameTime >= nextHeartbeatGameTime)) {
            RVP_Network.CHANNEL.sendToServer(new C2SScopeViewSync(vehicleId, partUnitIndex, inScope));
            lastInScope = inScope;
            lastVehicleId = vehicleId;
            lastPartUnitIndex = partUnitIndex;
            nextHeartbeatGameTime = gameTime + HEARTBEAT_TICKS;
        }
        adaptScopeCrosshair(player, level, inScope, vehicleId, partUnitIndex);
    }

    /**
     * 观瞄准星 RVP 弹道适配（无条件生效，不要求 rvp_sight_fire_disguise 配置）：本体 SCOPE 准星画在
     * {@code weaponHitPos} 的屏幕投影上，而本体对 RVP 武器只会做无下坠直线射线（CCIP 分支 instanceof
     * 只认本体武器类）。此处按 RVP 真实弹积分器 march 出带下坠/阻力的实际弹着点并覆写该公共字段。
     *
     * <p>march 原点跟随实际出弹点：站配置了 {@code rvp_sight_fire_disguise} 且距离帽内 = 观瞄相机
     * （与服务端出弹覆盖同源）；否则 = 炮口（无覆盖时实际弹的出弹点）。两种情况准星都与实际弹道
     * 同源同物理。</p>
     *
     * <p>平滑：上一帧覆写值存表，O 帧=上一帧值、当前帧=新值，恢复本体准星 lerp(posO→pos) 的
     * 帧间插值（覆写同值会逐 tick 硬跳，2026-09-19 用户实测反馈后修复）；本体每 tick 重算该字段，
     * 故持续覆写；退出观瞄/条件失效清表，下一 tick 起回本体原值自然过渡。</p>
     */
    private static void adaptScopeCrosshair(LocalPlayer player, Level level, boolean inScope,
                                            int vehicleId, int partUnitIndex) {
        WeaponUnit station = null;
        AbstractVehicle vehicle = null;
        if (inScope && partUnitIndex >= 0
                && player.getVehicle() instanceof AbstractVehicle v
                && v.getId() == vehicleId
                && v.getPartUnits().get(partUnitIndex) instanceof WeaponUnit s) {
            station = s;
            vehicle = v;
        }
        org.ywzj.rvp.weapon.data.RVP_WeaponData weaponData = station == null
                ? null : resolveStationWeaponData(station);
        if (station == null || weaponData == null || !marchable(weaponData.getWeaponKind())) {
            // 条件失效：清上一帧覆写表，本体原逻辑自然接管（准星与实际出弹点一致——无覆盖时同为炮口）
            if (lastAdaptedKey != Long.MIN_VALUE) {
                LAST_CROSSHAIR_HIT.remove(lastAdaptedKey);
                lastAdaptedKey = Long.MIN_VALUE;
            }
            return;
        }
        Vec3 dir = VectorUtil.rotToVec(station.aimContext().direction.x, station.aimContext().direction.y).normalize();
        Vec3 origin = resolveDisguiseOrigin(station, vehicle);
        Vec3 hit = RVP_CcipUtil.computeBulletImpact(level, origin, dir, weaponData, vehicle);
        if (hit == null) {
            // 视距内无方块/实体命中（对天空等）：准星居中——沿瞄准方向的远点投影即屏幕中心，
            // 不再标记数千格外地平线下的弹道终点（2026-09-19 用户反馈天空垂落后修复）
            hit = origin.add(dir.scale(512.0D));
        }
        long key = vehicleId * 100_000L + partUnitIndex;
        Vec3 prev = LAST_CROSSHAIR_HIT.get(key);
        station.weaponHitPosO = prev != null ? prev : hit;
        station.weaponHitPos = hit;
        LAST_CROSSHAIR_HIT.put(key, hit);
        lastAdaptedKey = key;
    }

    /** 观瞄准星 march 只覆盖 RVP 投射类弹种；LASER/TARGETING_POD/DISPENSER 走本体原逻辑。 */
    private static boolean marchable(org.ywzj.rvp.weapon.data.RVP_EnumWeaponKind kind) {
        return kind == org.ywzj.rvp.weapon.data.RVP_EnumWeaponKind.MACHINEGUN
                || kind == org.ywzj.rvp.weapon.data.RVP_EnumWeaponKind.MISSILE
                || kind == org.ywzj.rvp.weapon.data.RVP_EnumWeaponKind.ROCKET
                || kind == org.ywzj.rvp.weapon.data.RVP_EnumWeaponKind.BOMB;
    }

    /**
     * march 原点跟随实际出弹点：站配置 {@code rvp_sight_fire_disguise} 且观瞄相机在距离帽内 =
     * 观瞄相机（与服务端出弹覆盖同源，OPERATOR 型观瞄用操作员视点）；否则 = 炮口（无覆盖时实际弹
     * 的出弹点，与客户端上传的 aim.from 同源）。
     */
    private static Vec3 resolveDisguiseOrigin(WeaponUnit station, AbstractVehicle vehicle) {
        if (station.getData() instanceof org.ywzj.rvp.ext.WeaponUnitDataExt ext
                && ext.ywzj_rvp$getSightFireDisguise() != null) {
            org.ywzj.rvp.config.RVP_SightFireDisguiseConfig config = ext.ywzj_rvp$getSightFireDisguise();
            Vec3 sight = station.getOpticalSightType() == WeaponUnitData.OpticalSightType.OPERATOR
                    ? station.worldOwnerViewPosition(1.0f)
                    : station.worldOpticalSightPosition(1.0f);
            if (sight.distanceTo(vehicle.position()) <= config.maxDistance()) {
                return sight;
            }
        }
        return RVP_AimContexts.muzzle(station.aimContext());
    }

    /** 解析武器站当前有效武器的 RVP 数据：改装弹种代理（Multi/Agent 包装）由 unwrap 循环解包（null 安全）。 */
    @Nullable
    private static org.ywzj.rvp.weapon.data.RVP_WeaponData resolveStationWeaponData(WeaponUnit station) {
        AbstractVehicleWeapon<?> weapon = RVP_WeaponResolveHelper.unwrap(station.getCurrentWeapon().orElse(null));
        return weapon != null && weapon.getData() instanceof org.ywzj.rvp.weapon.data.RVP_WeaponData data
                ? data : null;
    }

    private static void reset() {
        lastInScope = false;
        lastVehicleId = -1;
        lastPartUnitIndex = -1;
        nextHeartbeatGameTime = 0;
    }

    /** 求根武器站在 getPartUnits() 中的序号（与 RVP_ClientLaserState.LaserBeamKey 同一套站序语义）。 */
    private static int stationIndexOf(AbstractVehicle vehicle, WeaponUnit root) {
        for (int i = 0; i < vehicle.getPartUnits().size(); i++) {
            if (vehicle.getPartUnits().get(i) == root) {
                return i;
            }
        }
        return -1;
    }
}
