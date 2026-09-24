package org.ywzj.rvp.client.state;

import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.ywzj.rvp.client.lead.RVP_MachinegunLeadSolver;
import org.ywzj.rvp.util.RVP_WeaponResolveHelper;
import org.ywzj.rvp.weapon.core.RVP_WeaponBase;
import org.ywzj.vehicle.util.VectorUtil;
import org.ywzj.vehicle.vehicle.part.WeaponUnit;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * 半自动机炮火控的玩家微调状态。
 *
 * <p>状态只保存相对理论预瞄方向的局部俯仰/方位角偏置，不保存世界坐标点；因此目标、载具和
 * 炮口继续运动时，最终射击方向仍会自动跟随预瞄解，玩家不需要持续移动鼠标。</p>
 */
public final class RVP_SemiAutoLeadTrimState {
    /** 各根火控武器站的半自动微调状态，键由载具实体 ID 和武器站索引组合。 */
    private static final Map<Integer, State> STATES = new HashMap<>();

    /** 有效预瞄解短暂缺失时允许继续接收微调输入的最长时间，单位为 Tick。 */
    private static final int SOLUTION_HOLD_TICKS = 8;

    /** 状态表超过该规模后才执行过期清理，避免常规单载具场景产生无意义遍历。 */
    private static final int PRUNE_SIZE_THRESHOLD = 24;

    /** 状态超过该时间仍未取得有效预瞄解时从状态表移除，单位为 Tick。 */
    private static final int PRUNE_STALE_TICKS = 80;

    /** 单次方向球面插值进入线性退化分支的最小夹角正弦。 */
    private static final double SLERP_EPSILON = 1.0E-6D;

    /** 工具类不允许实例化。 */
    private RVP_SemiAutoLeadTrimState() {}

    /**
     * 激活当前有效预瞄解对应的微调状态。
     *
     * <p>目标、武器或有效解连续性变化时清零偏置；同一目标在八 Tick 内的短暂丢解则继续沿用，
     * 避免网络抖动造成准星突然跳回理论预瞄点。</p>
     *
     * @param weaponUnit 当前根火控武器站
     * @param target 当前预瞄解对应的锁定目标
     */
    public static void activate(WeaponUnit weaponUnit, @Nullable Entity target) {
        if (weaponUnit == null || target == null) {
            return;
        }
        int nowTick = weaponUnit.getVehicle().tickCount;
        State state = STATES.computeIfAbsent(key(weaponUnit), unused -> new State());
        String weaponKey = currentWeaponKey(weaponUnit);
        if (!state.initialized
                || state.targetId != target.getId()
                || !state.weaponKey.equals(weaponKey)
                || nowTick - state.lastSolutionTick > SOLUTION_HOLD_TICKS) {
            state.pitchOffsetDeg = 0.0F;
            state.yawOffsetDeg = 0.0F;
            state.targetId = target.getId();
            state.weaponKey = weaponKey;
            state.initialized = true;
        }
        state.lastSolutionTick = nowTick;
        prune(nowTick);
    }

    /**
     * 记录本体瞄具输入产生的俯仰目标角增量。
     *
     * @param weaponUnit 当前根火控武器站
     * @param requestedPitchDeg 本体准备写入的俯仰目标角，单位为度
     */
    public static void recordPitchInput(WeaponUnit weaponUnit, float requestedPitchDeg) {
        State state = activeStateForInput(weaponUnit);
        if (state == null) {
            return;
        }
        float deltaDeg = Mth.wrapDegrees(requestedPitchDeg - weaponUnit.getXAimRot());
        state.pitchOffsetDeg += deltaDeg;
    }

    /**
     * 记录本体瞄具输入产生的方位目标角增量。
     *
     * @param weaponUnit 当前根火控武器站
     * @param requestedYawDeg 本体准备写入的方位目标角，单位为度
     */
    public static void recordYawInput(WeaponUnit weaponUnit, float requestedYawDeg) {
        State state = activeStateForInput(weaponUnit);
        if (state == null) {
            return;
        }
        float deltaDeg = Mth.wrapDegrees(requestedYawDeg - weaponUnit.getYAimRot());
        state.yawOffsetDeg += deltaDeg;
    }

