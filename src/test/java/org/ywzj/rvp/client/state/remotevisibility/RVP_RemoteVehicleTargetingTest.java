package org.ywzj.rvp.client.state.remotevisibility;

import com.google.common.collect.ImmutableList;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.entity.EntityInLevelCallback;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.ywzj.rvp.client.bridge.RVP_ClientActions;
import org.ywzj.rvp.client.bridge.RVP_ClientActionsAccess;
import org.ywzj.rvp.client.state.RVP_ClientWeaponTargets;
import org.ywzj.rvp.client.state.RVP_IrSeekerStateTable;
import org.ywzj.rvp.guidance.RVP_GuidanceMath;
import org.ywzj.rvp.guidance.RVP_IrLockHelper;
import org.ywzj.rvp.util.RVP_AimPointResolver;
import org.ywzj.rvp.weapon.core.RVP_ProjectileWeapon;
import org.ywzj.rvp.weapon.data.RVP_EnumWeaponKind;
import org.ywzj.rvp.weapon.data.RVP_WeaponData;
import org.ywzj.vehicle.custom.part.data.WeaponUnitData;
import org.ywzj.vehicle.custom.serialize.GsonUtil;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;
import org.ywzj.vehicle.entity.vehicle.FixedWingVehicle;
import org.ywzj.vehicle.vehicle.part.WeaponUnit;
import org.ywzj.vehicle.vehicle.structure.OBB;
import org.ywzj.vehicle.vehicle.structure.VehicleCubeGroup;
import org.ywzj.vehicle.vehicle.structure.VehicleCubeOBB;
import org.ywzj.vehicle.vehicle.weapon.AbstractVehicleWeapon;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.*;
import static org.ywzj.rvp.entity.projectile.RVP_ProjectileSimulationBootstrap.*;

/** 执行正式代理几何、搜索与 ID 交接；环境边界只有无窗口客户端世界与网络写锁。 */
class RVP_RemoteVehicleTargetingTest {
    /** 不创建游戏窗口的客户端世界。 */ private TestLevel level;
    /** 搜索方向固定朝东的测试武器站。 */ private TestStation station;
    /** 测试结束后恢复安全桥的原物理侧实现。 */ private Object previousActions;
    /** 测试 JVM 原本没有启动 Forge 物理端，结束后恢复其值。 */ private Object previousDist;
    /** 测试结束后恢复远距状态所属维度。 */ private Object previousDimension;
    /** 生产代理表；测试不复制目标筛选逻辑。 */ private Map<Integer, Object> proxies;
    /** 测试前已有的代理，避免污染其它回归。 */ private Map<Integer, Object> previousProxies;

    @BeforeAll static void bootstrap() {
        org.ywzj.rvp.entity.projectile.RVP_ProjectileSimulationBootstrap.initialize();
    }

    @BeforeEach @SuppressWarnings("unchecked") void prepare() throws Exception {
        previousDist = net.minecraftforge.fml.loading.FMLEnvironment.dist;
        replaceStatic(net.minecraftforge.fml.loading.FMLEnvironment.class, "dist", net.minecraftforge.api.distmarker.Dist.CLIENT);
        previousActions = read(RVP_ClientActionsAccess.class, null, "INSTANCE");
        // 测试 JVM 没有 Forge 物理端启动；仅绑定真实客户端实现，业务方法全部照常执行。
        replaceStatic(RVP_ClientActionsAccess.class, "INSTANCE", new RVP_ClientActions());
        previousDimension = read(RVP_ClientRemoteVehicleVisualState.class, null, "dimension");
        proxies = (Map<Integer, Object>) read(RVP_ClientRemoteVehicleVisualState.class, null, "PROXIES");
        previousProxies = new HashMap<>(proxies);
        proxies.clear();
        write(RVP_ClientRemoteVehicleVisualState.class, null, "dimension", Level.OVERWORLD.location());
        level = environment(TestLevel.class);
        level.tick = 100;
        level.live = new HashMap<>();
        write(Level.class, level, "dimension", Level.OVERWORLD);
        write(Level.class, level, "isClientSide", true);
        station = environment(TestStation.class);
        station.vehicle = vehicle(1, false);
        station.sensor = WeaponUnitData.FireControlSensorType.IR;
        setWeapon(1000, "[[0,10]]");
    }

