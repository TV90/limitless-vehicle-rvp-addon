package org.ywzj.rvp.client;

import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

/** 旧自动默认声策略已停用；保留否定入口作回归防线，禁止按口径/载具分类重启隐式接管。 */
final class RVP_WeaponFireSoundPolicy {
    private RVP_WeaponFireSoundPolicy() {}
    static boolean useGroundRocketSound(boolean ground, boolean rocket, @Nullable Float caliber,
                                        boolean resources, @Nullable Iterable<ResourceLocation> sounds) { return false; }
    static boolean allowsDefaultSound(boolean resources, @Nullable Iterable<ResourceLocation> sounds) { return false; }
}
