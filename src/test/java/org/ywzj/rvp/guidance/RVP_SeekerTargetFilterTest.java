package org.ywzj.rvp.guidance;

import net.minecraft.world.entity.*;
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
    @Test void seekerCannotTargetItselfAndVehicleFriendlyFireRulesAreUnchanged() {
        Ammo a=ammo(null,null);
        assertFalse(RVP_SeekerTargetFilter.allows(a,a));
        assertTrue(RVP_SeekerTargetFilter.allows(a,carrier()),"本次只限制友方弹药，不扩散到载具友伤");
    }
    /** 仅输入归属关系，生产过滤器负责全部判定。 */
    private Ammo ammo(Entity owner,AbstractVehicle carrier) {
        Ammo a=new Ammo(level); a.owner=owner; a.carrier=carrier; return a;
    }
    private Carrier carrier() { Carrier c=environment(Carrier.class); c.setUUID(UUID.randomUUID()); return c; }
    private static final class Ammo extends RVP_MissileEntity {
        /** 网络交接提供的射手。 */ Entity owner;
        /** 网络交接提供的发射载具。 */ AbstractVehicle carrier;
        Ammo(RVP_ProjectileSimulationLevel l) { super(RVP_Entities.RVP_MISSILE.get(),l); }
        @Override public Entity getOwner() { return owner; }
        @Override public AbstractVehicle getShooterVehicle() { return carrier; }
    }
    /** 无乘员和队伍的敌对/无主载具环境。 */
    private static final class Carrier extends WheeledVehicle {
        private Carrier() { super(null,null); }
        @Override public LivingEntity getDriver() { return null; }
        @Override public Team getTeam() { return null; }
    }
}