    @AfterEach void restore() throws Exception {
        RVP_IrSeekerStateTable.clearUnit(station);
        proxies.clear();
        proxies.putAll(previousProxies);
        write(RVP_ClientRemoteVehicleVisualState.class, null, "dimension", previousDimension);
        replaceStatic(RVP_ClientActionsAccess.class, "INSTANCE", previousActions);
        replaceStatic(net.minecraftforge.fml.loading.FMLEnvironment.class, "dist", previousDist);
    }

    @Test void acquiresProxyOutsideClientChunksAndUsesServerAltitude() throws Exception {
        TestVehicle proxy = addProxy(42, new Vec3(320, 100, 0), 3);
        assertFalse(level.hasChunkAt(proxy.blockPosition()), "模拟客户端 2 区块外未加载地形");
        // 调用正式红外搜索；候选不加入世界，仍在距离与服务器离地高度范围内。
        assertTrue(level.live.isEmpty());
        assertSame(proxy, RVP_ClientWeaponTargets.findInfraredTarget(station, 5));
        assertEquals(8, proxy.getBoundingBox().getXsize(), 1e-5, "不能使用 remote 的一格假包围盒");
        assertEquals(new Vec3(320, 100, 0), RVP_AimPointResolver.resolveLargestObbCenter(proxy));
        assertTrue(RVP_GuidanceMath.isTargetPassAltFilter(proxy, -10));
        assertFalse(RVP_GuidanceMath.isTargetPassAltFilter(proxy, 20));
        setWeapon(1000, "[[20,inf]]");
        assertNull(RVP_ClientWeaponTargets.findInfraredTarget(station, 5), "空地高度门仍须有效");
    }

    @Test void weaponRangeReplacesFixed512CutoffWithoutBypassingAngleOrOcclusion() throws Exception {
        TestVehicle proxy = addProxy(42, new Vec3(700, 100, 0), 3);
        assertSame(proxy, RVP_ClientWeaponTargets.findInfraredTarget(station, 5));
        setWeapon(600, "[[0,10]]");
        assertNull(RVP_ClientWeaponTargets.findInfraredTarget(station, 5));
        setWeapon(1000, "[[0,10]]");
        station.direction = new Vec3(0, 0, 1);
        assertNull(RVP_ClientWeaponTargets.findInfraredTarget(station, 5));
        station.direction = null;
        level.wall = true;
        assertNull(RVP_ClientWeaponTargets.findInfraredTarget(station, 5), "已加载的不透光方块仍须挡锁");
    }

    @Test void mergedInfraredSearchPreservesAmmoSignatureSizeRule() {
        level.localTerrainLoaded = true;
        setWeapon(1000, "[[0,inf]]");
        var ammo = new org.ywzj.rvp.entity.projectile.RVP_MissileEntity(
                org.ywzj.rvp.all.RVP_Entities.RVP_MISSILE.get(), level);
        // 手工分配的载具占用 ID 1；测试实体也必须满足真实世界的 ID 唯一性。
        ammo.setId(43);
        ammo.setPos(320, 100, 0);
        write(org.ywzj.rvp.entity.projectile.RVP_BaseBullet.class, ammo, "signatureSize", 4f);
        level.live.put(ammo.getId(), ammo);
        assertTrue(ammo.getBoundingBox().getSize() < 1);
        assertSame(ammo, RVP_ClientWeaponTargets.findInfraredTarget(station, 5),
                "小弹体应按配置的红外信号尺寸通过原有体积门");
        write(org.ywzj.rvp.entity.projectile.RVP_BaseBullet.class, ammo, "signatureSize", 0f);
        assertNull(RVP_ClientWeaponTargets.findInfraredTarget(station, 5));
    }

