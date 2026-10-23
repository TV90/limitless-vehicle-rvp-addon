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
        org.ywzj.rvp.config.RVP_SightFireDisguiseConfig config = ext.ywzj_rvp$getSightFireDisguise();
        Vec3 sight = station.getOpticalSightType() == WeaponUnitData.OpticalSightType.OPERATOR
                ? station.worldOwnerViewPosition(1.0f)
                : station.worldOpticalSightPosition(1.0f);
        return sight.distanceTo(vehicle.position()) <= config.maxDistance();
    }

    /**
     * 炮塔目标角的帧间插值缓存，按 WeaponUnit 实例分键。
     *
     * <p>布局 {@code float[4]}：{@code [0..1]} = 上一 tick 值，{@code [2..3]} = 当前 tick 值。
     * 滚动条件必须是「当前值真的变了」而不是「每次渲染」，原因见 {@link #disguiseAimWorldPos}。</p>
     */
    private static final Map<WeaponUnit, float[]> INTENT_AIM_ROT = new java.util.IdentityHashMap<>();

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
     * <p>平滑：目标角每 tick 才更新，按 {@code Mth.rotLerp(partialTick)} 在上一 tick 与当前 tick
     * 之间插值，消除 20Hz 台阶；静止时目标角不变，命中点天然稳定（不再需要死区）。</p>
     */
    @Nullable
    public static Vec3 disguiseAimWorldPos(WeaponUnit station, float partialTick) {
        LocalVehiclePlayer local = LocalVehiclePlayer.instance;
        if (station == null || local == null) {
            return station == null ? null : station.weaponHitPos;
        }
        org.ywzj.rvp.mixin.RotatableUnitAimRotAccessor accessor =
                (org.ywzj.rvp.mixin.RotatableUnitAimRotAccessor) (Object) station;
        // 必须叠加炮管自身旋转（WeaponUnit.xBarrelSelfRot / yBarrelSelfRot，本体维护、与
        // xAimRot 同单位同向）：炮塔角只是炮塔朝向，结构模型里炮管可能还带固定旋转
        // ——99B 的 turret_barrel 就带 2.4° 上仰用来抵消炮塔前倾。不加这一项，圈的落点会
        // 比真实炮口指向低一截（2026-10-24 用户实测）。
        //
        // 【帧间插值缓存的滚动条件】——2026-10-24 用户实测「高频小幅度左右横跳」的根因。
        // xAimRot/yAimRot 是 tick 级字段，一 tick 内通常有 2~3 帧渲染。若在渲染路径里直接覆写
        // 「上一 tick」缓存，同一 tick 的第 2 帧起 prev 就被污染成当前值，rotLerp 退化成「直接取
        // 当前值」，于是每个 tick 边界多出一次跳变：第 1 帧还在旧位置、第 2 帧起跳到新位置，
        // 远距落点把这个 tick 级差放大成肉眼可见的横跳。
        // 本体（toAimPosition -> aimGlobalTransform + vehicle.rotYXZ）压根不做 partialTick
        // 插值、一 tick 内值恒定，所以反而没有这种台阶——差异不在本体，在我自己加的这层。
        // 故滚动判据改成「当前值是否真的变了」：只有进入新 tick 才把旧 cur 移成 prev，
        // 同一 tick 内任意次渲染都拿到同一对 (prev, cur)，逐帧结果一致。
        if (lastIntentStation != station) {
            INTENT_AIM_ROT.clear();
            lastIntentStation = station;
        }
        float curX = accessor.ywzj_rvp$getXAimRot() + station.xBarrelSelfRot;
        float curY = accessor.ywzj_rvp$getYAimRot() + station.yBarrelSelfRot;
        float[] rot = INTENT_AIM_ROT.get(station);
        if (rot == null) {
            rot = new float[]{curX, curY, curX, curY};
            INTENT_AIM_ROT.put(station, rot);
        } else if (curX != rot[2] || curY != rot[3]) {
            rot[0] = rot[2];
            rot[1] = rot[3];
            rot[2] = curX;
            rot[3] = curY;
        }
        float aimX = net.minecraft.util.Mth.rotLerp(partialTick, rot[0], rot[2]);
        float aimY = net.minecraft.util.Mth.rotLerp(partialTick, rot[1], rot[3]);
        AbstractVehicle vehicle = local.vehicle;
        Vec3 dir = station.worldVec(aimX, aimY);
        // 车体姿态帧间补偿（2026-10-24 用户实测：车体左转/右转时意图圈左右剧烈晃动，静止时不晃）。
        //
        // 根因：worldVec(FF) 内部走 baseRot() → AbstractVehicle.rotYXZ()，后者用车体 tick 级字段
        // yRot/xRot/zRot 构造四元数，**不带 partialTick 插值**。车体转动时该值每 tick 跳一次，而渲染
        // 发生在 tick 之间，圈就跟着 tick 台阶左右跳；静止时 yRot 不变，所以看不出问题。
        //
        // 修正：dirFrame = (rotYXZ(pt) × rotYXZ()⁻¹) × dirTick。
        // baseRot() = rotYXZ() × group.baseRotation（× 父链），其中 group.baseRotation 是结构模型的
        // **静态**量，在这个 delta 里会被约掉，因此该式与「用插值车体重算一遍 worldVec」严格等价，
        // 不需要访问 protected 的 structureGroup。
        //
        // 与上面的 Mth.rotLerp(目标角) 是互补而非重复：后者补「局部目标角」的帧间差，这里补
        // 「基准姿态」的帧间差，两者合起来才是完整的帧间插值。
        if (vehicle != null && partialTick > 0.0F && partialTick < 1.0F) {
            org.joml.Quaternionf tickRot = vehicle.rotYXZ();
            org.joml.Quaternionf frameRot = vehicle.rotYXZ(partialTick);
            if (tickRot != null && frameRot != null) {
                org.joml.Quaternionf delta =
                        new org.joml.Quaternionf(frameRot).mul(new org.joml.Quaternionf(tickRot).conjugate());
                dir = new Vec3(delta.transform(dir.toVector3f(), new org.joml.Vector3f()));
            }
        }
        dir = dir.normalize();
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
