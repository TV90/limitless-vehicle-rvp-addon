package org.ywzj.rvp.entity.projectile;

import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Marker;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.ywzj.rvp.guidance.RVP_EnumGuidanceType;
import org.ywzj.rvp.guidance.RVP_GuidanceIntent;
import org.ywzj.rvp.guidance.RVP_GuidanceRuntimeContext;
import org.ywzj.rvp.guidance.RVP_GuidanceRuntimeController;
import org.ywzj.rvp.guidance.RVP_RuntimeGuidanceSourceRegistry;
import org.ywzj.rvp.projectile.simulation.RVP_ProjectileSimulationScenario;
import org.ywzj.rvp.weapon.core.RVP_ProjectileEntityFactory;
import org.ywzj.rvp.weapon.data.RVP_EnumWeaponKind;
import org.ywzj.rvp.weapon.data.RVP_WeaponData;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;
import org.ywzj.vehicle.entity.vehicle.WheeledVehicle;
import org.ywzj.vehicle.util.VectorUtil;
import org.ywzj.vehicle.vehicle.part.WeaponUnit;
import org.ywzj.vehicle.vehicle.structure.OBB;
import org.ywzj.vehicle.vehicle.structure.VehicleCubeOBB;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/** 直接运行真实 RVP 弹体的制导执行器和运动方法；环境只提供场景观测，不实现第二套弹道。 */
public final class RVP_ProjectileEntitySimulation {
    /** 真实弹体，必须经生产工厂和生产构造器建立。 */ private final RVP_BaseBullet projectile;
    /** 只提供平地、水面和就绪查询的测试环境。 */ private final RVP_ProjectileSimulationLevel level;
    /** 原始场景初态。 */ private final RVP_ProjectileSimulationScenario scene;
    /** 可选脚本目标；生产预测拦截直接读取其 Entity 运动观测。 */ private final Marker target;
    /** 用于所有实体类型的真实动态分派运动入口。 */ private static final Method MOTION = method(RVP_BaseBullet.class, "tickMotion");
    /** 炮弹在实体 Tick 中使用的专属运动和姿态入口。 */ private static final Method CANNON = method(RVP_BulletEntity.class, "tickBulletMotionAndFacing");
    /** 智能引信已接管时使用的实体点制导入口。 */ private static final Method SMART = method(RVP_BaseBullet.class, "tickSmartFuseGuidance");

    /** 初始化真实实体，保留 initFromWeapon、平台速度叠加、冷发射和姿态收口的生产顺序。 */
    public RVP_ProjectileEntitySimulation(RVP_WeaponData data, RVP_EnumWeaponKind kind, RVP_ProjectileSimulationScenario scene) {
        this(data, kind, scene, null);
    }

