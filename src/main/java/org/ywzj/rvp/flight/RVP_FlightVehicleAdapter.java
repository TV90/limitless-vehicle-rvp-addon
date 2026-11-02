package org.ywzj.rvp.flight;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import org.ywzj.rvp.flight.RVP_FlightTypes.*;
import org.ywzj.vehicle.entity.vehicle.FixedWingVehicle;
import org.ywzj.vehicle.vehicle.control.ControlUnit;
import java.util.function.LongPredicate;

/** 本体固定翼的只读观察与执行端口；公共控制器无需接触游戏对象。 */
final class RVP_FlightVehicleAdapter implements RVP_FlightExecutor.Port {
    /** 本刻实体，不保存到控制器状态。 */
    private final FixedWingVehicle vehicle;
    /** 所在服务器本刻编号。 */
    private final long tick;
    /** 校验会话代次与持有者资格。 */
    private final LongPredicate ownership;

    RVP_FlightVehicleAdapter(FixedWingVehicle vehicle, long tick, LongPredicate ownership) {
        this.vehicle = vehicle; this.tick = tick; this.ownership = ownership;
    }

    @Override public java.util.UUID vehicleId() { return vehicle.getUUID(); }
    @Override public String dimension() { return vehicle.level().dimension().location().toString(); }
    @Override public long tick() { return tick; }
    @Override public boolean available() { return vehicle.isAlive() && !vehicle.isRemoved() && !vehicle.isDestroyed() && vehicle.controlUnit != null; }
    @Override public boolean owns(long generation) { return ownership.test(generation); }
    @Override public boolean onGround() {
        try { return vehicle.level().getBlockState(vehicle.blockPosition().below()).isSolid(); }
        catch (RuntimeException ignored) { return vehicle.onGround(); }
    }
    @Override public double pitchStep() { return vehicle.xRotInputStep; }
    @Override public double yawStep() { return vehicle.yRotInputStep; }
    @Override public double rollStep() { return vehicle.zRotInputStep; }
    @Override public double maximumThrottle() {
        // 调用公共修正，缺失加力系数时仍可使用普通油门。
        return RVP_FlightMath.bounded(Math.max(100, 100 * vehicle.thrustK), 100, 0, 1000);
    }
    @Override public boolean hasLandingGear() { return vehicle.landingGear != null; }
    @Override public boolean hasAirbrake() { return vehicle.airbrakeUnit != null; }

    /** 本体数据转换成不含实体引用的参数；发动机当前功率影响可用推力。 */
    Airframe airframe() {
        double scale = vehicle.getY() < 64 ? 1 : Math.sqrt(Math.max(0, vehicle.ceiling - vehicle.getY())
                / Math.max(1, vehicle.ceiling - 64));
        scale = Math.max(0.001, scale);
        double response = scale * vehicle.getDeltaMovement().length() * vehicle.turnRateBySpeed;
        // 调用本体公开功率查询，以真实可用推力参与油门规划，不补油也不启动发动机。
        return new Airframe(vehicle.physicsEngine.physicsInfo.mass,
                vehicle.thrust * vehicle.getPower() / 100.0, vehicle.airDragKMin, vehicle.airDragKMax,
                vehicle.liftToDragK, scale, vehicle.angleOfAttackMin, vehicle.angleOfAttackMax,
                Math.min(vehicle.xTurnRate, response * vehicle.xTurnRate),
                Math.min(vehicle.yTurnRate, response * vehicle.yTurnRate),
                Math.min(vehicle.zTurnRate, response * vehicle.zTurnRate), maximumThrottle());
    }

