package org.ywzj.rvp.virtualflight.server;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/** 实体运动链的跨刻记忆；与位置/姿态快照一起保存，恢复时不能重新推算或清零。 */
public record RVP_VirtualMissileKinematics(
        /** 当前制导速率基准，格/刻；与只增不减的历史峰值分开保存。 */ double currentFlightSpeed,
        /** 已实际执行的飞行更新次数，不包含区块等待。 */ int updateCount,
        /** 上一刻运动开始时的竖直速度，供二脉冲顶点点火使用。 */ double previousVerticalSpeed,
        /** 是否已有一帧竖直速度历史，首次更新不能凭空触发顶点点火。 */ boolean previousVerticalSpeedValid,
        /** PRESET 实际点火参考点，未初始化时为空。 */ @Nullable Vec3 presetLaunchPosition,
        /** PRESET 已冻结的上升参考点，未初始化时为空。 */ @Nullable Vec3 presetAscentPosition,
        /** PRESET 已冻结的目标上空参考点，未初始化时为空。 */ @Nullable Vec3 presetOverheadPosition,
        /** 是否已经冻结 PRESET 参考点。 */ boolean presetInitialized,
        /** 冷发射窗口内是否已经由 PRESET 接管过运动。 */ boolean presetGuidanceAppliedDuringLaunch,
        /** 发射时固化的风偏方向；风场扰动仍由同一飞行 UUID 和飞行刻生成。 */ Vec3 inheritedWindDirection,
        /** 发射瞬间载机世界速度，格/刻。 */ Vec3 launchVehicleVelocity,
        /** 武器单元配置的局部冷发射速度，格/刻。 */ Vec3 coldLaunchVelocity) {

    /** 编码实体运动记忆，不保存实体引用、区块或客户端状态。 */
    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putDouble("currentFlightSpeed", currentFlightSpeed);
        tag.putInt("updateCount", updateCount);
        tag.putDouble("previousVerticalSpeed", previousVerticalSpeed);
        tag.putBoolean("previousVerticalSpeedValid", previousVerticalSpeedValid);
        // 调用本类向量编码器，三个可空参考点与初始化标志一起原样持久化。
        putVector(tag, "presetLaunchPosition", presetLaunchPosition);
        putVector(tag, "presetAscentPosition", presetAscentPosition);
        putVector(tag, "presetOverheadPosition", presetOverheadPosition);
        tag.putBoolean("presetInitialized", presetInitialized);
        tag.putBoolean("presetGuidanceAppliedDuringLaunch", presetGuidanceAppliedDuringLaunch);
        putVector(tag, "inheritedWindDirection", inheritedWindDirection);
        putVector(tag, "launchVehicleVelocity", launchVehicleVelocity);
        putVector(tag, "coldLaunchVelocity", coldLaunchVelocity);
        return tag;
    }

    /** 只读取当前运动记忆格式；外层状态版本负责拒绝旧格式，不补造缺失的飞行历史。 */
    public static RVP_VirtualMissileKinematics load(CompoundTag tag) {
        if (!tag.contains("currentFlightSpeed", Tag.TAG_DOUBLE)
                || !tag.contains("updateCount", Tag.TAG_INT)
                || !tag.contains("previousVerticalSpeed", Tag.TAG_DOUBLE)
                || !tag.contains("previousVerticalSpeedValid", Tag.TAG_BYTE)
                || !tag.contains("presetInitialized", Tag.TAG_BYTE)
                || !tag.contains("presetGuidanceAppliedDuringLaunch", Tag.TAG_BYTE)) {
            throw new IllegalArgumentException("虚拟导弹缺失实体运动记忆");
        }
        // 调用本类向量解码器；必需向量缺失时保留 null，由统一校验拒绝记录。
        return new RVP_VirtualMissileKinematics(tag.getDouble("currentFlightSpeed"), tag.getInt("updateCount"),
                tag.getDouble("previousVerticalSpeed"), tag.getBoolean("previousVerticalSpeedValid"),
                vector(tag, "presetLaunchPosition"), vector(tag, "presetAscentPosition"),
                vector(tag, "presetOverheadPosition"), tag.getBoolean("presetInitialized"),
                tag.getBoolean("presetGuidanceAppliedDuringLaunch"), vector(tag, "inheritedWindDirection"),
                vector(tag, "launchVehicleVelocity"), vector(tag, "coldLaunchVelocity"));
    }

    /** 防止坏存档把非有限速度、缺失风向或不完整 PRESET 参考点交给实体运动链。 */
    public boolean isValid() {
        return Double.isFinite(currentFlightSpeed) && currentFlightSpeed >= 0.0 && updateCount >= 0
                && Double.isFinite(previousVerticalSpeed)
                && finite(inheritedWindDirection) && finite(launchVehicleVelocity) && finite(coldLaunchVelocity)
                && (presetLaunchPosition == null || finite(presetLaunchPosition))
                && (presetAscentPosition == null || finite(presetAscentPosition))
                && (presetOverheadPosition == null || finite(presetOverheadPosition))
                && (!presetInitialized || presetLaunchPosition != null
                    && presetAscentPosition != null && presetOverheadPosition != null);
    }

    /** 写入可空世界向量；不把空参考点伪装成世界原点。 */
    private static void putVector(CompoundTag tag, String key, @Nullable Vec3 value) {
        if (value == null) return;
        CompoundTag vector = new CompoundTag();
        vector.putDouble("x", value.x);
        vector.putDouble("y", value.y);
        vector.putDouble("z", value.z);
        tag.put(key, vector);
    }

    /** 读取完整向量；缺字段直接拒绝，避免坐标分量被静默补零。 */
    @Nullable private static Vec3 vector(CompoundTag tag, String key) {
        if (!tag.contains(key, Tag.TAG_COMPOUND)) return null;
        CompoundTag value = tag.getCompound(key);
        if (!value.contains("x", Tag.TAG_DOUBLE) || !value.contains("y", Tag.TAG_DOUBLE)
                || !value.contains("z", Tag.TAG_DOUBLE)) throw new IllegalArgumentException("不完整运动向量：" + key);
        return new Vec3(value.getDouble("x"), value.getDouble("y"), value.getDouble("z"));
    }

    /** 向量存在且三轴均为有限值时才可继续飞行。 */
    private static boolean finite(@Nullable Vec3 value) {
        return value != null && Double.isFinite(value.x) && Double.isFinite(value.y) && Double.isFinite(value.z);
    }
}