    @Test void realProxyHandoverKeepsOneCandidateAndRefreshesStationReference() throws Exception {
        TestVehicle proxy = addProxy(42, new Vec3(320, 100, 0), 3);
        station.setLockedEntity(proxy);
        var weaponId = net.minecraft.resources.ResourceLocation.tryParse("rvp:remote_lock_test");
        var armed = RVP_IrSeekerStateTable.stateFor(station, weaponId);
        armed.phase = RVP_IrSeekerStateTable.Phase.ARMED;
        armed.armedId = 42;
        armed.dwellTicks = 20;
        assertSame(proxy, RVP_IrSeekerStateTable.getArmedEntity(station, weaponId));
        TestVehicle real = vehicle(42, false);
        real.setPos(320, 100, 0);
        level.live.put(42, real);
        assertSame(real, RVP_ClientWeaponTargets.resolve(level, 42));
        assertSame(real, RVP_IrSeekerStateTable.getArmedEntity(station, weaponId));
        assertEquals(List.of(real), RVP_ClientWeaponTargets.entities(level));
        RVP_ClientWeaponTargets.refreshStationLock(station);
        assertSame(real, station.getLockedEntity());
        level.live.clear();
        RVP_ClientWeaponTargets.refreshStationLock(station);
        assertSame(proxy, station.getLockedEntity());
        assertSame(proxy, RVP_IrSeekerStateTable.getArmedEntity(station, weaponId));
        assertEquals(20, armed.dwellTicks, "同 ID 表示切换不能重新累计锁定驻留");
        // 撤销代理资格后必须清锁，不能继续持有仍 isAlive 的离世界对象。
        proxies.clear();
        RVP_ClientWeaponTargets.refreshStationLock(station);
        assertNull(station.getLockedEntity());
        assertNull(RVP_IrSeekerStateTable.getArmedEntity(station, weaponId));
    }

    @Test void expiredDestroyedAndOtherDimensionProxiesCannotBeTargets() throws Exception {
        addProxy(42, new Vec3(320, 100, 0), 3);
        level.tick = 124;
        assertNotNull(RVP_ClientWeaponTargets.resolve(level, 42));
        level.tick = 125;
        assertNull(RVP_ClientWeaponTargets.resolve(level, 42));
        assertTrue(RVP_ClientWeaponTargets.entities(level).isEmpty());
        level.tick = 100;
        Object state = proxies.get(42);
        write(state.getClass(), state, "latest", sample(new Vec3(320, 100, 0), 100, 3, true));
        assertNull(RVP_ClientWeaponTargets.resolve(level, 42));
        write(state.getClass(), state, "latest", sample(new Vec3(320, 100, 0), 100, 3, false));
        write(Level.class, level, "dimension", Level.NETHER);
        assertNull(RVP_ClientWeaponTargets.resolve(level, 42));
    }

    @Test void movingProxyUsesRenderTimelineAndUpdatesActualObbs() throws Exception {
        TestVehicle proxy = addProxy(42, new Vec3(300, 100, 0), 3);
        Object state = proxies.get(42);
        write(state.getClass(), state, "previous", sample(new Vec3(300, 100, 0), 100, 3, false));
        write(state.getClass(), state, "latest", sample(new Vec3(400, 100, 0), 105, 3, false));
        write(state.getClass(), state, "lastUpdateClientTick", 105L);
        level.tick = 107;
        assertSame(proxy, RVP_ClientWeaponTargets.resolve(level, 42));
        // 正式插值延迟一份快照：107 - 5 = 102，位置应是两包之间的 40%。
        assertEquals(340, proxy.getX(), 1e-5);
        assertEquals(340, proxy.getBoundingBox().getCenter().x, 1e-5);
        assertEquals(340, RVP_AimPointResolver.resolveLargestObbCenter(proxy).x, 1e-5);
    }

