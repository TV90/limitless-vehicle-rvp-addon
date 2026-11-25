package org.ywzj.rvp.entity.projectile;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Marker;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.ywzj.rvp.all.RVP_Entities;
import org.ywzj.rvp.dircm.RVP_DircmRuntimeManager;
import org.ywzj.rvp.ecm.RVP_EcmIff;
import org.ywzj.rvp.entity.gunner.GunnerEntity;
import org.ywzj.rvp.entity.gunner.ai.profile.RVP_EnumGunnerFaction;
import org.ywzj.rvp.guidance.RVP_OpticalTargeting;
import org.ywzj.rvp.hitl.RVP_HitlLockSnapshot;
import org.ywzj.rvp.server.hitl.RVP_HitlRadioPath;
import org.ywzj.rvp.weapon.data.RVP_EnumWeaponKind;
import org.ywzj.rvp.weapon.data.RVP_WeaponData;
import org.ywzj.vehicle.api.entity.SightObstruction;
import org.ywzj.vehicle.entity.misc.VehiclePart;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;
import org.ywzj.vehicle.entity.vehicle.WheeledVehicle;
import org.ywzj.vehicle.custom.serialize.GsonUtil;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class RVP_OpticalGameplayTest {
    @BeforeAll
    static void bootstrap() {
        RVP_ProjectileSimulationBootstrap.initialize();
    }

    @Test
    void consecutiveTvShotsRetainTheirOwnTargetsAndTrackThroughVideoLoss() {
        var level = RVP_ProjectileSimulationLevel.create(0, null);
        var first = vehicle(level, 0, 40, false);
        var second = vehicle(level, 4, 55, false);
        var a = new Missile(level, "TV");
        var b = new Missile(level, "TV");
        a.opticalTrack().designate(first, first.position());
        b.opticalTrack().designate(second, second.position());
        a.guide(5);
        b.guide(5);
        assertSame(first, a.getTargetEntity());
        assertSame(second, b.getTargetEntity());

        var carrierBelowTerrain = new Vec3(0, -10, -10);
        assertEquals(RVP_HitlRadioPath.Result.BLOCKED,
                RVP_HitlRadioPath.check(level, a, carrierBelowTerrain, a.position()));
        RVP_ProjectileSimulationBootstrap.write(RVP_MissileEntity.class, a, "hitlLinkBlocked", true);
        place(first, 2, 40);
        a.guide(6);
        assertSame(first, a.getTargetEntity());
        assertTrue(a.getTargetPos().x > 1.9);
        assertEquals(RVP_HitlLockSnapshot.EMPTY, a.rvp$getHitlLockSnapshot());
        assertSame(second, b.getTargetEntity());
        RVP_ProjectileSimulationBootstrap.write(RVP_MissileEntity.class, a, "hitlLinkBlocked", false);
        assertEquals(first.getId(), a.rvp$getHitlLockSnapshot().entityId());
    }

    @Test
    void partialAndCompleteSmokeLowerConfidenceAndCannotRetargetTv() {
        var level = RVP_ProjectileSimulationLevel.create(0, null);
        var target = vehicle(level, 0, 40, false);
        var distractor = vehicle(level, 4, 35, false);
        var a = new Missile(level, "TV");
        a.opticalTrack().designate(target, target.position());
        a.guide(5);
        assertSame(target, a.getTargetEntity());
        Vec3 lastVisible = a.getTargetPos();

        var smoke = new Smoke(level);
        level.addObserver(smoke);
        smoke.setBoundingBox(new AABB(-1.5, 9.05, 19, 1.5, 12, 21));
        var range = a.getRvpData().getGuidanceData().getGuidanceTargetDistanceRange();
        var view = new RVP_OpticalTargeting.View(a.position(), new Vec3(0, 0, 1));
        float partial = RVP_OpticalTargeting.sample(a, view, target, range, 30).confidence();
        assertTrue(partial > 0f && partial < 1f);

        smoke.setBoundingBox(new AABB(-1.5, 7, 19, 1.5, 12, 21));
        assertEquals(0f, RVP_OpticalTargeting.sample(a, view, target, range, 30).confidence());
        assertTrue(RVP_OpticalTargeting.sample(a, view, distractor, range, 30).confidence() > 0.65f);
        a.guide(6);
        assertNull(a.getTargetEntity());
        assertEquals(lastVisible, a.getTargetPos());
        assertEquals(RVP_HitlLockSnapshot.Status.OCCLUDED, a.rvp$getHitlLockSnapshot().status());
        a.guide(19);
        assertNull(a.getTargetPos());
        smoke.setBoundingBox(new AABB(-1.5, 7, -20, 1.5, 12, -19));
        a.guide(20);
        assertSame(target, a.getTargetEntity());
    }

    @Test
    void dircmClearsTvEntityAndGroundLocksWithoutAffectingTheOtherShot() throws ReflectiveOperationException {
        var level = RVP_ProjectileSimulationLevel.create(0, null);
        var target = vehicle(level, 0, 40, false);
        var entityShot = new Missile(level, "TV");
        var groundShot = new Missile(level, "TV");
        var point = new Vec3(8, 9, 45);
        entityShot.opticalTrack().designate(target, target.position());
        groundShot.opticalTrack().designate(null, point);
        entityShot.guide(5);
        groundShot.guide(5);
        assertSame(target, entityShot.getTargetEntity());
        assertEquals(point, groundShot.getTargetPos());

        // 调用通道命中后的真实干扰入口，验证每发弹的目标记忆互不影响。
        applyDircm(target, entityShot);
        assertEquals(point, groundShot.getTargetPos());
        applyDircm(target, groundShot);
        for (var missile : List.of(entityShot, groundShot)) {
            assertOpticalJam(missile);
            for (int tick = 6; tick < 65; tick++) {
                RVP_DircmRuntimeManager.tickJammedProjectile(missile);
                missile.guide(tick);
            }
            assertTrue(missile.dircmJammed);
            assertEquals(1, missile.dircmJamRemainTick);
            RVP_DircmRuntimeManager.tickJammedProjectile(missile);
            missile.guide(65);
            assertFalse(missile.dircmJammed);
            assertEquals(RVP_HitlLockSnapshot.EMPTY, missile.rvp$getHitlLockSnapshot());
            assertNull(missile.getTargetEntity());
            assertNull(missile.getTargetPos());
        }
    }

    @Test
    void atvSearchesAgainAfterTheDircmWhiteout() throws ReflectiveOperationException {
        var level = RVP_ProjectileSimulationLevel.create(0, null);
        var original = vehicle(level, 0, 26, false);
        var replacement = vehicle(level, 6, 35, false);
        var missile = new Missile(level, "ATV");
        missile.guide(5);
        assertSame(original, missile.getTargetEntity());
        applyDircm(original, missile);
        assertOpticalJam(missile);

        // 原目标退出观察范围后，恢复的 ATV 应能锁定当前可见的空车。
        place(original, 60, 0);
        for (int tick = 6; tick < 65; tick++) {
            RVP_DircmRuntimeManager.tickJammedProjectile(missile);
            missile.guide(tick);
        }
        assertTrue(missile.dircmJammed);
        assertNull(missile.getTargetEntity());
        RVP_DircmRuntimeManager.tickJammedProjectile(missile);
        missile.setDeltaMovement(0, 0, 1);
        missile.guide(65);
        assertSame(replacement, missile.getTargetEntity());
        assertEquals(replacement.getId(), missile.rvp$getHitlLockSnapshot().entityId());
    }

    private static void applyDircm(AbstractVehicle source, Missile missile) throws ReflectiveOperationException {
        var method = RVP_DircmRuntimeManager.class.getDeclaredMethod("applyJam", AbstractVehicle.class, RVP_BaseBullet.class);
        method.setAccessible(true);
        method.invoke(null, source, missile);
    }

    private static void assertOpticalJam(Missile missile) {
        assertTrue(missile.dircmJammed);
        assertTrue(missile.dircmHitlTemporary);
        assertEquals(60, missile.dircmJamRemainTick);
        assertEquals(60, RVP_DircmRuntimeManager.resolveHitlJamTotal(missile));
        assertEquals(0, missile.dircmSelfDestructTick);
        assertEquals(RVP_HitlLockSnapshot.Status.DIRCM, missile.rvp$getHitlLockSnapshot().status());
        assertEquals(-1, missile.rvp$getHitlLockSnapshot().entityId());
        assertNull(missile.getTargetEntity());
        assertNull(missile.getTargetPos());
    }

    @Test
    void atvAcquiresAnUnoccupiedVehicleWithoutAPrelaunchLock() {
        var level = RVP_ProjectileSimulationLevel.create(0, null);
        var missile = new Missile(level, "ATV");
        var neutral = vehicle(level, 0, 26, false);
        assertNull(missile.getTargetEntity());
        missile.guide(5);
        assertSame(neutral, missile.getTargetEntity());
    }

    @Test
    void atvSkipsFriendlyAiInTheGunnerSeat() {
        var level = RVP_ProjectileSimulationLevel.create(0, null);
        var carrier = vehicle(level, 0, 0, false);
        var missile = new Missile(level, "ATV", carrier);
        var friendly = vehicle(level, 0, 26, false);
        var crew = com.google.common.collect.ImmutableList.of(driver(RVP_EnumGunnerFaction.FRIENDLY));
        RVP_ProjectileSimulationBootstrap.write(Entity.class, friendly, "passengers", crew);
        missile.guide(5);
        assertNull(missile.getTargetEntity());
    }

    @Test
    void atvWithFriendlyAiPilotSkipsFriendlyAndSmokeButStillAcquiresEnemy() {
        var level = RVP_ProjectileSimulationLevel.create(0, null);
        var carrier = vehicle(level, 0, 0, false);
        var missile = new Missile(level, "ATV", carrier);
        carrier.driver = driver(RVP_EnumGunnerFaction.FRIENDLY);
        var friendly = vehicle(level, 0, 26, false);
        friendly.driver = driver(RVP_EnumGunnerFaction.FRIENDLY);
        var unseen = vehicle(level, -5, 40, false);
        unseen.driver = driver(RVP_EnumGunnerFaction.ENEMY);
        var visible = vehicle(level, 7, 45, false);
        visible.driver = driver(RVP_EnumGunnerFaction.ENEMY);
        assertTrue(RVP_EcmIff.areVehiclesFriendly(carrier, visible));
        var smoke = new Smoke(level);
        smoke.setBoundingBox(new AABB(-4, 6, 19, -1.1, 13, 21));
        level.addObserver(smoke);
        assertNull(missile.getTargetEntity());
        missile.guide(5);
        assertSame(visible, missile.getTargetEntity());
        assertNotSame(unseen, missile.getTargetEntity());
        assertNotSame(friendly, missile.getTargetEntity());
    }

    @Test
    void recognitionOmitsDetachedPartsAndMountedCrewButShowsDismountedCrew() {
        var level = RVP_ProjectileSimulationLevel.create(0, null);
        var observer = new Missile(level, "ATV");
        var vehicle = vehicle(level, 0, 40, false);
        var crew = driver(RVP_EnumGunnerFaction.ENEMY);
        RVP_ProjectileSimulationBootstrap.write(Entity.class, crew, "level", level);
        RVP_ProjectileSimulationBootstrap.write(Entity.class, crew, "vehicle", vehicle);
        crew.setUUID(UUID.randomUUID());
        RVP_ProjectileSimulationBootstrap.write(Entity.class, crew, "position", new Vec3(0, 8, 40));
        crew.setId(1_100_002);
        crew.setBoundingBox(new AABB(-0.4, 8, 39.7, 0.4, 9.8, 40.3));
        level.addObserver(crew);
        var fragment = RVP_ProjectileSimulationBootstrap.environment(TestFragment.class);
        RVP_ProjectileSimulationBootstrap.write(Entity.class, fragment, "level", level);
        fragment.setUUID(UUID.randomUUID());
        RVP_ProjectileSimulationBootstrap.write(Entity.class, fragment, "position", new Vec3(5, 8, 19));
        fragment.setId(1_100_003);
        fragment.setBoundingBox(new AABB(4, 8, 18, 6, 10, 20));
        level.addObserver(fragment);

        var view = new RVP_OpticalTargeting.View(observer.position(), new Vec3(0, 0, 1));
        var range = observer.getRvpData().getGuidanceData().getGuidanceTargetDistanceRange();
        var contacts = RVP_OpticalTargeting.scanDisplayed(observer, null, view, range, 30, entity -> 0);
        assertEquals(List.of(vehicle), contacts.stream().map(RVP_OpticalTargeting.Contact::entity).toList());
        assertFalse(RVP_OpticalTargeting.isCandidate(observer, null, fragment, true));
        observer.guide(5);
        assertSame(vehicle, observer.getTargetEntity());

        // 下车并走出车体遮挡后，人员应重新进入识别候选。
        RVP_ProjectileSimulationBootstrap.write(Entity.class, crew, "vehicle", null);
        crew.setBoundingBox(new AABB(5.6, 8, 31.7, 6.4, 9.8, 32.3));
        contacts = RVP_OpticalTargeting.scanDisplayed(observer, null, view, range, 30, entity -> 0);
        assertTrue(contacts.stream().anyMatch(contact -> contact.entity() == crew));
    }

    @Test
    void displayedScanUsesScreenPriorityAndChecksAtMostFifteenCandidates() {
        var level = RVP_ProjectileSimulationLevel.create(0, null);
        var observer = new Missile(level, "TV");
        var view = new RVP_OpticalTargeting.View(observer.position(), new Vec3(0, 0, 1));
        var vehicles = java.util.stream.IntStream.rangeClosed(0, 20)
                .mapToObj(i -> vehicle(level, (i - 10) * 4, 80 + i, false)).toList();
        var contacts = RVP_OpticalTargeting.scanDisplayed(observer, null, view,
                observer.getRvpData().getGuidanceData().getGuidanceTargetDistanceRange(), 50,
                entity -> entity == vehicles.get(20) ? Double.POSITIVE_INFINITY : 100 - entity.position().z);
        var expected = java.util.stream.IntStream.range(0, 15).mapToObj(i -> vehicles.get(19 - i)).toList();
        assertEquals(expected, contacts.stream().map(RVP_OpticalTargeting.Contact::entity).toList());
    }

    private static Vehicle vehicle(RVP_ProjectileSimulationLevel level, double x, double z, boolean occupied) {
        var target = RVP_ProjectileSimulationBootstrap.environment(Vehicle.class);
        RVP_ProjectileSimulationBootstrap.write(Entity.class, target, "level", level);
        RVP_ProjectileSimulationBootstrap.write(Entity.class, target, "passengers", com.google.common.collect.ImmutableList.of());
        place(target, x, z);
        RVP_ProjectileSimulationBootstrap.write(Entity.class, target, "dimensions", EntityDimensions.scalable(2, 2));
        target.setUUID(UUID.randomUUID());
        target.setId(1_000_000 + (int) z);
        if (occupied) target.driver = RVP_ProjectileSimulationBootstrap.environment(
                org.ywzj.rvp.entity.gunner.GunnerEntity.class);
        level.addObserver(target);
        return target;
    }

    private static TestGunner driver(RVP_EnumGunnerFaction faction) {
        TestGunner gunner = RVP_ProjectileSimulationBootstrap.environment(TestGunner.class);
        gunner.faction = faction;
        return gunner;
    }

    private static void place(Vehicle target, double x, double z) {
        RVP_ProjectileSimulationBootstrap.write(Entity.class, target, "position", new Vec3(x, 8, z));
        target.setBoundingBox(new AABB(x - 1, 8, z - 2, x + 1, 10, z + 2));
    }

    private static final class Vehicle extends WheeledVehicle {
        private LivingEntity driver;
        private Vehicle() { super(null, null); }
        @Override public LivingEntity getDriver() { return driver; }
        @Override public net.minecraft.world.scores.Team getTeam() { return null; }
        @Override public boolean isAlive() { return true; }
        @Override public ResourceLocation getVehicleId() { return ResourceLocation.tryParse("rvp:optical_target"); }
        @Override public List<org.ywzj.vehicle.vehicle.part.PartUnit<?>> getPartUnits() { return List.of(); }
        @Override public List<org.ywzj.vehicle.vehicle.structure.VehicleCubeOBB> getVehicleCubeOBBs() { return List.of(); }
        @Override public boolean isPickable() { return true; }
    }

    private static final class TestGunner extends GunnerEntity {
        private RVP_EnumGunnerFaction faction;
        private TestGunner() { super(null, null); }
        @Override public RVP_EnumGunnerFaction getProfileFaction() { return faction; }
        @Override public boolean isAlive() { return true; }
        @Override public boolean isPickable() { return true; }
    }

    private static final class TestFragment extends VehiclePart {
        private TestFragment() { super(null, null); }
        @Override public boolean isAlive() { return true; }
        @Override public boolean isPickable() { return true; }
        @Override public List<org.ywzj.vehicle.vehicle.structure.VehicleCubeOBB> getVehicleCubeOBBs() { return List.of(); }
    }

    private static final class Smoke extends Marker implements SightObstruction {
        private Smoke(RVP_ProjectileSimulationLevel level) { super(EntityType.MARKER, level); }
    }

    private static final class Missile extends RVP_MissileEntity {
        private Missile(RVP_ProjectileSimulationLevel level, String guidance) {
            this(level, guidance, null);
        }

        private Missile(RVP_ProjectileSimulationLevel level, String guidance, Vehicle carrier) {
            super(RVP_Entities.RVP_MISSILE.get(), level);
            var data = GsonUtil.GSON.fromJson("""
                    {"type":"rvp:missile","life":300,"projectile_data":{"velocity":1,"max_speed":4},
                     "guidance_data":{"guidance_type":"%s","guidance_target_distance_range":"[[0,120]]",
                     "max_guidance_angle":60,"recognition_lock_threshold":0.65,"recognition_hold_ticks":12,
                     "allow_missile_view":true,"enable_inertial_guidance":false}}
                    """.formatted(guidance), RVP_WeaponData.class);
            data.setWeaponKind(RVP_EnumWeaponKind.MISSILE);
            data.setWeaponId(ResourceLocation.tryParse("rvp:optical_probe"));
            initFromWeapon(data, RVP_EnumWeaponKind.MISSILE, carrier, null, new Vec3(0,9,0),
                    new AimRot(0, 0), new Vec3(0,0,1));
        }
        private void guide(int tick) {
            tickCount = tick;
            ((RVP_ProjectileSimulationLevel) level()).observeTick(tick);
            super.tickGuidance();
        }
    }
}
