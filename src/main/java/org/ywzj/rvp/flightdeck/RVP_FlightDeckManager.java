package org.ywzj.rvp.flightdeck;

import com.github.mcmodderanchor.simplebedrockmodel.v1.common.model.BedrockBone;
import com.github.mcmodderanchor.simplebedrockmodel.v1.common.model.BedrockModel;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.event.entity.EntityLeaveLevelEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.ywzj.rvp.RVP_MOD;
import org.ywzj.rvp.config.RVP_VehicleExtendedConfigManager;
import org.ywzj.rvp.config.RVP_VehicleExtendedConfigManager.VehicleExtendedConfig;
import org.ywzj.rvp.config.RVP_VehicleExtendedConfigManager.VehicleExtendedConfig.FlightDeckConfig;
import org.ywzj.vehicle.YwzjVehicle;
import org.ywzj.vehicle.api.event.VehicleMoveEvent;
import org.ywzj.vehicle.custom.CommonAssetsManager;
import org.ywzj.vehicle.custom.part.data.PartUnitData;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;
import org.ywzj.vehicle.entity.vehicle.FixedWingVehicle;
import org.ywzj.vehicle.vehicle.control.ControlUnit;
import org.ywzj.vehicle.vehicle.part.PartUnit;
import org.ywzj.vehicle.vehicle.structure.OBB;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 航母飞行甲板（{@code rvp_flight_deck}，2026-10-31）。
 *
 * <p>与 {@code RVP_HeliDockManager} 的着舰是两套东西：那套是锁定式（Phase + 控制锁 + 吸附舱位），
 * 这套是<b>纯物理耦合</b>——不锁控制、不吸附，只做三件事：</p>
 * <ol>
 *   <li>把母舰的线速度、角速度叠加到停在甲板上的载具（角速度按 ω × r 给切向速度）；</li>
 *   <li>水平速度向"母舰速度"收敛，收敛速率即甲板摩擦，观感接近方块；</li>
 *   <li>可选的相对位置回拉，避免转弯时整机滑出甲板。</li>
 * </ol>
 *
 * <p>玩家随时能起飞：与期望速度之差超过 {@code max_relative_speed} 即解除停靠，之后完全由玩家控制。
 * 之所以能直接改速度，是因为本体 {@code PhysicsEngine} 每 tick 开头都会从
 * {@code vehicle.getDeltaMovement()} 读回速度（{@code PhysicsEngine:102-104}），
 * 因此 {@code setDeltaMovement} 会被下一 tick 采纳，不需要碰物理内部状态。</p>
 */