    /**
     * 把当前锁存微调应用到理论预瞄方向，并按离轴角限制最终方向。
     *
     * <p>先在武器站局部俯仰/方位坐标中叠加偏置，再转换回世界方向。这样载具横滚或转向时，
     * “准星位于预瞄点前/后/上/下”的关系会随武器站一起运动，而不会固定在世界轴上。</p>
     *
     * @param weaponUnit 当前根火控武器站
     * @param baseWorldDirection 理论预瞄世界方向
     * @param maxOffAxisDeg 允许的最大单侧偏置角，单位为度
     * @return 应交给本体瞄准方法的最终世界方向
     */
    public static Vec3 apply(WeaponUnit weaponUnit, Vec3 baseWorldDirection, float maxOffAxisDeg) {
        if (weaponUnit == null || baseWorldDirection == null || baseWorldDirection.lengthSqr() < 1.0E-8D) {
            return baseWorldDirection;
        }
        State state = STATES.get(key(weaponUnit));
        if (state == null || !state.initialized) {
            return baseWorldDirection.normalize();
        }

        float safeMaxOffAxisDeg = Mth.clamp(maxOffAxisDeg, 0.0F, 89.0F);
        float[] clampedOffset = clampOffsetMagnitude(
                state.pitchOffsetDeg,
                state.yawOffsetDeg,
                safeMaxOffAxisDeg
        );
        state.pitchOffsetDeg = clampedOffset[0];
        state.yawOffsetDeg = clampedOffset[1];

        // 调用本体武器站坐标换算，将世界预瞄方向变换到随载具和安装骨旋转的局部坐标。
        Vec3 baseLocalDirection = weaponUnit.worldVecToLocalVec(baseWorldDirection).normalize();
        Vec3 trimmedLocalDirection = applyLocalTrim(
                baseLocalDirection,
                state.pitchOffsetDeg,
                state.yawOffsetDeg,
                safeMaxOffAxisDeg
        );
        Vec2 trimmedLocalRot = VectorUtil.vecToRot(trimmedLocalDirection);
        // 调用本体武器站方向换算，将局部微调结果恢复成世界方向供最终 aim 调用。
        return weaponUnit.worldVec(trimmedLocalRot.x, trimmedLocalRot.y).normalize();
    }

    /** 清理指定武器站的半自动微调，供模式、武器或资格发生变化时避免继承旧偏置。 */
    public static void clear(@Nullable WeaponUnit weaponUnit) {
        if (weaponUnit != null) {
            STATES.remove(key(weaponUnit));
        }
    }

    /**
     * 在纯局部坐标中叠加双轴偏置；包可见以便无需构造载具实体即可做数学回归测试。
     */
    static Vec3 applyLocalTrim(Vec3 baseLocalDirection, float pitchOffsetDeg, float yawOffsetDeg,
                               float maxOffAxisDeg) {
        Vec3 baseDirection = baseLocalDirection.normalize();
        if (maxOffAxisDeg <= 0.0F
                || (Math.abs(pitchOffsetDeg) < 1.0E-6F && Math.abs(yawOffsetDeg) < 1.0E-6F)) {
            return baseDirection;
        }
        Vec2 baseRot = VectorUtil.vecToRot(baseDirection);
        Vec3 requestedDirection = VectorUtil.rotToVec(
                baseRot.x + pitchOffsetDeg,
                baseRot.y + yawOffsetDeg
        ).normalize();
        double angleRad = VectorUtil.angleBetween(baseDirection, requestedDirection);
        double maxAngleRad = Math.toRadians(Math.max(0.0F, maxOffAxisDeg));
        if (angleRad <= maxAngleRad || angleRad < SLERP_EPSILON) {
            return requestedDirection;
        }
        return slerpDirection(baseDirection, requestedDirection, maxAngleRad / angleRad);
    }

    /**
     * 将双轴角偏置按圆形边界限制，避免两个轴分别钳制形成方形离轴包线。
     */
    static float[] clampOffsetMagnitude(float pitchOffsetDeg, float yawOffsetDeg, float maxOffAxisDeg) {
        float safeMax = Math.max(0.0F, maxOffAxisDeg);
        double magnitude = Math.hypot(pitchOffsetDeg, yawOffsetDeg);
        if (magnitude <= safeMax || magnitude < 1.0E-6D) {
            return new float[]{pitchOffsetDeg, yawOffsetDeg};
        }
        double scale = safeMax / magnitude;
        return new float[]{(float) (pitchOffsetDeg * scale), (float) (yawOffsetDeg * scale)};
    }