    @Test void renderPoseRestorePreservesFullTargetBounds() throws Exception {
        TestVehicle proxy = addProxy(42, new Vec3(320, 100, 0), 3);
        RVP_ClientWeaponTargets.resolve(level, 42);
        AABB targetBounds = proxy.getBoundingBox();
        Class<?> pose = Class.forName("org.ywzj.rvp.client.render.remotevisibility.RVP_RemoteVehicleVisualRenderer$ProxyPose");
        var capture = pose.getDeclaredMethod("capture", AbstractVehicle.class);
        var restore = pose.getDeclaredMethod("restore", AbstractVehicle.class);
        capture.setAccessible(true);
        restore.setAccessible(true);
        Object saved = capture.invoke(null, proxy);
        proxy.setPos(330, 100, 0);
        assertEquals(1, proxy.getBoundingBox().getXsize(), 1e-5);
        restore.invoke(saved, proxy);
        assertEquals(targetBounds, proxy.getBoundingBox(), "画完一帧后不能把锁定轮廓缩回一格");
    }

    @Test void tvRaycastSelectsPreciseProxyHitboxAndRejectsExpiredProxy() throws Exception {
        var oldAssets = org.ywzj.vehicle.custom.CommonAssetsManager.INSTANCE;
        var manager = org.ywzj.rvp.config.RVP_VehicleExtendedConfigManager.INSTANCE;
        Object oldConfigs = read(manager.getClass(), manager, "configs");
        Object oldRaw = read(manager.getClass(), manager, "rawVehicleJson");
        try {
            // 只在内存中准备结构输入；仍由正式结构解析器、骨变换和电视选点执行。
            org.ywzj.vehicle.custom.CommonAssetsManager.INSTANCE = new org.ywzj.vehicle.custom.CommonAssetsManager();
            var modelId = net.minecraft.resources.ResourceLocation.tryParse("rvp:vehicle/remote_target_test");
            var vehicleId = EntityType.getKey(EntityType.BOAT);
            var models = (org.ywzj.vehicle.custom.StructureModelManager)
                    org.ywzj.vehicle.custom.CommonAssetsManager.structureModelManager();
            models.apply(Map.of(modelId, com.google.gson.JsonParser.parseString("""
                    {"format_version":"1.12.0","minecraft:geometry":[{"description":{
                    "identifier":"geometry.remote_test","texture_width":16,"texture_height":16,
                    "visible_bounds_offset":[0,0,0]},"bones":[
                    {"name":"body","pivot":[0,0,0],"cubes":[{"origin":[-64,-8,-16],"size":[128,16,32],"uv":[0,0]}]}]}]}
                    """)), null, null);
            manager.applyFromJsonMap(Map.of(vehicleId, com.google.gson.JsonParser.parseString("""
                    {"structure_model":"rvp:vehicle/remote_target_test",
                    "physics_info":{"physics_only_bones":["main_structure"]}}
                    """)));
            TestVehicle proxy = addProxy(42, new Vec3(320, 100, 0), 3);
            proxy.setVehicleId(vehicleId);
            assertTrue(models.getStructureModel(modelId).isPresent(), "电视回归必须成功解析真实结构输入");
            assertEquals(modelId, manager.get(proxy).structureModel());
            var mc = environment(net.minecraft.client.Minecraft.class);
            mc.level = level;
            var raycast = org.ywzj.rvp.client.state.RVP_ClientHitlUtil.class.getDeclaredMethod("findEntityHit",
                    net.minecraft.client.Minecraft.class, Entity.class, Vec3.class, Vec3.class);
            raycast.setAccessible(true);
            Object hit = raycast.invoke(null, mc, station.vehicle, new Vec3(0, 100, 0), new Vec3(500, 100, 0));
            var precise = assertInstanceOf(org.ywzj.rvp.client.state.RVP_HitlVehicleHitResult.class, hit);
            assertSame(proxy, precise.getEntity());
            assertEquals("body", precise.key().bone());
            assertTrue(level.live.isEmpty(), "电视选点不能通过偷偷把代理加入世界来通过测试");
            level.tick = 125;
            assertNull(raycast.invoke(null, mc, station.vehicle, new Vec3(0, 100, 0), new Vec3(500, 100, 0)));
        } finally {
            org.ywzj.vehicle.custom.CommonAssetsManager.INSTANCE = oldAssets;
            write(manager.getClass(), manager, "configs", oldConfigs);
            write(manager.getClass(), manager, "rawVehicleJson", oldRaw);
        }
    }

