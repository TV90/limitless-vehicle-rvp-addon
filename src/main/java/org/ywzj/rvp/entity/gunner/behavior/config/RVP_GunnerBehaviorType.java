package org.ywzj.rvp.entity.gunner.behavior.config;

import net.minecraft.resources.ResourceLocation;

import java.util.Set;

/** Gunner 行为注册表中的不可变类型元数据。 */
public record RVP_GunnerBehaviorType(
        /** JSON 使用的稳定类型 ID。 */ ResourceLocation id,
        /** 该行为 config 唯一允许的字段集合。 */ Set<String> configKeys,
        /** 缺省仲裁优先级。 */ int defaultPriority,
        /** 允许的最小仲裁优先级。 */ int minPriority,
        /** 允许的最大仲裁优先级。 */ int maxPriority) {

    public RVP_GunnerBehaviorType {
        configKeys = Set.copyOf(configKeys);
    }
}
