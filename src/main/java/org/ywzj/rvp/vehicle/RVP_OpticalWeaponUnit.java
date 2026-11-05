package org.ywzj.rvp.vehicle;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.joml.Quaterniond;
import org.joml.Quaterniondc;
import org.joml.Quaternionf;
import org.ywzj.rvp.client.bridge.RVP_ClientActionsAccess;
import org.ywzj.rvp.config.RVP_CommonConfig;
import org.ywzj.rvp.event.RVP_TurretTraverseHandler;
import org.ywzj.vehicle.custom.part.data.WeaponUnitData;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;
import org.ywzj.vehicle.network.message.ClientVehicleAction;
import org.ywzj.vehicle.vehicle.part.WeaponUnit;

import java.util.UUID;

/** 新版吊舱的请求、真实运动和相机共用轨迹；普通 WeaponUnit 不接入。 */
public class RVP_OpticalWeaponUnit extends WeaponUnit {
    /** 从真实机械角延迟初始化，兼容结构建立及存档恢复。 */
    @Nullable private RVP_OpticalSightMotion motion;
    /** 当前世界方向请求；稳定器关闭时每刻转换到基座局部。 */
    @Nullable private Vec3 requestedDirection;
    /** 稳定器关闭时保存的基座局部方向，避免逐刻重算后仍错误地保持世界方向。 */
    @Nullable private Vec3 localRequestedDirection;
    /** 真实世界点请求；普通手动转动及未知距离方向锁为空。 */
    @Nullable private Vec3 requestedPoint;
    /** 本体 aim 写目标角时保留世界点语义。 */
    private boolean writingPoint;
    /** 最近一次镜像到本体目标字段的值，用于识别外部直接写入的新请求。 */
    private float mirroredPitch, mirroredYaw;
    /** 目标镜像是否已初始化。 */
    private boolean mirrored;
    /** 上一刻实际基座，用于在整刻内分配补偿预算。 */
    @Nullable private Quaterniond previousBase;
    /** 最近执行的可重放逻辑刻输入。 */
    @Nullable private RVP_OpticalSightMotion.Input lastInput;
    /** 当前合法操作者，换座后重置会话确认。 */
    @Nullable private UUID commandOwner;
    /** 当前专用控制会话。 */
    @Nullable private UUID commandSession;
    /** 服务端最后接受的输入序号。 */
    private long acceptedSequence = -1;
    /** 服务端最后收到合法输入的世界刻。 */
    private long lastCommandTick;
    /** 最近接受的权威状态刻。 */
    private long lastSnapshotTick = Long.MIN_VALUE;
    /** 服务端确定的加减速时间，单位秒。 */
    private double accelerationSeconds = 0.1;
    /** 网络校正的俯仰、偏航显示偏差，单位度。 */
    private double correctionPitch, correctionYaw;
    /** 校正起点的世界刻加刻内进度，显示采样不推进时间。 */
    private double correctionTime;

    /** 使用既有武器站数据，不新增或迁移载具 schema。 */
    public RVP_OpticalWeaponUnit(int index, AbstractVehicle vehicle, WeaponUnitData data) { super(index, vehicle, data); }