    /** 回归可提供只隔离外部世界的实体子类，直接执行完整生产 Tick 作为对照。 */
    RVP_ProjectileEntitySimulation(RVP_WeaponData data, RVP_EnumWeaponKind kind, RVP_ProjectileSimulationScenario scene,
            java.util.function.BiFunction<EntityType<? extends net.minecraft.world.entity.projectile.Projectile>, Level, RVP_BaseBullet> referenceFactory) {
        scene.validate(); this.scene = scene;
        level = RVP_ProjectileSimulationLevel.create(scene.groundY, scene.waterY);
        RVP_ProjectileSimulationBootstrap.write(Level.class, level, "random", RandomSource.create(scene.seed));
        // 调用生产工厂，kind 对应真实导弹/火箭/炮弹/炸弹/鱼雷/布撒弹类，不按武器 ID 分支。
        projectile = referenceFactory == null ? RVP_ProjectileEntityFactory.create(kind, null, level, data)
                : referenceFactory.apply(RVP_ProjectileEntityFactory.entityTypeFor(kind), level);
        if (projectile == null) throw new IllegalArgumentException("无法创建弹体类型：" + kind);
        projectile.setUUID(UUID.nameUUIDFromBytes((scene.id + "/" + data.getWeaponId() + "/" + scene.seed).getBytes(StandardCharsets.UTF_8)));
        RVP_ProjectileSimulationBootstrap.write(Entity.class, projectile, "random", RandomSource.create(scene.seed));
        Vec3 launch = vector(scene.position);
        var aim = new RVP_BaseBullet.AimRot(-scene.pitch, scene.yaw);
        // 调用本体方向换算和生产出膛初速解析；随后由真实初始化执行高初速火箭等特殊规则。
        Vec3 initialMotion = scene.initialVelocity != null ? vector(scene.initialVelocity).scale(1.0 / 20)
                : VectorUtil.rotToVec(aim.xRot(), aim.yRot()).normalize().scale(Math.max(data.resolveMuzzleSpeed(kind), 0.01f));
        projectile.initFromWeapon(data, kind, null, null, launch, aim, initialMotion);
        if (scene.parentRotation != null) {
            // 母弹只提供释放瞬间观测；风向模式、竖直退化和垂直分量都交给生产捕获方法。
            var parent = RVP_ProjectileEntityFactory.create(RVP_EnumWeaponKind.ROCKET, null, level, data);
            parent.setYRot((float) scene.parentRotation[0]); parent.setXRot((float) -scene.parentRotation[1]);
            parent.setDeltaMovement(vector(scene.parentVelocity).scale(1.0 / 20));
            projectile.captureWindDirectionFromParent(parent);
        }
        if (scene.target != null) {
            target = new Marker(EntityType.MARKER, level); target.setUUID(new UUID(scene.seed, 1));
            updateTarget(0);
            // 调用生产目标装订，GPS 误差仍按真实配置与固定随机种子生成，不在工具里强制关闭。
            projectile.setTargetPos(vector(scene.target));
            if (data.getGuidanceData().getGuidanceType() != RVP_EnumGuidanceType.GPS) projectile.setTargetEntity(target);
        } else target = null;
        Vec3 inherited = data.isInheritVehicleVelocity() ? vector(scene.vehicleVelocity).scale(1.0 / 20) : Vec3.ZERO;
        projectile.captureLaunchVehicleVelocity(inherited);
        projectile.setDeltaMovement(projectile.getDeltaMovement().add(inherited));
        if (scene.coldLaunchTicks > 0) {
            // 环境提供固定的平台主轴；实际冷发射向量合成、点火交接和机头锁定仍由实体处理。
            AbstractVehicle carrier = carrier(launch, scene.carrierRotation);
            projectile.shooterVehicle = carrier; projectile.vehicle = carrier;
            WeaponUnit unit = RVP_ProjectileSimulationBootstrap.environment(WeaponUnit.class);
            RVP_ProjectileSimulationBootstrap.write(WeaponUnit.class, unit, "coldLaunchTimeTick", scene.coldLaunchTicks);
            RVP_ProjectileSimulationBootstrap.write(WeaponUnit.class, unit, "coldLaunchVelocity", vector(scene.coldLaunchVelocity).scale(1.0 / 20));
            projectile.initColdLaunch(unit);
        }
        if (scene.deploymentVelocity != null) {
            Vec3 deployment = vector(scene.deploymentVelocity).scale(1.0 / 20);
            projectile.setDeltaMovement(projectile.getDeltaMovement().add(deployment));
            // 调用真实子弹药部署初始化，独立水平/垂直半衰期和风偏继续由实体积分。
            projectile.initializeSubmunitionDeploymentMotion(deployment);
        }
        projectile.finalizeSpawnOrientation(aim);
        if (scene.smartFuseTarget != null) {
            projectile.smartFuseActive = true; projectile.smartFuseTargetPos = vector(scene.smartFuseTarget);
        }
        updateWaterState();
    }