    /** 有限采样当前和前方两个方向；只有已加载区块才查询高度。 */
    Observation observe(long generation, RVP_FlightRequest request, RVP_FlightTuning tuning, Observation previous, boolean sampleTerrain) {
        ServerLevel level = (ServerLevel) vehicle.level();
        Vec3 motion = vehicle.getDeltaMovement();
        Vector position = vector(vehicle.position());
        Vector velocity = vector(motion.scale(20));
        BlockPos current = vehicle.blockPosition();
        boolean currentKnown = sampleTerrain && level.hasChunkAt(current);
        double ground = currentKnown ? level.getHeight(Heightmap.Types.WORLD_SURFACE, current.getX(), current.getZ()) : 0;
        double highest = ground;
        boolean aheadKnown = currentKnown;
        double distance = Math.min(256, Math.max(24, velocity.horizontalLength() * tuning.terrainLookAheadSeconds()));
        Vec3 direction = motion.horizontalDistanceSqr() > 0.01 ? new Vec3(motion.x, 0, motion.z).normalize()
                : new Vec3(-Math.sin(Math.toRadians(vehicle.getYRot())), 0, Math.cos(Math.toRadians(vehicle.getYRot())));
        Vector destination = request.goal() instanceof RVP_FlightRequest.FlyTo fly ? fly.point()
                : request.goal() instanceof RVP_FlightRequest.TrackPoint track ? track.point() : position;
        Vector delta = destination.subtract(position);
        Vec3 planned = delta.horizontalLength() > 1 ? new Vec3(delta.x(), 0, delta.z()).normalize() : direction;
        if (sampleTerrain) for (Vec3 ray : new Vec3[]{direction, planned}) {
            for (int i = 1; i <= 4; i++) {
                BlockPos sample = BlockPos.containing(vehicle.position().add(ray.scale(distance * i / 4.0)));
                if (!level.hasChunkAt(sample)) { aheadKnown = false; continue; }
                highest = Math.max(highest, level.getHeight(Heightmap.Types.WORLD_SURFACE, sample.getX(), sample.getZ()));
            }
        }
        double bankRate = previous != null && tick > previous.tick() && tick - previous.tick() <= 3
                ? RVP_FlightMath.wrap(vehicle.getZRot() - previous.bankRightDeg()) * 20 / (tick - previous.tick()) : 0;
        return new Observation(vehicleId(), dimension(), tick, generation, position, velocity,
                vehicle.getYRot(), -vehicle.getXRot(), vehicle.getZRot(), bankRate, vehicle.getThrottleLevel(),
                onGround(), new Terrain(ground, highest, currentKnown, aheadKnown));
    }

    /** 按本体约定一次写入运动字段，完整保留功能/武器字段。 */
    @Override public void write(RVP_FlightExecutor.Frame frame) {
        ControlUnit control = vehicle.controlUnit;
        control.forward = false; control.backward = false;
        control.up = true; control.down = false;
        control.left = true; control.right = false;
        control.leftYaw = frame.leftYaw(); control.rightYaw = frame.rightYaw();
        control.xRot = vehicle.getXRot(); control.xRotKeep = true;
        control.yRot = vehicle.getYRot(); control.yRotKeep = true;
        // 调用本体公开setter交付已补偿的三轴和明确油门，随后仍由本体tickMove消费。
        vehicle.setPitchInput((float) frame.pitchSetter());
        vehicle.setYawInput((float) frame.yawSetter());
        vehicle.setRollInput((float) frame.rollSetter());
        vehicle.setThrottleLevel((float) frame.throttle());
        // 可选部件缺失时只跳过该项；运动输入已经交付，不能因附件中断整条请求。
        if (frame.landingGear() != Switch.KEEP && vehicle.landingGear != null) vehicle.landingGear.setOn(frame.landingGear() == Switch.ON);
        if (frame.airbrake() != Switch.KEEP && vehicle.airbrakeUnit != null) vehicle.airbrakeUnit.setOn(frame.airbrake() == Switch.ON);
    }

    /** 交还时清连续杆量和方向键，不把目标角度归零，也不重置功能输入或油门。 */
    @Override public void clear() {
        ControlUnit control = vehicle.controlUnit;
        control.forward = false; control.backward = false; control.up = false; control.down = false;
        control.left = false; control.right = false; control.leftYaw = false; control.rightYaw = false;
        // 调用公共修正，致命数值状态的清理过程同样不能把NaN扩散到控制字段。
        control.xRot = (float) RVP_FlightMath.bounded(vehicle.getXRot(), 0, -180, 180); control.xRotKeep = true;
        control.yRot = (float) RVP_FlightMath.bounded(vehicle.getYRot(), 0, -Float.MAX_VALUE, Float.MAX_VALUE); control.yRotKeep = true;
        // 调用本体公开setter清除补偿残留，避免交还后继续使用上个业务的模拟杆量。
        vehicle.setPitchInput(0); vehicle.setYawInput(0); vehicle.setRollInput(0);
    }

    /** 游戏向量只在适配边界转换为公共值对象。 */
    static Vector vector(Vec3 value) { return new Vector(value.x, value.y, value.z); }
}