    /** 只提供真实的武器数据；搜索与高度判定不写测试替代算法。 */
    private void setWeapon(int range, String altitude) {
        RVP_WeaponData data = GsonUtil.GSON.fromJson("""
                {"type":"rvp:missile","guidance_data":{"guidance_type":"IR",
                "max_lock_angle":10,"max_off_axis_lock_angle":45,
                "lock_target_distance_range":"[[0,%d]]","lock_altitude_range":"%s"}}
                """.formatted(range, altitude), RVP_WeaponData.class);
        data.setWeaponKind(RVP_EnumWeaponKind.MISSILE);
        station.weapon = environment(RVP_ProjectileWeapon.class);
        write(AbstractVehicleWeapon.class, station.weapon, "data", data);
    }

    /** 建立一个正式双样本状态；省略的是资源/网络接收，不替换生产状态解析。 */
    private TestVehicle addProxy(int id, Vec3 position, double height) throws Exception {
        TestVehicle proxy = vehicle(id, true);
        Class<?> stateType = Class.forName(RVP_ClientRemoteVehicleVisualState.class.getName() + "$ProxyState");
        var initial = stateType.getDeclaredMethod("initial", RVP_ClientRemoteVehicleVisualState.ProxyMetadata.class,
                AbstractVehicle.class, RVP_ClientRemoteVehicleVisualState.Sample.class, long.class);
        initial.setAccessible(true);
        var metadata = new RVP_ClientRemoteVehicleVisualState.ProxyMetadata(
                EntityType.getKey(EntityType.BOAT), EntityType.getKey(EntityType.BOAT), EntityType.getKey(EntityType.BOAT));
        proxies.put(id, initial.invoke(null, metadata, proxy, sample(position, 100, height, false), 100L));
        return proxy;
    }

    /** 单元测试只构造真实世界姿态所需的结构块，不读写任何载具包模型资产。 */
    private TestVehicle vehicle(int id, boolean remote) {
        TestVehicle vehicle = environment(TestVehicle.class);
        write(Entity.class, vehicle, "level", level);
        write(Entity.class, vehicle, "position", Vec3.ZERO);
        write(Entity.class, vehicle, "blockPosition", BlockPos.ZERO);
        write(Entity.class, vehicle, "chunkPosition", new net.minecraft.world.level.ChunkPos(0, 0));
        write(Entity.class, vehicle, "levelCallback", EntityInLevelCallback.NULL);
        write(Entity.class, vehicle, "passengers", ImmutableList.of());
        vehicle.setId(id);
        vehicle.setUUID(UUID.randomUUID());
        vehicle.remote = remote;
        vehicle.centerOffset = Vec3.ZERO;
        VehicleCubeOBB body = new VehicleCubeOBB(new OBB(new Vector3f(), new Vector3f(4, .5f, 1), new Quaternionf()));
        body.group = new VehicleCubeGroup(null, new Quaternionf(), Vec3.ZERO);
        body.x = -4; body.y = -.5; body.z = -1;
        write(AbstractVehicle.class, vehicle, "vehicleCubeOBBs", List.of(body));
        write(AbstractVehicle.class, vehicle, "mainCubeOBB", body);
        vehicle.aerodynamicCubeOBB = body;
        write(AbstractVehicle.class, vehicle, "partUnits", List.of());
        vehicle.setPos(0, 100, 0);
        return vehicle;
    }

    /** 从正式数据记录创建样本。 */
    private static RVP_ClientRemoteVehicleVisualState.Sample sample(Vec3 position, long tick, double height, boolean destroyed) {
        return new RVP_ClientRemoteVehicleVisualState.Sample(tick, 42, position, Vec3.ZERO,
                0, 0, 0, height, destroyed, false, 0, 0);
    }

