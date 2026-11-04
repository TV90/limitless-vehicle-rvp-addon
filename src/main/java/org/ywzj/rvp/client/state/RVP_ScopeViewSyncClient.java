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
        // 采样之前校正受损炮塔，避免依赖同优先级事件注册顺序而缓存稳定器补偿后的旧方向。
        org.ywzj.rvp.event.RVP_TurretTraverseHandler.constrain(station);
        var aim = station.aimContext();
        Vec3 dir = VectorUtil.rotToVec(aim.direction.x, aim.direction.y).normalize();
        Vec3 origin = resolveDisguiseOrigin(station, vehicle);
        Vec3 hit = RVP_CcipUtil.computeBulletImpact(level, origin, dir, weaponData, vehicle);
        if (hit == null) {
            // 视距内无方块/实体命中（对天空等）：准星居中——沿瞄准方向的远点投影即屏幕中心，
            // 不再标记数千格外地平线下的弹道终点（2026-09-19 用户反馈天空垂落后修复）
            hit = origin.add(dir.scale(512.0D));
        }
        long key = vehicleId * 100_000L + partUnitIndex;
        Vec3 prev = LAST_CROSSHAIR_HIT.get(key);
        // 静止冻结：命中点与上一 tick 的差小于 0.03 格时沿用旧值。march 输入（炮塔角/相机位置）
        // 在静止时仍有亚像素级微变，经数百格射线放大后会让准星逐 tick 跳动（"静止也爆抖"）。
        if (prev != null && prev.distanceToSqr(hit) < 9.0E-4) {
            hit = prev;
        }
        station.weaponHitPosO = prev != null ? prev : hit;
        station.weaponHitPos = hit;
        LAST_CROSSHAIR_HIT.put(key, hit);
        lastAdaptedKey = key;
    }

    /**
     * 观瞄准星 march 范围（2026-09-19 用户定版）：仅 RVP 机枪/机炮类（MACHINEGUN，含下坠明显的
     * 榴弹如 3of26 与高初速 APFSDS）；火箭/导弹/炸弹暂不适配——这些弹的飞行模型含发动机推进段，
     * 弹道 march 预测不准，且本体行为对其够用，回退本体直线射线原逻辑。
     */
    private static boolean marchable(org.ywzj.rvp.weapon.data.RVP_EnumWeaponKind kind) {
        return kind == org.ywzj.rvp.weapon.data.RVP_EnumWeaponKind.MACHINEGUN;
    }

    /**
     * march 原点跟随实际出弹点：站配置 {@code rvp_sight_fire_disguise} 且观瞄相机在距离帽内 =
     * 观瞄相机（与服务端出弹覆盖同源，OPERATOR 型观瞄用操作员视点）；否则 = 炮口（无覆盖时实际弹
     * 的出弹点，与客户端上传的 aim.from 同源）。
     *
     * <p><b>2026-10-24 教训</b>：曾把这里改成恒返回炮口，以为「准星要跟炮管」——那等于废掉
     * disguise 的「近距离指哪打哪」语义（disguise 存在的理由就是让弹从相机出、消除炮口视差）。
     * 正确做法是反向适配：让<b>本体新增的瞄准圈</b>也改用相机指向（见
     * {@code VehicleAimAtOverlaySeekerColorMixin#rvp$disguiseAimPosition}），两边同源。</p>
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

    /**
     * 观瞄「相机出弹」口径是否生效：观瞄视角 + 本武器站配置了 {@code rvp_sight_fire_disguise}
     * + 观瞄相机在距离帽内（与服务端 {@code resolveSightFireDisguise} 同条件）。
     *
     * <p>供本体新增瞄准圈（{@code VehicleAimAtOverlay.aimScreenPosition}）适配用：命中时
     * 圈的位置改用相机口径的世界点，从而与 RVP 准星（{@link #resolveDisguiseOrigin} 同源）重合。</p>
     */
    public static boolean disguiseAimActive(WeaponUnit station) {
        if (station == null || LocalVehiclePlayer.instance == null) {
            return false;
        }
        if (LocalVehiclePlayer.instance.viewType != LocalVehiclePlayer.ViewType.SCOPE) {
            return false;
        }
        AbstractVehicle vehicle = LocalVehiclePlayer.instance.vehicle;
        if (vehicle == null) {
            return false;
        }
        if (!(station.getData() instanceof org.ywzj.rvp.ext.WeaponUnitDataExt ext)
                || ext.ywzj_rvp$getSightFireDisguise() == null) {
            return false;
        }
        // [RVP] 2026-10-30：观瞄视角射弹只对 MACHINEGUN 生效（服务端出弹覆盖同条件，见
        // RVP_ProjectileSpawner:106 的 kind 判断）——炮射导弹（rvp:missile）本身不走相机出弹，
        // 意图圈必须保持本体原行为（炮口口径），否则导弹的圈会被错误搬到观瞄相机上。
        org.ywzj.rvp.weapon.data.RVP_WeaponData currentWeaponData = resolveStationWeaponData(station);
        if (currentWeaponData == null || !marchable(currentWeaponData.getWeaponKind())) {
            return false;
        }
        org.ywzj.rvp.config.RVP_SightFireDisguiseConfig config = ext.ywzj_rvp$getSightFireDisguise();
        Vec3 sight = station.getOpticalSightType() == WeaponUnitData.OpticalSightType.OPERATOR
                ? station.worldOwnerViewPosition(1.0f)
                : station.worldOpticalSightPosition(1.0f);
        return sight.distanceTo(vehicle.position()) <= config.maxDistance();
    }

    /**
     * 意图方向的帧间插值缓存，按 WeaponUnit 实例分键；布局 {@code Vec3[2]}：
     * {@code [0]} = 上一 tick 的世界方向，{@code [1]} = 当前 tick 的世界方向。
     *
     * <p><b>为什么必须缓存世界方向、不能缓存局部目标角（2026-10-24 定位）</b>：本体
     * {@code WeaponUnit.tickRot()} 的双向稳定器分支在车体转动时，每 tick 会把
     * {@code xAimRot/yAimRot} 改写为「保持同一个世界目标方向所需的局部角」——车体偏航 Δ，
     * 局部目标角就反向变化约 Δ。若在局部角空间做 rotLerp、再乘车体姿态的帧间增量，两个
     * 插值分量在 tick 内不是同一条路径：圈每 tick 被车体偏航量反向拉一次、tick 末尾再弹回，
     * 即用户实测的「车体左右转时高频小幅左右横跳」；静止时 Δ=0，所以看不出来。
     * 世界方向两端在 tick 边界严格连续，插值路径与相机（本体 CameraMixin 对世界朝向做
     * slerp）一致，转车体/转炮塔都不会再出现台阶。</p>
     */
    private static final Map<WeaponUnit, Vec3[]> INTENT_AIM_DIR = new java.util.IdentityHashMap<>();

    private static WeaponUnit lastIntentStation;

    /**
     * 「意图层」瞄准世界点 = 相机位置 + <b>炮塔目标角</b>方向（{@code xAimRot/yAimRot}）。
     *
     * <p>为什么不用视线：scope 视角下相机被 {@code cameraAimWith(weaponUnit)} 绑在炮塔上，
     * 「视线」与「炮口实际方向」近似同一个量，两者画出来永远同步转——用户反馈的"圈和准星
     * 一起转、看不出层次"正是这个原因。</p>
     *
     * <p>本体真正的两层是：{@code updateWorldAimVec()} = {@code worldVec(xAimRot, yAimRot)}
     * （<b>目标角</b>，鼠标指令立即到位）vs {@code worldVec()} = {@code worldVec(xRot, yRot)}
     * （<b>实际角</b>，经 {@code Mth.approach(..., y_rot_speed)} 限速）。本条取前者，RVP 准星
     * 取后者（{@code weaponHitPos} 走炮口实际姿态 + 弹道 march）：转向时圈先走、准星按方向机
     * 节奏追；炮塔到位后两者同向、落点重合。</p>
     *
     * <p>平滑：在<b>世界方向</b>空间按 partialTick 插值（缓存布局见 {@link #INTENT_AIM_DIR}）。
     * 不能在局部角空间插值——本体稳定器会在车体转动时逐 tick 反向改写
     * {@code xAimRot/yAimRot}，局部角插值与车体插值两条路径不同步，圈会被每 tick 反向拉一次
     * 再弹回（2026-10-24 用户实测的车体转动时高频横跳）。世界方向插值的两端在 tick 边界严格
     * 连续，且与相机（CameraMixin 对世界朝向做 slerp）同一路径；静止时方向不变，命中点天然
     * 稳定（不需要死区）。</p>
     */
    @Nullable
    public static Vec3 disguiseAimWorldPos(WeaponUnit station, float partialTick) {
        LocalVehiclePlayer local = LocalVehiclePlayer.instance;
        if (station == null || local == null) {
            return station == null ? null : station.weaponHitPos;
        }
        org.ywzj.rvp.mixin.RotatableUnitAimRotAccessor accessor =
                (org.ywzj.rvp.mixin.RotatableUnitAimRotAccessor) (Object) station;
        // 意图世界方向：本体稳定器已把 xAimRot/yAimRot 改写为「世界目标的局部角」，
        // 这里按本体 worldVec() 的同一套链路（车体姿态 × 结构基准 × 目标角）还原成世界方向。
        // 叠加炮管自身旋转（xBarrelSelfRot/yBarrelSelfRot，本体 field、与相机口径同向）：
        // 与 cameraAimWith() 的观瞄相机方向保持同一参考系（99B 的 turret_barrel 带 2.4° 上仰）。
        Vec3 curDir = station.worldVec(
                accessor.ywzj_rvp$getXAimRot() + station.xBarrelSelfRot,
                accessor.ywzj_rvp$getYAimRot() + station.yBarrelSelfRot).normalize();
        Vec3[] dirs = INTENT_AIM_DIR.get(station);
        if (dirs == null) {
            dirs = new Vec3[]{curDir, curDir};
            INTENT_AIM_DIR.put(station, dirs);
        } else if (dirs[1].distanceToSqr(curDir) > 1.0E-10) {
            // 只在「世界方向真的变了」时滚动：同一 tick 内多次渲染必须拿到同一对 (prev, cur)，
            // 否则第 2 帧起 prev 被污染，插值退化成逐 tick 跳变。
            dirs[0] = dirs[1];
            dirs[1] = curDir;
        }
        // 世界空间插值：两端方向转成 (pitch, yaw)，yaw 用 rotLerp 处理 ±180 环绕。
        // 插值路径与相机一致（CameraMixin 对世界朝向做 slerp），不再受车体/炮塔在局部角
        // 空间分解方式的影响：车体转动时稳定器反向改写局部目标角不会再把圈拉出台阶。
        net.minecraft.world.phys.Vec2 fromRot = VectorUtil.vecToRot(dirs[0]);
        net.minecraft.world.phys.Vec2 toRot = VectorUtil.vecToRot(dirs[1]);
        float aimX = net.minecraft.util.Mth.lerp(partialTick, fromRot.x, toRot.x);
        float aimY = net.minecraft.util.Mth.rotLerp(partialTick, fromRot.y, toRot.y);
        Vec3 dir = VectorUtil.rotToVec(aimX, aimY).normalize();
        AbstractVehicle vehicle = local.vehicle;
        net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
        if (mc.gameRenderer == null || mc.level == null) {
            return station.weaponHitPos;
        }
        Vec3 origin = mc.gameRenderer.getMainCamera().getPosition();
        // 意图圈必须与准星走同一套弹道积分（下坠/阻力），否则用下坠大的弹种时，
        // 「目标角直线射线」会打在远处地平线，而准星按真实弹道落在近处，两圈分家
        // （2026-10-24 用户用 30K18M 破甲弹实测）。差别只应来自方向本身（目标角 vs 实际角）。
        org.ywzj.rvp.weapon.data.RVP_WeaponData weaponData = resolveStationWeaponData(station);
        if (weaponData != null && marchable(weaponData.getWeaponKind())) {
            Vec3 marchHit = RVP_CcipUtil.computeBulletImpact(mc.level, origin, dir, weaponData, vehicle);
            if (marchHit != null) {
                return marchHit;
            }
            return origin.add(dir.scale(512.0D));
        }
        Vec3 end = origin.add(dir.scale(512.0D));
        net.minecraft.world.entity.player.Player player = local.getPlayer();
        if (player == null) {
            return end;
        }
        return org.ywzj.vehicle.util.VectorUtil.hitPosition(player, origin, end);
    }

    /** 解析武器站当前有效武器的 RVP 数据：改装弹种代理（Multi/Agent 包装）由 unwrap 循环解包（null 安全）。 */    @Nullable
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
