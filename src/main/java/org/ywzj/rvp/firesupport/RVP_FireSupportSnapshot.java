package org.ywzj.rvp.firesupport;

import net.minecraft.resources.ResourceLocation;

import java.util.Map;

/** 一次原子发布的不可变 profile 集合。 */
public record RVP_FireSupportSnapshot(
        /** 单调递增的运行时修订号。 */ long revision,
        /** 以资源 ID 为键的不可变 profile 表。 */ Map<ResourceLocation, RVP_FireSupportProfile> profiles) {
    public RVP_FireSupportSnapshot { profiles = Map.copyOf(profiles); }

    /** 初始空快照；第一次成功重载后修订号变为 1。 */
    public static RVP_FireSupportSnapshot empty() { return new RVP_FireSupportSnapshot(0, Map.of()); }
}
