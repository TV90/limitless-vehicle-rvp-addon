package org.ywzj.rvp.server.remotevisibility;

import org.jetbrains.annotations.Nullable;
import org.ywzj.rvp.config.RVP_CommonConfig.VisibilityMode;
import org.ywzj.vehicle.entity.misc.VehiclePart;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;
import org.ywzj.vehicle.entity.vehicle.FixedWingVehicle;
import org.ywzj.vehicle.entity.vehicle.RotaryWingVehicle;

import java.util.Collections;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** 服务端远距载具分类、配置 token 规范化与纯授权筛选策略。 */
public final class RVP_RemoteVehicleVisibilityPolicy {
    /** 原生实体追踪与远距视觉接管的水平距离边界，单位格。 */
    static final double NATIVE_TRACKING_DISTANCE = 512.0D;
    /** 雷达模式无需探测即可直接显示的水平距离边界，单位格。 */
    static final double RADAR_DIRECT_VISIBILITY_DISTANCE = 1024.0D;

    /** 工具类不允许实例化。 */
    private RVP_RemoteVehicleVisibilityPolicy() {
    }

    /** 服务端用于类型白名单判定的三类完整载具。 */
    public enum VehicleCategory {
        /** 直升机与其他旋翼航空器。 */
        HELICOPTER("helicopter"),
        /** 固定翼航空器。 */
        AIRCRAFT("aircraft"),
        /** 履带、轮式及其他可同步地面车辆。 */
        GROUND_VEHICLES("ground_vehicles");

        /** common 配置中使用的严格小写 token。 */
        private final String token;

        VehicleCategory(String token) {
            this.token = token;
        }

        /** 返回写入 common 配置的严格小写 token。 */
        public String token() {
            return token;
        }

        /** 仅按严格小写 token 解析载具分类，不自动纠正大小写或空白。 */
        public static Optional<VehicleCategory> fromToken(String token) {
            if (token == null) {
                return Optional.empty();
            }
            for (VehicleCategory category : values()) {
                if (category.token.equals(token)) {
                    return Optional.of(category);
                }
            }
            return Optional.empty();
        }
    }

    /**
     * 按本体公开实体基类分类完整载具。
     *
     * <p>未命中固定翼或旋翼的完整载具统一归为地面车辆；拆分出来的 {@link VehiclePart}
     * 不属于可同步目标。此处禁止按车型、display 或模型资源 ID 分类。</p>
     */
    public static Optional<VehicleCategory> classify(@Nullable AbstractVehicle vehicle) {
        if (vehicle == null || vehicle instanceof VehiclePart) {
            return Optional.empty();
        }
        if (vehicle instanceof RotaryWingVehicle) {
            return Optional.of(VehicleCategory.HELICOPTER);
        }
        if (vehicle instanceof FixedWingVehicle) {
            return Optional.of(VehicleCategory.AIRCRAFT);
        }
        return Optional.of(VehicleCategory.GROUND_VEHICLES);
    }

    /**
     * 对单名玩家执行纯授权筛选和稳定截断。
     *
     * @param mode 服务端权威可见模式
     * @param observerCategory 观察者所乘完整载具类型；步行或观察者玩家为空
     * @param observerVehicleId 观察者所乘载具实体 ID；没有载具时为负数
     * @param allowedTargetTypes 观察者载具类型对应的权威目标类型白名单
     * @param maxDistance 最大水平同步距离，单位格
     * @param maxTargets 单份完整快照的最大目标数量
     * @param candidates 同维度已加载目标的纯事实集合
     * @return 按水平距离、实体 ID 稳定排序并截断的授权目标
     */
    public static List<Candidate> selectTargets(
            VisibilityMode mode,
            @Nullable VehicleCategory observerCategory,
            int observerVehicleId,
            Set<VehicleCategory> allowedTargetTypes,
            double maxDistance,
            int maxTargets,
            List<Candidate> candidates) {
        Objects.requireNonNull(mode, "mode");
        Objects.requireNonNull(allowedTargetTypes, "allowedTargetTypes");
        Objects.requireNonNull(candidates, "candidates");
        if (mode == VisibilityMode.OFF || maxTargets <= 0 || maxDistance <= NATIVE_TRACKING_DISTANCE) {
            return List.of();
        }
        if ((mode == VisibilityMode.RADAR_DETECTED || mode == VisibilityMode.VEHICLE_OCCUPANTS)
                && observerCategory == null) {
            return List.of();
        }

        double nativeDistanceSq = NATIVE_TRACKING_DISTANCE * NATIVE_TRACKING_DISTANCE;
        double maximumDistanceSq = maxDistance * maxDistance;
        double directRadarDistanceSq = RADAR_DIRECT_VISIBILITY_DISTANCE * RADAR_DIRECT_VISIBILITY_DISTANCE;
        return candidates.stream()
                .filter(candidate -> candidate.entityId() != observerVehicleId)
                .filter(candidate -> candidate.horizontalDistanceSq() > nativeDistanceSq
                        && candidate.horizontalDistanceSq() <= maximumDistanceSq)
                .filter(candidate -> observerCategory == null
                        || allowedTargetTypes.contains(candidate.category()))
                .filter(candidate -> mode != VisibilityMode.RADAR_DETECTED
                        || candidate.horizontalDistanceSq() <= directRadarDistanceSq
                        || candidate.radarDetected())
                .sorted(Comparator.comparingDouble(Candidate::horizontalDistanceSq)
                        .thenComparingInt(Candidate::entityId))
                .limit(Math.max(0, maxTargets))
                .toList();
    }

    /**
     * 规范化服务端白名单 token。
     *
     * @param configuredTokens 配置文件中的原始 token 列表
     * @return 去重后的不可变分类集合与未知 token 集合
     */
    public static NormalizationResult normalizeConfiguredTypes(List<? extends String> configuredTokens) {
        Objects.requireNonNull(configuredTokens, "configuredTokens");
        EnumSet<VehicleCategory> accepted = EnumSet.noneOf(VehicleCategory.class);
        Set<String> unknown = new LinkedHashSet<>();
        for (String token : configuredTokens) {
            VehicleCategory.fromToken(token).ifPresentOrElse(accepted::add, () -> unknown.add(String.valueOf(token)));
        }
        Set<VehicleCategory> immutableAccepted = accepted.isEmpty()
                ? Set.of()
                : Collections.unmodifiableSet(EnumSet.copyOf(accepted));
        return new NormalizationResult(immutableAccepted, Set.copyOf(unknown));
    }

    /**
     * 同维度已加载候选的纯筛选事实。
     *
     * @param entityId 服务端实体 ID
     * @param category 目标载具分类
     * @param horizontalDistanceSq 观察者到目标的水平距离平方，单位格平方
     * @param radarDetected 本机或授权数据链雷达当前是否发现目标
     */
    public record Candidate(
            int entityId,
            VehicleCategory category,
            double horizontalDistanceSq,
            boolean radarDetected) {
        public Candidate {
            Objects.requireNonNull(category, "category");
            if (entityId < 0 || !Double.isFinite(horizontalDistanceSq) || horizontalDistanceSq < 0.0D) {
                throw new IllegalArgumentException("Invalid remote vehicle visibility candidate");
            }
        }
    }

    /** 配置 token 规范化结果。 */
    public record NormalizationResult(Set<VehicleCategory> accepted, Set<String> unknown) {
        public NormalizationResult {
            accepted = Set.copyOf(Objects.requireNonNull(accepted, "accepted"));
            unknown = Set.copyOf(Objects.requireNonNull(unknown, "unknown"));
        }
    }
}