    /** 只执行一次 RVP 运动，本体 tick 继续处理其他生命周期与结构刷新。 */
    @Override
    protected void tickRot() {
        RVP_OpticalSightMotion state = motion();
        xRotO = xRot; yRotO = yRot;
        Quaterniond base = base(1);
        if (requestedDirection == null) requestedDirection = worldVec(xAimRot + xBarrelSelfRot, yAimRot + yBarrelSelfRot);
        Vec3 direction = requestedDirection;
        if ((!withStabilizer() || getOwner() == null) && requestedPoint == null) {
            if (localRequestedDirection == null) localRequestedDirection = RVP_OpticalSightMotion.direction(xAimRot + xBarrelSelfRot, yAimRot + yBarrelSelfRot);
            // 稳定器关闭时请求随基座转动，不能在下一刻又反向补偿同一世界方向。
            direction = RVP_OpticalSightMotion.transform(base, localRequestedDirection).normalize();
            requestedDirection = direction;
        }
        if (requestedPoint != null && vehicle != null) {
            // 调用现有观瞄挂点，世界点从镜头而非玩家眼睛或炮口求方向。
            Vec3 offset = requestedPoint.subtract(sightPosition(1));
            if (offset.lengthSqr() > 1.0E-12) direction = offset.normalize();
        }
        requestedDirection = direction;
        if (vehicle != null && !vehicle.level().isClientSide()) {
            // 调用服务端配置，快照会把同一加减速时间交给客户端预测。
            accelerationSeconds = RVP_CommonConfig.opticalSightAccelerationSeconds();
            if (getOwner() == null || !getOwner().getUUID().equals(commandOwner)) {
                commandOwner = getOwner() == null ? null : getOwner().getUUID();
                commandSession = null; acceptedSequence = -1;
                // 操作者离开或更换后只保留真实机械初态，不能继续执行上一乘员的世界点。
                requestedPoint = null;
                xAimRot = xRot; yAimRot = yRot;
                updateWorldAimVec();
                direction = requestedDirection;
            }
        }
        lastInput = new RVP_OpticalSightMotion.Input(previousBase == null ? base : previousBase, base,
                direction, limits(), withStabilizer() && getOwner() != null, !isDestroyed() && (!needPower || vehicle != null && vehicle.hasPower()));
        // 调用共享规划，机体补偿与鼠标请求共同消耗一份轴速预算。
        state.advance(lastInput);
        previousBase = new Quaterniond(base);
        copyActual();
        if (vehicle != null && !vehicle.level().isClientSide()) {
            long tick = vehicle.level().getGameTime();
            if (tick % 2 == 0 && (state.phase() != RVP_OpticalSightMotion.Phase.IDLE || tick % 20 == 0)) {
                var message = new org.ywzj.rvp.network.S2COpticalSightState(vehicle.level().dimension().location(), vehicle.getId(), vehicle.getUUID(), getIndex(), tick,
                        commandSession, acceptedSequence, accelerationSeconds, state.snapshot(), lastInput.baseBefore(), lastInput.baseAfter(), lastInput.stabilized(),
                        lastInput.direction(), requestedPoint);
                // 调用既有追踪分发；UAV 等远端操作者另收一份，重复状态按 tick 去重。
                org.ywzj.rvp.network.RVP_Network.CHANNEL.send(net.minecraftforge.network.PacketDistributor.TRACKING_ENTITY.with(() -> vehicle), message);
                if (getOwner() instanceof net.minecraft.server.level.ServerPlayer owner) {
                    org.ywzj.rvp.network.RVP_Network.CHANNEL.send(net.minecraftforge.network.PacketDistributor.PLAYER.with(() -> owner), message);
                }
            }
        }
    }

    /** 专用快照生效后不再用本体远端目标角覆盖真实轨迹。 */
    @Override
    protected void tickRemoteRot() {
        if (vehicle == null || lastSnapshotTick != Long.MIN_VALUE || RVP_ClientActionsAccess.isLocalOpticalOperator(this)) return;
        xAimRot = xRemoteAimRot; yAimRot = yRemoteAimRot;
        // 调用首次请求初始化，首份快照到达前仍能使用本体初态。
        updateWorldAimVec();
    }

    /** 接住既有火控目标更新，摆脱本体私有稳定器缓存。 */
    @Override
    public void updateWorldAimVec() {
        if (!writingPoint) requestedPoint = null;
        // 调用双精度方向，固定偏角与实际相机一致。
        requestedDirection = worldVec(xAimRot + xBarrelSelfRot, yAimRot + yBarrelSelfRot);
        localRequestedDirection = RVP_OpticalSightMotion.direction(xAimRot + xBarrelSelfRot, yAimRot + yBarrelSelfRot);
        mirroredPitch = xAimRot; mirroredYaw = yAimRot; mirrored = true;
    }

