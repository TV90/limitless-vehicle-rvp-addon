package org.ywzj.rvp.client.state;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.*;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.*;
import org.junit.jupiter.api.*;
import org.ywzj.rvp.guidance.RVP_IrLockHelper;
import org.ywzj.rvp.weapon.core.RVP_ProjectileWeapon;
import org.ywzj.rvp.weapon.data.*;
import org.ywzj.vehicle.custom.part.data.WeaponUnitData;
import org.ywzj.vehicle.custom.serialize.GsonUtil;
import org.ywzj.vehicle.entity.vehicle.*;
import org.ywzj.vehicle.vehicle.part.*;
import org.ywzj.vehicle.vehicle.structure.VehicleCubeOBB;
import org.ywzj.vehicle.vehicle.weapon.AbstractVehicleWeapon;

import java.util.*;
import java.util.function.Predicate;
import static org.junit.jupiter.api.Assertions.*;
import static org.ywzj.rvp.entity.projectile.RVP_ProjectileSimulationBootstrap.*;

/** 无窗口客户端真实搜索及发射门回归；仅隔离世界、输入和网络收发边界。 */
class RVP_AirIndependentLockTest {
    /** 当前客户端世界，不启动窗口或服务器。 */ private TestLevel level;
    /** 操作武器站；雷达列表始终为空。 */ private Station station;
    /** 当前弹种的真实生产实例。 */ private RVP_ProjectileWeapon weapon;
    /** 红外搜索中的真实载具。 */ private Target target;

    @BeforeAll static void bootstrap() { initialize(); }
    @BeforeEach void prepare() {
        level = environment(TestLevel.class); level.live = new HashMap<>(); level.tick = 100;
        write(Level.class, level, "dimension", Level.OVERWORLD);
        write(Level.class, level, "isClientSide", true);
        station = environment(Station.class); station.vehicle = vehicle(1,0);
        target = vehicle(2,100); level.live.put(target.getId(),target);
        var data = data("AIR");
        weapon = new RVP_ProjectileWeapon(station.vehicle,station,0,data,"air_probe",null); station.weapon = weapon;
    }
    @AfterEach void cleanState() { RVP_IrSeekerStateTable.clearUnit(station); }

    @Test void radarOffAirAccumulatesRealInfraredDwellWithoutStationLock() {
        for (int i=0;i<10;i++) {
            level.tick++;
            // 模拟本体RF火控每刻清掉不在雷达探测表中的站级目标。
            station.locked = null;
            assertTrue(RVP_IrSeekerTracker.tickAirBeforeRadar(station,weapon));
        }
        var state = RVP_IrSeekerStateTable.find(station,weapon.getData().getWeaponId());
        assertNotNull(state); assertEquals(target.getId(),state.candidateId);
        assertEquals(RVP_IrSeekerStateTable.Phase.TRACKING,state.phase);
        assertTrue(state.dwellTicks >= 9,"无雷达时红外驻留不能被RF冷却门饿死");
        assertNull(station.locked,"驻留尚未完成不能提前写站级锁");
    }

    @Test void armedAirSurvivesRfClearAndCanPassActualClientLaunchGate() {
        // 网络边界后的已授权状态；搜索、保锁和射击门均调用生产实现。
        var state = RVP_IrSeekerStateTable.stateFor(station,weapon.getData().getWeaponId());
        state.phase=RVP_IrSeekerStateTable.Phase.ARMED; state.armedId=target.getId();
        state.candidateId=target.getId(); state.dwellTicks=20; state.lastArmedInEnvelopeTick=level.tick;
        for (int i=0;i<5;i++) {
            level.tick++; station.locked=null;
            assertTrue(RVP_IrSeekerTracker.tickAirBeforeRadar(station,weapon));
            assertSame(target,station.locked);
            assertEquals(RVP_IrSeekerStateTable.Phase.ARMED,state.phase);
        }
        station.locked=null;
        assertTrue(weapon.passesShootLockGates(),"AIR独立红外授权必须允许无雷达发射");
        assertSame(target,station.locked);
    }

    @Test void terminalInfraredNeverCreatesAPrelaunchSeekerOnSaclos() {
        var data = data("SACLOS");
        assertFalse(data.hasLaunchSeeker()); assertFalse(RVP_IrLockHelper.isIrLaunchWeapon(data));
        assertFalse(RVP_IrLockHelper.supportsInfraredLaunchSearch(data));
        assertFalse(new RVP_ProjectileWeapon(station.vehicle,station,0,data,"saclos_probe",null).withSeeker());
    }

    @Test void everyMainSeekerRetainsItsLaunchCapabilityWithATerminalStage() {
        for (String type : List.of("IR","AIR","SARH","ARM","ARH")) {
            var data=data(type);
            assertTrue(data.hasLaunchSeeker(),type);
            assertTrue(new RVP_ProjectileWeapon(station.vehicle,station,0,data,type,null).withSeeker(),type);
        }
    }

