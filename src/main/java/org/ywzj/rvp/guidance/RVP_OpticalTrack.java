package org.ywzj.rvp.guidance;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.ywzj.rvp.entity.projectile.RVP_MissileEntity;
import org.ywzj.rvp.hitl.RVP_HitlLockSnapshot;

import java.util.UUID;

/** 每发弹独立保存目标。视频状态不参与识别和转向。 */
public final class RVP_OpticalTrack {
    private UUID target;
    private Vec3 ground;
    private Vec3 lastSeen;
    private int lastSeenTick;
    private int nextScan;
    private RVP_HitlLockSnapshot snapshot = RVP_HitlLockSnapshot.EMPTY;

    public void designate(Entity entity, Vec3 point) {
        target = entity == null ? null : entity.getUUID();
        ground = entity == null ? point : null;
    }

    public RVP_GuidanceIntent evaluate(RVP_MissileEntity missile, RVP_GuidanceRuntimeContext context) {
        var type = context.active().guidanceType();
        if (!(missile.level() instanceof ServerLevel level)) return RVP_GuidanceIntent.failed(type);
        var data = context.data().getGuidanceData();
        boolean automatic = type == RVP_EnumGuidanceType.ATV;
        var distance = context.active().targetDistanceRange();
        var view = new RVP_OpticalTargeting.View(missile.position(), RVP_OpticalTargeting.forward(missile));
        double angle = context.active().maxGuidanceAngle() * 0.5;
        RVP_OpticalTargeting.Contact contact = null;
        if (!automatic && ground != null && RVP_OpticalTargeting.inView(view, ground, distance, angle)
                && RVP_OpticalTargeting.groundVisible(missile, view.origin(), ground)) {
            return track(missile, type, null, ground);
        }
        Entity entity = target == null ? null : level.getEntity(target);
        if (entity != null && RVP_OpticalTargeting.isCandidate(missile, missile.getShooterVehicle(), entity, automatic)) {
            var visible = RVP_OpticalTargeting.sample(missile, view, entity, distance, angle);
            if (visible.confidence() >= data.getRecognitionLockThreshold()) contact = visible;
        }
        boolean holding = lastSeen != null && missile.tickCount - lastSeenTick <= data.getRecognitionHoldTicks();
        if (contact == null && automatic && !holding && missile.tickCount >= nextScan) {
            nextScan = missile.tickCount + context.data().resolveGuidanceScanIntervalTick();
            var search = data.getSeekerSearchDistanceRange();
            contact = RVP_OpticalTargeting.scan(missile, missile.getShooterVehicle(), view,
                            search == null ? distance : search, angle, true).stream()
                    .filter(candidate -> candidate.confidence() >= data.getRecognitionLockThreshold())
                    .findFirst().orElse(null);
        }
        if (contact != null) return track(missile, type, contact.entity(), contact.point());

        if (missile.getTargetEntity() != null) missile.setTargetEntity(null);
        snapshot = lastSeen == null ? RVP_HitlLockSnapshot.EMPTY : new RVP_HitlLockSnapshot(
                RVP_HitlLockSnapshot.Status.OCCLUDED, -1, holding ? lastSeen : null, null, 0);
        if (holding) return RVP_GuidanceIntent.point(lastSeen, false, 1.0, type);
        missile.setGuidanceTargetPos(null);
        return RVP_GuidanceIntent.failed(type);
    }

    private RVP_GuidanceIntent track(RVP_MissileEntity missile, RVP_EnumGuidanceType type,
                                    Entity entity, Vec3 point) {
        if (entity != null) target = entity.getUUID();
        lastSeen = point;
        lastSeenTick = missile.tickCount;
        if (missile.getTargetEntity() != entity) missile.setTargetEntity(entity);
        snapshot = new RVP_HitlLockSnapshot(
                entity == null ? RVP_HitlLockSnapshot.Status.POINT : RVP_HitlLockSnapshot.Status.ENTITY,
                entity == null ? -1 : entity.getId(), point, null, 0);
        return RVP_GuidanceIntent.point(point, false, 1.0, type);
    }

    public RVP_HitlLockSnapshot snapshot() {
        return snapshot;
    }

    public void save(CompoundTag tag) {
        if (target != null) tag.putUUID("rvp_optical_target", target);
        if (ground != null) {
            tag.putDouble("rvp_optical_ground_x", ground.x);
            tag.putDouble("rvp_optical_ground_y", ground.y);
            tag.putDouble("rvp_optical_ground_z", ground.z);
        }
    }

    public void load(CompoundTag tag) {
        if (tag.hasUUID("rvp_optical_target")) target = tag.getUUID("rvp_optical_target");
        if (tag.contains("rvp_optical_ground_x")) {
            ground = new Vec3(tag.getDouble("rvp_optical_ground_x"), tag.getDouble("rvp_optical_ground_y"),
                    tag.getDouble("rvp_optical_ground_z"));
        }
    }
}