    /** 保留本体子武器瞄准转发，本站保存真实世界点。 */
    @Override
    public void aim(Vec3 worldPos) {
        Vec3 directionOnly = vehicle != null && worldPos != null && worldPos.equals(getFocusLockPos())
                ? RVP_ClientActionsAccess.opticalDirectionFocus(this, sightPosition(1)) : null;
        if (rotByAim && finite(worldPos)) requestedPoint = worldPos;
        writingPoint = true;
        try {
            // 调用本体火控副作用和子站转发，实际转动仅由 tickRot 执行。
            super.aim(worldPos);
        } finally { writingPoint = false; }
        if (directionOnly != null) {
            // 调用显式方向请求，不把位置代理锁成空气中的世界点。
            request(directionOnly, null);
        }
    }

    /** 公开解角保持当前分支，有限区间的真实路线另由运动规划负责。 */
    @Override
    public Vec2 worldVecToLocalRot(Vec3 direction) {
        // 调用当前基座逆变换，不假定飞机水平。
        return RVP_OpticalSightMath.localAngles(worldVecToLocalVec(direction), getXRot(), getYRot(),
                xRotMin - xSelfRot, xRotMax - xSelfRot, yRotMin - ySelfRot, yRotMax - ySelfRot);
    }

    /** 双精度方向避免高倍镜及极点附近的角度查表台阶。 */
    @Override
    public Vec3 worldVec(float pitch, float yaw) {
        // 调用本体基座，继续使用资产的固定偏角和父级关系。
        return RVP_OpticalSightMotion.transform(new Quaterniond(baseRot()), RVP_OpticalSightMotion.direction(pitch, yaw));
    }

    /** 完整画面轴输入只写请求，越界请求就地截断。 */
    public Quaterniond turnInView(Quaterniondc view, double yawDelta, double pitchDelta) {
        if (mirrored && (xAimRot != mirroredPitch || yAimRot != mirroredYaw)) {
            // 调用外部目标接续，自动火控和首次接管后不能沿用旧请求缓存。
            updateWorldAimVec();
        }
        Vec3 from = requestedDirection == null ? worldVec(xAimRot + xBarrelSelfRot, yAimRot + yBarrelSelfRot) : requestedDirection;
        // 调用完整姿态累加，同刻多次输入不从旧实际角重新开始。
        Quaterniond rotated = RVP_OpticalSightMath.turnInView(view, from, yawDelta, pitchDelta);
        Vec3 direction = RVP_OpticalSightMotion.inverse(new Quaterniond(baseRot()), RVP_OpticalSightMath.direction(rotated));
        // 调用连续分支解角限制手动请求，不能为躲止挡把目标角翻半圈。
        Vec2 local = RVP_OpticalSightMath.localAngles(direction,
                getXAimRot() + xBarrelSelfRot, getYAimRot() + yBarrelSelfRot,
                xRotMin - xSelfRot + xBarrelSelfRot, xRotMax - xSelfRot + xBarrelSelfRot,
                yRotMin - ySelfRot + yBarrelSelfRot, yRotMax - ySelfRot + yBarrelSelfRot);
        xAimRot = (float) clamp(local.x - xBarrelSelfRot, xRotMin - xSelfRot, xRotMax - xSelfRot);
        yAimRot = (float) clamp(local.y - yBarrelSelfRot, yRotMin - ySelfRot, yRotMax - ySelfRot);
        // 调用请求刷新，清点锁语义，反向输入不积压越界角。
        updateWorldAimVec();
        return RVP_OpticalSightMath.followDirection(rotated, requestedDirection);
    }