@Mod.EventBusSubscriber(modid = RVP_MOD.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class RVP_FlightDeckManager {

    /** 已停靠载具 id → 停靠信息。 */
    private static final Map<Integer, Docking> DOCKED = new ConcurrentHashMap<>();

    /** 脚底允许高出甲板面的上限（格）。 */
    private static final double CONTACT_UP = 1.2D;
    /** 脚底允许低于甲板面的下限（格），给悬挂压缩留余量。 */
    private static final double CONTACT_DOWN = 0.9D;
    /** 搜寻停放载具的母舰搜索半径（格）。 */
    private static final double SCAN_RADIUS = 96.0D;

    /** 已经就"甲板 OBB 解析不出来"报过警告的载具 id（每台只报一次，避免刷屏）。 */
    private static final Set<ResourceLocation> DECK_WARNED = ConcurrentHashMap.newKeySet();

    /**
     * 各维度里"配了甲板"的母舰登记表（2026-11-01 性能修正）。
     *
     * <p>原来每次 {@code VehicleMoveEvent}（每台载具每 tick 都发）都用
     * {@code getEntitiesOfClass(AbstractVehicle, bbox.inflate(96))} 现搜——航母这种 200 多格的
     * 载具，搜索盒接近 400×200×400，等于每 tick 把所有相关区块段扫一遍，而且航母自己也会发事件、
     * 也扫一遍。改成实体加入/离开时登记，查询退化成对 0~2 条记录的直线距离判断。</p>
     */
    private static final Map<Level, Set<AbstractVehicle>> DECK_SHIPS =
            Collections.synchronizedMap(new WeakHashMap<>());

    /** 甲板 OBB 的每 tick 缓存（key = 母舰 UUID，position 变化即失效）。 */
    private static final Map<UUID, CachedDeck> DECK_OBB_CACHE = new ConcurrentHashMap<>();

    private RVP_FlightDeckManager() {}

    /**
     * 停靠记录。
     *
     * @param shipId  母舰实体 id
     * @param localX  停靠瞬间相对母舰的船体局部水平偏移（用于转弯回拉）
     * @param localZ  同上
     */
    public record Docking(int shipId, double localX, double localZ) {}

    /** 该载具当前是否停在某艘船的甲板上（撞击豁免与调试用）。 */
    public static boolean isDockedOnDeck(AbstractVehicle vehicle) {
        return vehicle != null && DOCKED.containsKey(vehicle.getId());
    }

    /** 该载具停靠的母舰 id；未停靠返回 -1。 */
    public static int dockedShipId(int vehicleId) {
        Docking docking = DOCKED.get(vehicleId);
        return docking == null ? -1 : docking.shipId();
    }

    /**
     * 甲板骨骼的世界系 OBB 列表。
     *
     * <p>走本体公共 API {@code OBB.getOBBsFromBone}（直升机着舰同款），按母舰实时位姿现算，
     * 因此倾斜、转向都能跟上；不需要 {@code vehiclePartGroups}（那是 protected）。</p>
     */
    public static List<OBB> deckOBBs(AbstractVehicle ship) {
        // 缓存命中前提：同一个 tick 内母舰位置没变。位置一变就重算，
        // 因此同一 tick 里几十次调用（每个踩在船上/附近的实体都会走 support()）只算一次。
        Vec3 position = ship.position();
        CachedDeck cached = DECK_OBB_CACHE.get(ship.getUUID());
        if (cached != null && cached.position().equals(position)) {
            return cached.obbs();
        }
        List<OBB> computed = computeDeckOBBs(ship);
        DECK_OBB_CACHE.put(ship.getUUID(), new CachedDeck(position, computed));
        return computed;
    }

    /** 甲板 OBB 缓存条目。 */
    private record CachedDeck(Vec3 position, List<OBB> obbs) {}

    /** 现算甲板 OBB（无缓存版本，缓存逻辑在 {@link #deckOBBs}）。 */
    private static List<OBB> computeDeckOBBs(AbstractVehicle ship) {
        FlightDeckConfig config = flightDeckOf(ship);
        if (config == null) {
            return List.of();
        }
        VehicleExtendedConfig extended = RVP_VehicleExtendedConfigManager.INSTANCE.get(ship);
        if (extended == null || extended.structureModel() == null) {
            warnOnce(ship, "structure_model 未解析（扩展配置缺失）");
            return List.of();
        }
        BedrockModel model = CommonAssetsManager.structureModelManager()
                .getStructureModel(extended.structureModel()).orElse(null);
        if (model == null) {
            warnOnce(ship, "结构模型未注册：" + extended.structureModel());
            return List.of();
        }
        BedrockBone bone = model.getBoneMap().get(config.bone());
        if (bone == null) {
            warnOnce(ship, "结构模型里没有骨骼 " + config.bone() + "，现有骨骼 " + model.getBoneMap().keySet());
            return List.of();
        }
        HashSet<BedrockBone> namedBones = new HashSet<>(model.getBoneMap().values());
        List<OBB.CubeOBB> cubes = OBB.getOBBsFromBone(bone, ship, namedBones);
        if (cubes.isEmpty()) {
            warnOnce(ship, "骨骼 " + config.bone() + " 没有 cube");
            return List.of();
        }
        List<OBB> result = new ArrayList<>(cubes.size());
        for (OBB.CubeOBB cube : cubes) {
            result.add(cube.obb());
        }
        return result;
    }

    /**
     * 甲板配置存在但解析不出 OBB 时警告一次（每台载具一个 id 只报一次）。
     *
     * <p>这种情况一定是配置/资源问题（模型没注册、骨骼名写错、骨骼没 cube），
     * 但表现是"甲板完全没有碰撞、直接穿过去"，不看日志根本查不出来，所以值得报一次。</p>
     */
    private static void warnOnce(AbstractVehicle ship, String reason) {
        var vehicleId = ship.getVehicleId();
        if (vehicleId == null || !DECK_WARNED.add(vehicleId)) {
            return;
        }
        YwzjVehicle.LOGGER.warn("[RVP] 飞行甲板 OBB 解析失败（{}）：{}", vehicleId, reason);
    }

    /** 甲板世界包围盒；<b>仅供调试/粗筛</b>，停靠判定不要用它（倾斜时会框住空气）。 */
    public static AABB deckBounds(AbstractVehicle ship) {
        List<OBB> obbs = deckOBBs(ship);
        return obbs.isEmpty() ? null : OBB.toAABB(obbs);
    }

    /**
     * 参与碰撞的甲板 OBB（2026-10-31 二次修正）。
     *
     * <p>优先走 {@link #deckOBBs}（结构模型骨骼现算）。若那条链拿不到东西——结构模型没注册、
     * 骨骼名对不上、或车辆数据里的骨骼条目没被解析——就回退到挂在同一骨骼上的<b>部件 OBB</b>
     * （部件 OBB 由本体自己的 {@code buildPartUnitsOBBs} 构建，是另一条完全独立的链路）。
     * 两条链任何一条通，甲板就有碰撞，不至于"直接穿过去"。</p>
     */
    public static List<OBB> deckCollisionOBBs(AbstractVehicle ship) {
        List<OBB> primary = deckOBBs(ship);
        if (!primary.isEmpty()) {
            return primary;
        }
        FlightDeckConfig config = flightDeckOf(ship);
        if (config == null) {
            return List.of();
        }
        List<OBB> fallback = new ArrayList<>();
        for (PartUnit<?> partUnit : ship.getPartUnits()) {
            if (partUnit.isDetached()) {
                continue;
            }
            Object data = partUnit.getData();
            if (!(data instanceof PartUnitData partData)) {
                continue;
            }
            if (config.bone().equals(partData.getStructureBone())) {
                fallback.addAll(partUnit.getOBBs());
            }
        }
        return fallback;
    }

    /** 配了甲板的载具进入世界时登记（含从存档载入）。 */
    @SubscribeEvent
    public static void onShipJoin(EntityJoinLevelEvent event) {
        if (event.getLevel().isClientSide() || !(event.getEntity() instanceof AbstractVehicle ship)) {
            return;
        }
        registerShip(event.getLevel(), ship);
    }

    /** 离开世界时注销，避免登记表与缓存里留死引用。 */
    @SubscribeEvent
    public static void onShipLeave(EntityLeaveLevelEvent event) {
        if (!(event.getEntity() instanceof AbstractVehicle ship)) {
            return;
        }
        Set<AbstractVehicle> ships = DECK_SHIPS.get(event.getLevel());
        if (ships != null) {
            ships.remove(ship);
        }
        DECK_OBB_CACHE.remove(ship.getUUID());
    }

    private static void registerShip(Level level, AbstractVehicle ship) {
        if (flightDeckOf(ship) == null) {
            return;
        }
        DECK_SHIPS.computeIfAbsent(level, key -> ConcurrentHashMap.newKeySet()).add(ship);
    }

    /**
     * 附近的甲板母舰（登记表里筛，不做区块扫描）。
     *
     * <p>距离判定用<b>包围盒相交</b>而不是中心点距离：航母 200 多格长，原点在中部，
     * 停在艏部的舰载机与原点可能超过 96 格。</p>
     */
    private static List<AbstractVehicle> nearbyDeckShips(AbstractVehicle vehicle) {
        Set<AbstractVehicle> ships = DECK_SHIPS.get(vehicle.level());
        if (ships == null || ships.isEmpty()) {
            return List.of();
        }
        AABB vehicleBox = vehicle.getBoundingBox();
        List<AbstractVehicle> out = new ArrayList<>(2);
        for (AbstractVehicle ship : ships) {
            if (ship == vehicle || ship.noPhysics || flightDeckOf(ship) == null) {
                continue;
            }
            if (!ship.getBoundingBox().inflate(SCAN_RADIUS).intersects(vehicleBox)) {
                continue;
            }
            out.add(ship);
        }
        return out;
    }

    @SubscribeEvent
    public static void onVehicleMove(VehicleMoveEvent event) {
        AbstractVehicle vehicle = event.getVehicle();
        if (vehicle == null || vehicle.level().isClientSide() || vehicle.noPhysics) {
            return;
        }
        // 已在甲板上：优先按原母舰续期，母舰没了/离开了再重新找
        Docking docking = DOCKED.get(vehicle.getId());
        if (docking != null) {
            Entity entity = vehicle.level().getEntity(docking.shipId());
            if (entity instanceof AbstractVehicle ship && tickDocked(vehicle, ship, docking)) {
                return;
            }
            DOCKED.remove(vehicle.getId());
        }
        tryDock(vehicle);
    }

    /** 维持一 tick 的停靠状态；返回 false 表示应当解除。 */
    private static boolean tickDocked(AbstractVehicle vehicle, AbstractVehicle ship, Docking docking) {
        FlightDeckConfig config = flightDeckOf(ship);
        if (config == null) {
            return false;
        }
        List<OBB> decks = deckCollisionOBBs(ship);
        if (decks.isEmpty()) {
            return false;
        }
        // 甲板厚板：本体那条支撑链拉不出已经陷进去的载具，这里每 tick 回正一次
        settleOnDeck(vehicle, decks);
        if (!isOnDeck(vehicle, decks)) {
            return false;
        }
        // 推油门即解除：交给本体物理去加速，本类不再接管速度
        if (hasPropulsionInput(vehicle)) {
            return false;
        }
        Vec3 target = expectedVelocity(vehicle, ship, docking, config);
        Vec3 current = vehicle.getDeltaMovement();
        Vec3 deviation = current.subtract(target);
        // 玩家起飞/被撞离：相对速度超阈值就交出控制权，本类不再干预
        if (deviation.horizontalDistance() > Math.max(0.01F, config.maxRelativeSpeed())) {
            return false;
        }
        double friction = Mth.clamp(config.friction(), 0.0F, 1.0F);
        double nx = current.x + (target.x - current.x) * friction;
        double nz = current.z + (target.z - current.z) * friction;
        // 垂直分量直接跟母舰：甲板升降时不会浮空或陷进去
        vehicle.setDeltaMovement(nx, target.y, nz);
        return true;
    }

    /** 甲板期望速度 = 母舰线速度 × carryLinear + 切向速度 × carryAngular + 位置回拉。 */
    private static Vec3 expectedVelocity(AbstractVehicle vehicle, AbstractVehicle ship,
                                         Docking docking, FlightDeckConfig config) {
        Vec3 shipVelocity = ship.getDeltaMovement();
        Vec3 target = new Vec3(shipVelocity.x, shipVelocity.y, shipVelocity.z)
                .scale(config.carryLinear());
        // 角速度：用每 tick 偏航增量，避免依赖物理内部角速度字段
        double yawDelta = Mth.wrapDegrees(ship.getYRot() - ship.yRotO);
        double omega = Math.toRadians(yawDelta) * config.carryAngular();
        if (Math.abs(omega) > 1.0E-5D) {
            Vec3 rel = vehicle.position().subtract(ship.position());
            // 绕 Y 轴：v = ω × r
            target = target.add(-omega * rel.z, 0.0D, omega * rel.x);
        }
        float spring = config.positionSpring();
        if (spring > 0.0F) {
            Vec3 ideal = ship.position().add(rotateY(docking.localX(), docking.localZ(), ship.getYRot()));
            target = target.add((ideal.x - vehicle.getX()) * spring, 0.0D,
                    (ideal.z - vehicle.getZ()) * spring);
        }
        return target;
    }

    /**
     * 飞行员是否正在给推力（跳跃/起飞判据，2026-11-01）。
     *
     * <p>固定翼的油门是<b>档位</b>（{@code FixedWingVehicle.throttleLevel}，按一次加一档，
     * 松开按键推力仍在），所以只能看档位；其余载具看即时输入（船/车的前后、直升机的总距）。
     * 引擎关着时一律视为没推力——否则"油门杆停在 30% 但引擎已关"的停机状态会拒绝耦合。</p>
     */
    private static boolean hasPropulsionInput(AbstractVehicle vehicle) {
        if (!vehicle.isEngineOn()) {
            return false;
        }
        if (vehicle instanceof FixedWingVehicle fixedWing) {
            return fixedWing.throttleLevel > PROPULSION_THROTTLE_MIN;
        }
        ControlUnit control = vehicle.controlUnit;
        return control != null && (control.forward || control.backward || control.up || control.down);
    }

    /** 尝试在当前载具附近找一艘带甲板的母舰并停靠。 */
    private static void tryDock(AbstractVehicle vehicle) {
        // 自身若有甲板但还没登记（例如登记事件早于配置加载），顺手补登记
        registerShip(vehicle.level(), vehicle);
        List<AbstractVehicle> candidates = nearbyDeckShips(vehicle);
        if (candidates.isEmpty()) {
            return;
        }
        // 优先最近的
        candidates.sort(Comparator.comparingDouble(other -> other.distanceToSqr(vehicle)));
        for (AbstractVehicle ship : candidates) {
            List<OBB> decks = deckCollisionOBBs(ship);
            if (decks.isEmpty()) {
                continue;
            }
            // 先回正再判定：落舰那一 tick 载具通常已经扎进板里，不回正永远判不到"在甲板上"
            settleOnDeck(vehicle, decks);
            // 正在给推力（在甲板上滑跑/起飞）：只做回正，不接管速度，否则会把推力吃掉
            if (hasPropulsionInput(vehicle)) {
                continue;
            }
            if (!isOnDeck(vehicle, decks)) {
                continue;
            }
            Vec3 rel = vehicle.position().subtract(ship.position());
            Vec3 local = rotateY(rel.x, rel.z, -ship.getYRot());
            DOCKED.put(vehicle.getId(), new Docking(ship.getId(), local.x, local.z));
            return;
        }
    }

    /** 回正所需的最小垂直 MTV（格）：再小说明本来就贴着面，不用动。 */
    private static final double SETTLE_MIN_MTV = 0.02D;

    /**
     * 视为"飞行员在给推力"的油门档位下限（固定翼 `throttleLevel`，0~100，开加力可到 100×thrustK）。
     *
     * <p>不能用"速度差超过 max_relative_speed"当起飞判据：甲板耦合每 tick 都把速度往母舰速度
     * 拽一次，推力攒到稳态也只是每 tick 加速度的 1/(friction) 倍——永远够不到 0.4 格/tick，
     * 结果就是"加力拉满、速度 0 km/h、一动不动"。必须直接看推力输入。</p>
     */
    private static final float PROPULSION_THROTTLE_MIN = 0.5F;

    /**
     * 把已经陷进甲板里的载具沿最短路推回板面（2026-10-31，`002` 是"飞机停不住"的正解）。
     *
     * <p>本体给<b>载具</b>找支撑走 {@code PhysicsEngine.findVehicleSupport}，而它的第一道关卡是
     * {@code box.embeddingDepth(start) > BODY_CONTACT_SLOP}(0.002) 就 {@code continue}——
     * 也就是说载具的采样点必须"几乎还没进入盒子"才算接触。甲板是 6.5 格厚的大板，
     * 落舰速度下一下扎进去几十厘米，之后每一次查询都被跳过，没有任何回拉路径，
     * 只能一路沉穿整块板。而<b>实体</b>走的是 {@code AbstractVehicle.support()}，
     * 用的是 {@code OBB.calculateMTV} 把实体推出来——这就是"人站得住、飞机停不住"的原因。</p>
     *
     * <p>这里照实体那条路补一次：用同一个 {@code calculateMTV} 求最小平移量，
     * 只接受"向上且纵向占主导"的推出（水平占主导说明是擦着甲板边缘，交给本体物理），
     * 同时把向下速度清掉，避免下一 tick 又扎进去。</p>
     *
     * @return 是否发生了回正
     */
    private static boolean settleOnDeck(AbstractVehicle vehicle, List<OBB> decks) {
        if (decks.isEmpty() || vehicle.noPhysics) {
            return false;
        }
        boolean adjusted = false;
        for (OBB deck : decks) {
            AABB box = vehicle.getBoundingBox();
            if (!OBB.isColliding(deck, box)) {
                continue;
            }
            Vec3 mtv = new Vec3(deck.calculateMTV(box));
            if (mtv.y <= SETTLE_MIN_MTV
                    || mtv.y <= Math.abs(mtv.x) || mtv.y <= Math.abs(mtv.z)) {
                continue;
            }
            Vec3 pos = vehicle.position();
            vehicle.setPos(pos.x + mtv.x, pos.y + mtv.y, pos.z + mtv.z);
            Vec3 velocity = vehicle.getDeltaMovement();
            vehicle.setDeltaMovement(velocity.x, Math.max(0.0D, velocity.y), velocity.z);
            adjusted = true;
        }
        return adjusted;
    }

    /**
     * 脚底是否真的踩在甲板顶面上（2026-10-31 修正）。
     *
     * <p>原来用甲板 OBB 的世界 AABB 做水平包含判定是错的：AABB 是轴对齐包围盒，
     * 甲板一旦倾斜，它的四角会框出一大块空气，载具能"停"在悬空的角落上。
     * 现在改成和本体 {@code PhysicsEngine.findVehicleSupport} 同样的做法——
     * 从脚底上方往脚底下方打一段短射线，逐 OBB 求 {@code clip}，再看法线是不是朝上的顶面。
     * 射线长度本身就同时约束了"高度接近"和"水平落在实心板面上"，倾斜甲板也不会误判。</p>
     */
    private static boolean isOnDeck(AbstractVehicle vehicle, List<OBB> decks) {
        AABB box = vehicle.getBoundingBox();
        double centerX = (box.minX + box.maxX) * 0.5D;
        double centerZ = (box.minZ + box.maxZ) * 0.5D;
        double feetY = box.minY;
        // 中心 + 四个内缩采样点：机翼/履带压在板上时，中心可能在甲板外沿
        double spanX = (box.maxX - box.minX) * 0.3D;
        double spanZ = (box.maxZ - box.minZ) * 0.3D;
        double[][] offsets = {
                {0.0D, 0.0D}, {spanX, 0.0D}, {-spanX, 0.0D}, {0.0D, spanZ}, {0.0D, -spanZ}
        };
        float top = (float) (feetY + CONTACT_UP);
        float bottom = (float) (feetY - CONTACT_DOWN);
        for (double[] offset : offsets) {
            Vector3f start = new Vector3f((float) (centerX + offset[0]), top, (float) (centerZ + offset[1]));
            Vector3f end = new Vector3f(start.x, bottom, start.z);
            for (OBB obb : decks) {
                java.util.Optional<Vector3f> hit = obb.clip(start, end);
                if (hit.isEmpty()) {
                    continue;
                }
                int face = obb.embeddingFace(new Vec3(hit.get()));
                Vector3f axis = obb.getAxes()[Math.abs(face) - 1];
                double sign = face > 0 ? 1.0D : -1.0D;
                // 只认朝上的顶面；侧面/底面不算停靠
                if (axis.y * sign > 0.25D) {
                    return true;
                }
            }
        }
        return false;
    }

    /** 把船体局部水平偏移按偏航角旋到世界偏移（yaw 为 MC 口径：0 朝 -Z）。 */
    private static Vec3 rotateY(double localX, double localZ, float yawDegrees) {
        double radians = Math.toRadians(-yawDegrees);
        double cos = Math.cos(radians);
        double sin = Math.sin(radians);
        return new Vec3(localX * cos + localZ * sin, 0.0D, -localX * sin + localZ * cos);
    }

    /** 该载具是否配置了甲板（mixin 判据用，避免每 tick 反复取配置）。 */
    public static boolean hasFlightDeck(AbstractVehicle vehicle) {
        return flightDeckOf(vehicle) != null;
    }

    /**
     * 立刻做一次停靠判定并登记（2026-10-31，供"右键把载具放到甲板上"用）。
     *
     * <p>正常停靠要等下一次 {@code VehicleMoveEvent}，但在甲板上刚生成出来的载具
     * 会先走一 tick 物理，那一 tick 里如果和母舰有相对速度，{@code impact} 就是几千伤害。
     * 生成后立即登记可以让撞击豁免从第一 tick 就生效。</p>
     *
     * @return 是否成功登记停靠
     */
    public static boolean dockNow(AbstractVehicle vehicle) {
        if (vehicle == null || vehicle.level().isClientSide()) {
            return false;
        }
        tryDock(vehicle);
        return DOCKED.containsKey(vehicle.getId());
    }

    private static FlightDeckConfig flightDeckOf(AbstractVehicle vehicle) {
        if (vehicle == null) {
            return null;
        }
        VehicleExtendedConfig config = RVP_VehicleExtendedConfigManager.INSTANCE.get(vehicle);
        return config == null ? null : config.flightDeck();
    }
}