    /** 只切换测试进程的物理端绑定，结束时恢复；无需更改生产安全桥来迁就测试。 */
    private static void replaceStatic(Class<?> type, String name, Object implementation) throws Exception {
        Field unsafeField = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        unsafeField.setAccessible(true);
        sun.misc.Unsafe unsafe = (sun.misc.Unsafe) unsafeField.get(null);
        Field actions = type.getDeclaredField(name);
        unsafe.putObjectVolatile(unsafe.staticFieldBase(actions), unsafe.staticFieldOffset(actions), implementation);
    }

    /** 无窗口世界只提供时间、可见实体与方块遮挡，未加载地形查询一律失败。 */
    private static final class TestLevel extends ClientLevel {
        /** 环境观测时间。 */ private long tick;
        /** 已加入真实客户端世界的实体；代理不加入此表。 */ private Map<Integer, Entity> live;
        /** 是否在射线中间放一面不透光墙。 */ private boolean wall;
        /** 仅弹药兼容回归提供已加载平地；代理回归仍拒绝全部地形查询。 */ private boolean localTerrainLoaded;
        private TestLevel() { super(null, null, null, null, 2, 2, null, null, false, 0); }
        @Override public long getGameTime() { return tick; }
        @Override public Entity getEntity(int id) { return live.get(id); }
        @Override public Iterable<Entity> entitiesForRendering() { return live.values(); }
        @Override public boolean hasChunkAt(BlockPos pos) { return localTerrainLoaded; }
        @Override public int getHeight(Heightmap.Types type, int x, int z) {
            if (localTerrainLoaded) return 0;
            throw new AssertionError("未加载地形不得用于计算远距目标高度");
        }
        @Override public BlockHitResult clip(ClipContext context) {
            Vec3 end = context.getTo();
            return wall ? new BlockHitResult(context.getFrom().lerp(end, .5), net.minecraft.core.Direction.WEST,
                    BlockPos.containing(context.getFrom().lerp(end, .5)), false)
                    : BlockHitResult.miss(end, net.minecraft.core.Direction.WEST, BlockPos.containing(end));
        }
        @Override public BlockState getBlockState(BlockPos pos) { return Blocks.STONE.defaultBlockState(); }
        @Override public List<Entity> getEntities(Entity excluded, AABB box, Predicate<? super Entity> predicate) {
            return live.values().stream().filter(entity -> entity != excluded
                    && entity.getBoundingBox().intersects(box) && predicate.test(entity)).toList();
        }
    }

    /** 仅跳过未联网的损毁同步字段；位置、包围盒和 OBB 更新仍执行本体真实方法。 */
    private static final class TestVehicle extends FixedWingVehicle {
        private TestVehicle() { super(null, null); }
        @Override public boolean isDestroyed() { return false; }
        @Override public boolean isDescending() { return false; }
    }

    /** 仅提供操作输入与截获网络写锁，几何/搜索/交接保持生产代码。 */
    private static final class TestStation extends WeaponUnit {
        /** 发射载具。 */ private AbstractVehicle vehicle;
        /** 当前真实 RVP 武器数据对象。 */ private RVP_ProjectileWeapon weapon;
        /** 可切换的瞄准方向；null 表示正东。 */ private Vec3 direction;
        /** 当前测试锁定目标。 */ private Entity locked;
        /** 当前火控传感器。 */ private WeaponUnitData.FireControlSensorType sensor;
        private TestStation() { super(0, null, null); }
        @Override public AbstractVehicle getVehicle() { return vehicle; }
        @Override public Optional<AbstractVehicleWeapon<?>> getCurrentWeapon() { return Optional.of(weapon); }
        @Override public Vec3 worldPivotPosition() { return new Vec3(0, 100, 0); }
        @Override public Vec3 worldVec() { return direction == null ? new Vec3(1, 0, 0) : direction; }
        @Override public WeaponUnit getRootParentWeaponUnit() { return this; }
        @Override public WeaponUnitData.FireControlSensorType getFireControlSensorType() { return sensor; }
        @Override public Entity getLockedEntity() { return locked; }
        @Override public void setLockedEntity(Entity entity) { locked = entity; }
    }
}
