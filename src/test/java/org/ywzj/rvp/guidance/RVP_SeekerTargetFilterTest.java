package org.ywzj.rvp.guidance;

import com.google.common.collect.ImmutableList;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.scores.Team;
import org.junit.jupiter.api.*;
import org.ywzj.rvp.all.RVP_Entities;
import org.ywzj.rvp.entity.projectile.*;
import org.ywzj.vehicle.entity.vehicle.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.ywzj.rvp.entity.projectile.RVP_ProjectileSimulationBootstrap.*;

/** 归属过滤必须覆盖不同表示对象，同时保留无主及敌方弹药拦截。 */
class RVP_SeekerTargetFilterTest {
    /** 无磁盘的服务端查询环境。 */ private RVP_ProjectileSimulationLevel level;
    @BeforeAll static void bootstrap() { initialize(); }
    @BeforeEach void prepare() { level=RVP_ProjectileSimulationLevel.create(0,null); }

    @Test void sameShooterIsExcludedEvenAfterRepresentationHandoff() {
        Marker owner=new Marker(EntityType.MARKER,level),copy=new Marker(EntityType.MARKER,level);
        copy.setUUID(owner.getUUID());
        Ammo seeker=ammo(owner,null),target=ammo(copy,null);
        assertFalse(RVP_SeekerTargetFilter.allows(seeker,target));
        assertFalse(RVP_SeekerTargetFilter.allows(owner,null,target),"发射前验证与飞行中相同");
    }
    @Test void sameCarrierIsExcludedAcrossOperatorAndRepresentationChanges() {
        Carrier a=carrier(),copy=carrier(); copy.setUUID(a.getUUID());
        assertFalse(RVP_SeekerTargetFilter.allows(ammo(new Marker(EntityType.MARKER,level),a),
                ammo(new Marker(EntityType.MARKER,level),copy)));
    }
    @Test void distinctHostileOrUnownedMissilesRemainInterceptable() {
        assertTrue(RVP_SeekerTargetFilter.allows(ammo(null,carrier()),ammo(null,carrier())));
        assertTrue(RVP_SeekerTargetFilter.allows(ammo(null,null),ammo(null,null)),"双方null不代表同一射手");
    }
    @Test void damageCreditOwnerDoesNotMakeIndependentSupportAmmoPartOfTheInterceptorCarrier() {
        Marker caller = new Marker(EntityType.MARKER, level);
        Carrier interceptorCarrier = carrier();
        Ammo supportRound = ammo(caller, null);
        // 终端炮火没有发射载具，owner 仅用于伤害/击杀归属；发射前与在途必须同时允许拦截。
        assertTrue(RVP_SeekerTargetFilter.allows(caller, interceptorCarrier, supportRound));
        assertTrue(RVP_SeekerTargetFilter.allows(ammo(caller, interceptorCarrier), supportRound));
        // 同一玩家本车前一发仍须排除，不能因兼容终端炮火而恢复自锁。
        assertFalse(RVP_SeekerTargetFilter.allows(caller, interceptorCarrier, ammo(caller, interceptorCarrier)));
    }
    @Test void launchCarrierOccupancyControlsInterceptionAfterChangingVehicles() {
        LivingEntity operator = environment(ArmorStand.class);
        operator.setUUID(UUID.randomUUID());
        Carrier launchCarrier = carrier();
        Carrier interceptorCarrier = carrier();
        Ammo incoming = ammo(operator, launchCarrier);
        Ammo interceptor = ammo(operator, interceptorCarrier);
        write(Entity.class, launchCarrier, "passengers", ImmutableList.of(operator));

        assertFalse(RVP_SeekerTargetFilter.allows(operator, interceptorCarrier, incoming));
        assertFalse(RVP_SeekerTargetFilter.allows(interceptor, incoming));

        // 玩家从 A 舰换到 B 舰，发射时保存的 owner 和发射载具都保留。
        write(Entity.class, launchCarrier, "passengers", ImmutableList.of());
        write(Entity.class, interceptorCarrier, "passengers", ImmutableList.of(operator));
        assertTrue(RVP_SeekerTargetFilter.allows(operator, interceptorCarrier, incoming));
        assertTrue(RVP_SeekerTargetFilter.allows(interceptor, incoming));
        assertFalse(RVP_SeekerTargetFilter.allows(operator, null, incoming), "保留手持武器自弹过滤");
        assertFalse(RVP_SeekerTargetFilter.allows(ammo(operator, launchCarrier), incoming), "空车同源连射仍排除");

        // A 舰没有驾驶员但还有炮手时，按有乘员处理。
        write(Entity.class, launchCarrier, "passengers", ImmutableList.of(environment(ArmorStand.class)));
        assertFalse(RVP_SeekerTargetFilter.allows(operator, interceptorCarrier, incoming));
        assertFalse(RVP_SeekerTargetFilter.allows(interceptor, incoming));
    }
    @Test void seekerCannotTargetItselfAndVehicleFriendlyFireRulesAreUnchanged() {
        Ammo a=ammo(null,null);
        assertFalse(RVP_SeekerTargetFilter.allows(a,a));
        assertTrue(RVP_SeekerTargetFilter.allows(a,carrier()),"本次只限制友方弹药，不扩散到载具友伤");
    }
    /** 仅输入归属关系，生产过滤器负责全部判定。 */
    private Ammo ammo(Entity owner,AbstractVehicle carrier) {
        Ammo a=new Ammo(level); a.owner=owner; a.carrier=carrier; return a;
    }
    private Carrier carrier() {
        Carrier carrier = environment(Carrier.class);
        carrier.setUUID(UUID.randomUUID());
        write(Entity.class, carrier, "passengers", ImmutableList.of());
        return carrier;
    }
    private static final class Ammo extends RVP_MissileEntity {
        /** 网络交接提供的射手。 */ Entity owner;
        /** 网络交接提供的发射载具。 */ AbstractVehicle carrier;
        Ammo(RVP_ProjectileSimulationLevel l) { super(RVP_Entities.RVP_MISSILE.get(),l); }
        @Override public Entity getOwner() { return owner; }
        @Override public AbstractVehicle getShooterVehicle() { return carrier; }
    }
    /** 无驾驶员和队伍的载具环境，可独立模拟其它座位的乘员。 */
    private static final class Carrier extends WheeledVehicle {
        private Carrier() { super(null,null); }
        @Override public LivingEntity getDriver() { return null; }
        @Override public Team getTeam() { return null; }
    }
}