    /** 真实实体只读出口，用于导出和直接数值对照；不能用历史峰值回填当前速度。 */
    public RVP_BaseBullet entity() { return projectile; }
    /** 当前脚本目标中心，供界面与最近距离统计使用。 */
    public Vec3 targetPosition() { return target == null ? null : target.getBoundingBox().getCenter(); }
    /** 当前真实实体运动状态，原样保留位置、速度和 Java 姿态角。 */
    public Snapshot snapshot() {
        var motor = RVP_ProjectileMotion.resolvePIPMotorState(projectile);
        String stage = !projectile.getRvpData().usesPropulsion() ? "无发动机"
                : projectile.getFlightTickCount() < RVP_ProjectileMotion.resolveMotorIgnitionTick(projectile, projectile.getRvpData()) ? "等待点火"
                : projectile.isSecondMotorPulseBurningNow() ? "第二脉冲燃烧" : motor.burning() ? "主发动机燃烧" : "发动机燃尽";
        return new Snapshot(projectile.position(), projectile.getDeltaMovement(), projectile.getXRot(), projectile.getYRot(),
                projectile.peakFlightSpeed, projectile.flightDistance, projectile.getFlightTickCount(), projectile.life,
                projectile.getAttackAngleState().pitchRateDeg(), projectile.getAttackAngleState().yawRateDeg(),
                stage, RVP_ProjectileMotion.resolveAttackAngleMass(projectile), motor.thrust(), projectile.getAeroLoadFactor(), projectile.isInWater(),
                projectile.getGuidancePhaseState().phase().name(), projectile.rvp$guidanceWireDirectApplied());
    }

    /** 执行真实实体的一次飞行步骤；世界碰撞、引信触发和网络不在此数值环境里伪造。 */
    public Snapshot step() {
        projectile.xRotO = projectile.getXRot(); projectile.yRotO = projectile.getYRot();
        projectile.tickCount++; projectile.updateCount++; observeEnvironment();
        // 调用生产单刻准备，零散标记的清理顺序和真实 Tick 共用，不复制气动状态机。
        if (!(projectile instanceof RVP_BulletEntity)) projectile.prepareKinematicGuidanceTick();
        executeGuidance();
        if (!(projectile instanceof RVP_BulletEntity) && !projectile.smartFuseActive) {
            // 调用实体攻角兜底，丢失观测时同样推进机头角速度；每刻只结算一次。
            RVP_ProjectileMotion.finishAttackAngleGuidance(projectile);
        }
        // 直接调用该对象的真实运动方法：包含弹种分派、风漂、恒速、二脉冲、HITL、水中和鱼雷状态机。
        invoke(projectile instanceof RVP_BulletEntity ? CANNON : MOTION, projectile);
        projectile.life--;
        return snapshot();
    }

    /** 完整实体 Tick 对照使用相同的外部环境观测，不能替换其运动或姿态方法。 */
    void observeEnvironment() {
        level.observeTick(projectile.tickCount);
        if (target != null) updateTarget(projectile.tickCount);
        updateWaterState();
    }

    /** 场景目标观测的统一入口，完整 Tick 和独立调度只共享这层外部输入。 */
    void executeGuidance() {
        if (projectile.smartFuseActive) invoke(SMART, projectile);
        else if (scene.guidance) {
            // 调用同一个生产制导控制器；只有目标观测来自场景，阶段、时间/包线门控和执行器均保持生产实现。
            RVP_GuidanceRuntimeController.tick(projectile, context -> target == null
                    ? RVP_GuidanceIntent.failed(context.active().guidanceType()) : observeTarget(context));
        }
    }

    /** 传入明确的目标意图；GPS/NONE 使用原源，其他源的搜索/遥控链路由场景理想观测替代。 */
    private RVP_GuidanceIntent observeTarget(RVP_GuidanceRuntimeContext context) {
        RVP_EnumGuidanceType type = context.active().guidanceType();
        if (type == RVP_EnumGuidanceType.GPS || type == RVP_EnumGuidanceType.NONE) {
            return RVP_RuntimeGuidanceSourceRegistry.get(type).evaluate(context);
        }
        boolean direct = type == RVP_EnumGuidanceType.HITL_CLOS_TV || type == RVP_EnumGuidanceType.LBR
                || type == RVP_EnumGuidanceType.SACLOS && !context.active().semiCorrectionEnabled();
        return direct ? RVP_GuidanceIntent.point(targetPosition(), true, 1, type)
                : RVP_GuidanceIntent.entity(target, false, 1, type);
    }