    /** 复合制导只改变飞行阶段，不把末端IR冒充发射前IR。 */
    private RVP_WeaponData data(String main) {
        var data=GsonUtil.GSON.fromJson("""
            {"type":"rvp:missile","require_lock":true,"guidance_data":{"guidance_type":"%s",
             "max_lock_angle":30,"max_guidance_angle":50,"lock_target_distance_range":"[[0,1800]]",
             "terminal_guidance":{"guidance_type":"IR","guidance_start_tick":40,
              "max_lock_angle":15,"seeker_search_distance_range":"[[0,128]]"}}}
            """.formatted(main),RVP_WeaponData.class);
        data.setWeaponKind(RVP_EnumWeaponKind.MISSILE);
        data.setWeaponId(ResourceLocation.tryParse("rvp:independent_air_probe"));
        return data;
    }

    /** 提供载具位置与空结构；候选、驻留与发射判定不做测试替代。 */
    private Target vehicle(int id,double z) {
        Target v=environment(Target.class);
        write(Entity.class,v,"level",level); write(Entity.class,v,"position",new Vec3(0,100,z));
        write(Entity.class,v,"bb",new AABB(-2,99,z-4,2,101,z+4));
        write(Entity.class,v,"dimensions",EntityDimensions.scalable(4,2));
        write(Entity.class,v,"deltaMovement",Vec3.ZERO);
        write(Entity.class,v,"passengers",com.google.common.collect.ImmutableList.of());
        write(AbstractVehicle.class,v,"vehicleCubeOBBs",List.of());
        write(AbstractVehicle.class,v,"partUnits",List.of());
        v.setId(id); v.setUUID(UUID.randomUUID()); return v;
    }

    /** 世界边界只提供已加载实体、平地和无障碍视线。 */
    private static final class TestLevel extends ClientLevel {
        /** 世界时钟。 */ long tick;
        /** 已加载实体表。 */ Map<Integer,Entity> live;
        private TestLevel() { super(null,null,null,null,2,2,null,null,false,0); }
        @Override public long getGameTime() { return tick; }
        @Override public Entity getEntity(int id) { return live.get(id); }
        @Override public Iterable<Entity> entitiesForRendering() { return live.values(); }
        @Override public boolean hasChunkAt(BlockPos pos) { return true; }
        @Override public int getHeight(Heightmap.Types t,int x,int z) { return 0; }
        @Override public net.minecraft.world.level.block.state.BlockState getBlockState(BlockPos p) { return Blocks.AIR.defaultBlockState(); }
        @Override public BlockHitResult clip(net.minecraft.world.level.ClipContext c) {
            return BlockHitResult.miss(c.getTo(),net.minecraft.core.Direction.NORTH,BlockPos.containing(c.getTo()));
        }
        @Override public List<Entity> getEntities(Entity e,AABB b,Predicate<? super Entity> p) {
            return live.values().stream().filter(v -> v!=e && b.intersects(v.getBoundingBox()) && p.test(v)).toList();
        }
    }
    /** 仅隔离未初始化的同步/模型字段。 */
    private static final class Target extends WheeledVehicle {
        private Target() { super(null,null); }
        @Override public boolean isAlive() { return true; }
        @Override public boolean isDescending() { return false; }
        @Override public boolean isDestroyed() { return false; }
        @Override public List<PartUnit<?>> getPartUnits() { return List.of(); }
        @Override public List<VehicleCubeOBB> getVehicleCubeOBBs() { return List.of(); }
    }
    /** 模拟RF站，方向为前方；搜索与授权算法保持真实实现。 */
    private static final class Station extends WeaponUnit {
        /** 载具。 */ AbstractVehicle vehicle;
        /** 当前武器。 */ RVP_ProjectileWeapon weapon;
        /** 本体RF锁引用。 */ Entity locked;
        private Station() { super(0,null,null); }
        @Override public AbstractVehicle getVehicle() { return vehicle; }
        @Override public LivingEntity getOwner() { return null; }
        @Override public WeaponUnit getRootParentWeaponUnit() { return this; }
        @Override public Optional<AbstractVehicleWeapon<?>> getCurrentWeapon() { return Optional.of(weapon); }
        @Override public List<AbstractVehicleWeapon<?>> getIndexedWeapons() { return List.of(weapon); }
        @Override public List<RadarUnit> getRadarUnits() { return List.of(); }
        @Override public List<WeaponUnit> getSubWeaponUnits() { return List.of(); }
        @Override public Entity getLockedEntity() { return locked; }
        @Override public void setLockedEntity(Entity t) { locked=t; }
        @Override public String getId() { return "turret"; }
        @Override public boolean isSeekerOn() { return true; }
        @Override public Vec3 worldPivotPosition() { return new Vec3(0,100,0); }
        @Override public Vec3 worldVec() { return new Vec3(0,0,1); }
        @Override public WeaponUnitData.FireControlSensorType getFireControlSensorType() { return WeaponUnitData.FireControlSensorType.RF; }
    }
}