    /** 焦点提交方向或真实世界点，不直接改显示镜头。 */
    public void request(Vec3 direction, @Nullable Vec3 point) {
        if (!finite(direction) || !Double.isFinite(direction.lengthSqr()) || direction.lengthSqr() < 1.0E-12
                || point != null && !finite(point)) return;
        requestedDirection = direction.normalize(); requestedPoint = point;
        // 保存局部请求供关闭稳定器时接续，世界点模式仍按镜头位置逐刻求方向。
        localRequestedDirection = RVP_OpticalSightMotion.inverse(base(1), requestedDirection).normalize();
        // 调用解角镜像到本体目标字段，兼容目标圈和既有武器读取。
        Vec2 angles = worldVecToLocalRot(direction);
        xAimRot = angles.x - xBarrelSelfRot; yAimRot = angles.y - yBarrelSelfRot;
        mirroredPitch = xAimRot; mirroredYaw = yAimRot; mirrored = true;
    }

    /** 模型与相机共用刻内俯仰。 */
    @Override
    public float getViewXRot(float partial) {
        return motion == null ? super.getViewXRot(partial) : (float) clamp(
                motion.pitch(partial) - xBarrelSelfRot + correctionPitch * correction(partial), xRotMin - xSelfRot, xRotMax - xSelfRot);
    }

    /** 模型与相机共用刻内偏航。 */
    @Override
    public float getViewYRot(float partial) {
        return motion == null ? super.getViewYRot(partial) : (float) clamp(
                motion.yaw(partial) - yBarrelSelfRot + correctionYaw * correction(partial), yRotMin - ySelfRot, yRotMax - ySelfRot);
    }

    /** 本体在 partial=1 时直接读结构角；新版所有采样点都要包含同一份预测显示校正。 */
    @Override
    protected Quaternionf getViewGroupRotation(org.ywzj.vehicle.vehicle.structure.VehicleCubeGroup group, float partial) {
        if (group == structureGroup) {
            Quaternionf rotation = new Quaternionf(group.baseRotation).rotateY((float) Math.toRadians(-getViewYRot(partial)));
            if (xTurnGroup == structureGroup) rotation.rotateX((float) Math.toRadians(getViewXRot(partial)));
            return rotation;
        }
        if (group == xTurnGroup) return new Quaternionf(group.baseRotation).rotateX((float) Math.toRadians(getViewXRot(partial)));
        // 调用本体其他骨骼采样，父级和非观瞄部件继续保持原有行为。
        return super.getViewGroupRotation(group, partial);
    }

    /** 无副作用的真实视线和连续横轴采样。 */
    public Quaterniond viewRotation(float partial) {
        RVP_OpticalSightMotion state = motion();
        Quaterniond actual = base(partial).rotateY(Math.toRadians(-getViewYRot(partial) - yBarrelSelfRot))
                .rotateX(Math.toRadians(getViewXRot(partial) + xBarrelSelfRot));
        // 调用真实视线对齐，微段插值后的模型与相机也完全同向。
        return withStabilizer() ? RVP_OpticalSightMath.followDirection(state.view(partial), RVP_OpticalSightMath.direction(actual)) : actual;
    }

    /** 本体逻辑相机也读取共用完整姿态的横滚。 */
    @Override
    public float worldZRot() {
        Vec2 direction = worldRot(getXRot() + xBarrelSelfRot, getYRot() + yBarrelSelfRot);
        // 调用同姿态横滚解析，不能混用另一套稳定器状态。
        Quaterniond view = lastInput == null ? new Quaterniond(baseRot()).rotateY(Math.toRadians(-yRot - yBarrelSelfRot))
                .rotateX(Math.toRadians(xRot + xBarrelSelfRot)) : viewRotation(1);
        return RVP_OpticalSightMath.roll(new Quaternionf(view), direction.x, direction.y);
    }

    /** 专用会话有效时，原生角度包只保留发射用途。 */
    @Override
    public void onClientMessageReceived(ClientVehicleAction message, Player player) {
        if (message.shoot || commandSession == null || vehicle.level().getGameTime() - lastCommandTick > 20) {
            // 调用本体发射或首次角度初始化，其他普通站不经过本覆写。
            super.onClientMessageReceived(message, player);
        }
    }