    /** 目标按显式脚本提供位置/速度；弹体的预测、响应补偿和攻角求解仍调用生产代码。 */
    private void updateTarget(int tick) {
        // 调用场景统一目标脚本，真实制导读取与编辑器一致的分段路线或恒加速度观测。
        var state = scene.targetAt(tick);
        Vec3 position = vector(state.position());
        target.setPos(position);
        target.setBoundingBox(new AABB(position, position).inflate(scene.targetRadius));
        target.setDeltaMovement(vector(state.velocity()).scale(1.0 / 20));
    }

    /** 水面是外部环境观测；实体水中重力、摩擦和鱼雷入水过渡不在工具中重算。 */
    private void updateWaterState() {
        boolean wet = scene.waterY != null && projectile.getBoundingBox().minY < scene.waterY && projectile.getBoundingBox().maxY > scene.groundY;
        RVP_ProjectileSimulationBootstrap.write(Entity.class, projectile, "wasTouchingWater", wet);
    }

    /** 给真实冷发射读取一个稳定的平台主 OBB；只分配环境载具，不运行它的驾驶或气动。 */
    private AbstractVehicle carrier(Vec3 position, double[] rotation) {
        AbstractVehicle carrier = RVP_ProjectileSimulationBootstrap.environment(WheeledVehicle.class);
        RVP_ProjectileSimulationBootstrap.write(Entity.class, carrier, "position", position);
        Quaternionf orientation = new Quaternionf().rotateY((float) Math.toRadians(-rotation[0]))
                .rotateX((float) Math.toRadians(rotation[1])).rotateZ((float) Math.toRadians(rotation[2]));
        RVP_ProjectileSimulationBootstrap.write(AbstractVehicle.class, carrier, "mainCubeOBB",
                new VehicleCubeOBB(new OBB(new Vector3f((float) position.x, (float) position.y, (float) position.z), new Vector3f(1), orientation)));
        return carrier;
    }

    /** 精确定位现有实体方法；签名变化时必须显式修复，禁止退回数学近似。 */
    private static Method method(Class<?> owner, String name) {
        try { Method method = owner.getDeclaredMethod(name); method.setAccessible(true); return method; }
        catch (ReflectiveOperationException error) { throw new IllegalStateException("实体飞行方法已变化：" + name, error); }
    }
    /** 反射只解决测试侧可见性，不改字节码、不增加 Mixin；异常保留生产堆栈。 */
    private static void invoke(Method method, RVP_BaseBullet projectile) {
        try { method.invoke(projectile); }
        catch (InvocationTargetException error) {
            if (error.getCause() instanceof RuntimeException cause) throw cause;
            throw new IllegalStateException("实体飞行失败：" + method.getName(), error.getCause());
        } catch (ReflectiveOperationException error) { throw new IllegalStateException(error); }
    }
    /** 场景数组转世界向量。 */ private static Vec3 vector(double[] value) { return new Vec3(value[0], value[1], value[2]); }

    /** 只读实体快照；此类不包含积分公式。 */
    public record Snapshot(
            /** 世界坐标，格。 */ Vec3 position, /** 世界速度，格/刻。 */ Vec3 velocity,
            /** 实体原始俯仰角，度。 */ float xRot, /** 实体原始航向角，度。 */ float yRot,
            /** 历史峰值速度，格/刻。 */ double peakFlightSpeed, /** 累计路程，格。 */ double flightDistance,
            /** 实体飞行刻。 */ int flightTick, /** 剩余寿命，刻。 */ int remainingLife,
            /** 俯仰角速度，度/刻。 */ double pitchRateDeg, /** 航向角速度，度/刻。 */ double yawRateDeg,
            /** 实体实际发动机阶段。 */ String motorStage, /** 本刻动力质量。 */ double mass,
            /** 本刻动力推力。 */ double thrust, /** 气动载荷使用率。 */ double aeroLoadFactor,
            /** 本刻环境水状态。 */ boolean inWater, /** 制导相位。 */ String phase,
            /** 本刻是否走真实直控运动。 */ boolean directMotion) {}
}