    /** 在单位球面上按最短弧插值方向，用于精确执行最终离轴角上限。 */
    private static Vec3 slerpDirection(Vec3 from, Vec3 to, double ratio) {
        double dot = Mth.clamp(from.dot(to), -1.0D, 1.0D);
        double angle = Math.acos(dot);
        double sinAngle = Math.sin(angle);
        if (Math.abs(sinAngle) < SLERP_EPSILON) {
            return from.lerp(to, Mth.clamp(ratio, 0.0D, 1.0D)).normalize();
        }
        double safeRatio = Mth.clamp(ratio, 0.0D, 1.0D);
        double fromScale = Math.sin((1.0D - safeRatio) * angle) / sinAngle;
        double toScale = Math.sin(safeRatio * angle) / sinAngle;
        return from.scale(fromScale).add(to.scale(toScale)).normalize();
    }

    /** 解析当前仍可接收鼠标微调的状态，并拒绝目标或武器已经变化的迟到输入。 */
    @Nullable
    private static State activeStateForInput(WeaponUnit weaponUnit) {
        if (weaponUnit == null) {
            return null;
        }
        State state = STATES.get(key(weaponUnit));
        if (state == null || !state.initialized) {
            return null;
        }
        int nowTick = weaponUnit.getVehicle().tickCount;
        if (nowTick - state.lastSolutionTick > SOLUTION_HOLD_TICKS) {
            STATES.remove(key(weaponUnit));
            return null;
        }
        // 调用本项目锁定目标解析器，防止切换目标后、下一份预瞄解生成前把输入写入旧目标状态。
        Entity trackedTarget = RVP_MachinegunLeadSolver.resolveTrackedTarget(weaponUnit);
        if (trackedTarget == null
                || trackedTarget.getId() != state.targetId
                || !currentWeaponKey(weaponUnit).equals(state.weaponKey)) {
            STATES.remove(key(weaponUnit));
            return null;
        }
        return state;
    }

    /** 取得当前实际武器的数据键，用于切换同一武器站内不同机炮时隔离微调。 */
    private static String currentWeaponKey(WeaponUnit weaponUnit) {
        // 调用本项目武器解析器，兼容根火控站把当前机炮委托给子武器站或组合武器的配置。
        RVP_WeaponBase weapon = RVP_WeaponResolveHelper.currentPrimaryRvp(weaponUnit);
        if (weapon != null && weapon.getData().getWeaponId() != null) {
            return weapon.getData().getWeaponId().toString();
        }
        return "";
    }

    /** 组合载具实体 ID 和武器站索引，隔离不同武器站的客户端微调状态。 */
    private static int key(WeaponUnit weaponUnit) {
        return weaponUnit.getVehicle().getId() * 257 + weaponUnit.getIndex();
    }

    /** 按载具 Tick 清理长期未使用状态，防止反复进出载具后客户端状态表持续增长。 */
    private static void prune(int nowTick) {
        if (STATES.size() <= PRUNE_SIZE_THRESHOLD || (nowTick & 31) != 0) {
            return;
        }
        Iterator<Map.Entry<Integer, State>> iterator = STATES.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<Integer, State> entry = iterator.next();
            if (nowTick - entry.getValue().lastSolutionTick > PRUNE_STALE_TICKS) {
                iterator.remove();
            }
        }
    }

    /** 单个根火控武器站的双轴微调及身份连续性状态。 */
    private static final class State {
        /** 玩家相对理论预瞄方向锁存的俯仰角偏置，单位为度。 */
        float pitchOffsetDeg;

        /** 玩家相对理论预瞄方向锁存的方位角偏置，单位为度。 */
        float yawOffsetDeg;

        /** 最近一次有效预瞄解对应的目标实体 ID。 */
        int targetId = -1;

        /** 最近一次有效预瞄解对应的当前武器资源键。 */
        String weaponKey = "";

        /** 最近一次取得有效预瞄解的载具 Tick。 */
        int lastSolutionTick = Integer.MIN_VALUE;

        /** 当前状态是否已由一份有效预瞄解初始化。 */
        boolean initialized;
    }
}
