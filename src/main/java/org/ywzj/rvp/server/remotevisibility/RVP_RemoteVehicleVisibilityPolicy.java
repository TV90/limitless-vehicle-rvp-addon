package org.ywzj.rvp.server.remotevisibility;

import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * 远距载具可见性策略的公共类型定义与配置 token 规范化入口。
 * <p>
 * 阶段 A 只建立类型协议；距离、观察者状态和雷达授权筛选将在后续同步阶段补充。
 */
public final class RVP_RemoteVehicleVisibilityPolicy {
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

    /** 配置 token 规范化结果。 */
    public record NormalizationResult(Set<VehicleCategory> accepted, Set<String> unknown) {
        public NormalizationResult {
            accepted = Set.copyOf(Objects.requireNonNull(accepted, "accepted"));
            unknown = Set.copyOf(Objects.requireNonNull(unknown, "unknown"));
        }
    }
}