    /** 验证操作者、会话与序号后接收绝对请求。 */
    public boolean acceptCommand(Player player, UUID session, long sequence, Vec3 direction, @Nullable Vec3 point) {
        if (vehicle.getOwnOperatorUnit(player) != this || getOwner() != player || sequence < 0
                || !finite(direction) || !Double.isFinite(direction.lengthSqr()) || Math.abs(direction.lengthSqr() - 1) > 1.0E-4
                || point != null && (!finite(point) || point.distanceToSqr(vehicle.position()) > 2.5E9)) return false;
        if (!player.getUUID().equals(commandOwner)) {
            commandOwner = player.getUUID(); commandSession = null; acceptedSequence = -1;
        }
        if (commandSession != null && !commandSession.equals(session) || sequence <= acceptedSequence) return false;
        commandSession = session; acceptedSequence = sequence; lastCommandTick = vehicle.level().getGameTime();
        // 调用统一请求，服务端自行执行机械约束，绝不相信客户端实际角。
        request(!withStabilizer() && point == null ? RVP_OpticalSightMotion.transform(base(1), direction) : direction, point);
        return true;
    }

    /** 恢复权威轨迹并重放未确认输入，校正后统一更新真实角与结构。 */
    public void restoreMotion(RVP_OpticalSightMotion.Snapshot snapshot, Iterable<RVP_OpticalSightMotion.Input> replay,
                              long serverTick, double seconds, boolean smooth, float partial) {
        if (serverTick <= lastSnapshotTick) return;
        double oldPitch = getViewXRot(partial), oldYaw = getViewYRot(partial);
        motion().restore(snapshot); accelerationSeconds = seconds;
        for (var input : replay) {
            // 调用同一纯运动计算，补回确认包之后本地执行过的预测。
            var l = input.limits();
            motion.advance(new RVP_OpticalSightMotion.Input(input.baseBefore(), input.baseAfter(), input.direction(),
                    new RVP_OpticalSightMotion.Limits(l.minPitch(), l.maxPitch(), l.minYaw(), l.maxYaw(), l.pitchSpeed(), l.yawSpeed(), seconds),
                    input.stabilized(), input.powered()));
        }
        lastSnapshotTick = serverTick; copyActual();
        xRotO = (float) (motion.pitch(0) - xBarrelSelfRot);
        yRotO = (float) (motion.yaw(0) - yBarrelSelfRot);
        previousBase = base(1);
        correctionPitch = smooth ? oldPitch - (motion.pitch(partial) - xBarrelSelfRot) : 0;
        correctionYaw = smooth ? oldYaw - (motion.yaw(partial) - yBarrelSelfRot) : 0;
        correctionTime = vehicle.level().getGameTime() + partial;
        // 调用结构刷新，发射与受击盒读取新姿态，显示仍共享同一校正轨迹。
        updateRot();
    }

    /** 存档恢复不携带另一会话的控制权和队列。 */
    @Override
    public void deserializeNBT(CompoundTag nbt) {
        // 调用本体武器、弹药及真实机械角恢复。
        super.deserializeNBT(nbt);
        motion = null; previousBase = null; requestedDirection = requestedPoint = localRequestedDirection = null;
        commandOwner = commandSession = null; acceptedSequence = -1; lastSnapshotTick = Long.MIN_VALUE;
        correctionPitch = correctionYaw = 0;
    }

    /** 返回可同步的实际轨迹。 */
    public RVP_OpticalSightMotion.Snapshot motionSnapshot() { return motion().snapshot(); }
    /** 返回最近执行的一刻输入。 */
    @Nullable public RVP_OpticalSightMotion.Input lastMotionInput() { return lastInput; }
    /** 返回世界点，方向锁为空。 */
    @Nullable public Vec3 requestedPoint() { return requestedPoint; }
    /** 返回请求世界方向。 */
    public Vec3 requestedDirection() { return requestedDirection == null ? worldVec() : requestedDirection; }
    /** 返回服务端确认序号。 */
    public long acceptedSequence() { return acceptedSequence; }
    /** 返回服务端控制会话。 */
    @Nullable public UUID commandSession() { return commandSession; }
    /** 返回执行阶段。 */
    public RVP_OpticalSightMotion.Phase motionPhase() { return motion == null ? RVP_OpticalSightMotion.Phase.IDLE : motion.phase(); }
    /** 返回服务端采用的加减速时间。 */
    public double accelerationSeconds() { return accelerationSeconds; }
    /** 在修改请求和预测队列前先拒绝迟到/重复快照，避免旧目标单独覆盖新目标。 */
    public boolean acceptsSnapshot(long tick) { return tick > lastSnapshotTick; }

    /** 只从模型真实角初始化，不依赖客户端类型。 */
    private RVP_OpticalSightMotion motion() {
        if (motion == null) {
            motion = new RVP_OpticalSightMotion();
            Quaterniond initial = base(1).rotateY(Math.toRadians(-yRot - yBarrelSelfRot)).rotateX(Math.toRadians(xRot + xBarrelSelfRot));
            motion.reset(xRot + xBarrelSelfRot, yRot + yBarrelSelfRot, initial);
        }
        return motion;
    }
    /** 无窗口测试可直接覆写本体 baseRot，不必伪造世界。 */
    private Quaterniond base(float partial) {
        return new Quaterniond(vehicle == null ? baseRot() : RVP_TurretTraverseHandler.sightBaseRotation(this, partial));
    }
    /** 与本体 cameraPosition 的 SCOPE 分支一致，OPERATOR 型使用操作员视点。 */
    private Vec3 sightPosition(float partial) {
        // 调用现有两类挂点，保留 offset、pivot 和俯仰跟随配置。
        return getOpticalSightType() == WeaponUnitData.OpticalSightType.OPERATOR
                ? worldOwnerViewPosition(partial) : worldOpticalSightPosition(partial);
    }
    /** 约束保留结构偏角和受损后真实转速。 */
    private RVP_OpticalSightMotion.Limits limits() {
        return new RVP_OpticalSightMotion.Limits(xRotMin - xSelfRot + xBarrelSelfRot, xRotMax - xSelfRot + xBarrelSelfRot,
                yRotMin - ySelfRot + yBarrelSelfRot, yRotMax - ySelfRot + yBarrelSelfRot,
                Math.max(0, xRotSpeed), Math.max(0, yRotSpeed), accelerationSeconds > 0 ? accelerationSeconds : 0.1);
    }
    /** 实际角只有运动规划写入，目标字段继续表示请求。 */
    private void copyActual() {
        xRot = (float) (motion.pitch(1) - xBarrelSelfRot); yRot = (float) (motion.yaw(1) - yBarrelSelfRot);
        if (requestedDirection != null) {
            // 调用当前基座反解镜像：机体转动后目标圈也必须仍表示同一个请求世界方向。
            Vec2 local = worldVecToLocalRot(requestedDirection);
            xAimRot = local.x - xBarrelSelfRot; yAimRot = local.y - yBarrelSelfRot;
            mirroredPitch = xAimRot; mirroredYaw = yAimRot; mirrored = true;
        }
    }
    /** 按世界刻取样，重复渲染不改变校正速度。 */
    private double correction(float partial) {
        return vehicle == null ? 0 : Math.exp(-Math.max(0, vehicle.level().getGameTime() + partial - correctionTime) / 2.0);
    }
    private static boolean finite(Vec3 v) { return v != null && Double.isFinite(v.x) && Double.isFinite(v.y) && Double.isFinite(v.z); }
    private static double clamp(double value, double min, double max) { return Math.max(min, Math.min(max, value)); }
}
