package org.ywzj.rvp.server.visual;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import org.ywzj.rvp.weapon.visual.api.RVP_ShockwaveProfile;

import java.util.ArrayList;
import java.util.List;

/** 有界地形采样：只读已经加载的区块，不为视觉效果加载或生成远处地形。 */
public final class RVP_ShockwaveProfileSampler {
    /** 视觉工厂类型标识，与武器 ID 无关。 */
    public static final ResourceLocation EFFECT_TYPE = ResourceLocation.fromNamespaceAndPath("rvp", "wilson_mach_shockwave");
    /** 每方向采样点数；每次爆炸最多 64 个方向样本。 */
    private static final int SAMPLES_PER_DIRECTION = 8;

    private RVP_ShockwaveProfileSampler() { }

    public static RVP_ShockwaveProfile sample(ServerLevel level, Vec3 center, float radius, float scale) {
        // 调用共享尺寸函数，使服务端测量与客户端视觉尺寸一致。
        float visualRadius = RVP_ShockwaveProfile.visualRadius(radius, scale);
        double groundY = height(level, Mth.floor(center.x), Mth.floor(center.z), center.y);
        // 调用公共分类函数，统一空爆与埋爆边界。
        RVP_ShockwaveProfile.HitClass hitClass = RVP_ShockwaveProfile.classify(center.y - groundY);
        float solidity = switch (hitClass) { case AIR -> 0.1F; case GROUND -> 0.7F; case BURIED -> 1.0F; };
        List<Float> reach = new ArrayList<>(RVP_ShockwaveProfile.DIRECTION_COUNT);
        for (int direction = 0; direction < RVP_ShockwaveProfile.DIRECTION_COUNT; direction++) {
            double angle = direction * Math.PI / 4;
            double obstruction = 0;
            for (int step = 1; step <= SAMPLES_PER_DIRECTION; step++) {
                double distance = visualRadius * step / SAMPLES_PER_DIRECTION;
                int x = Mth.floor(center.x + Math.cos(angle) * distance);
                int z = Mth.floor(center.z + Math.sin(angle) * distance);
                // 未加载区块按爆心地面高度回退，不调用会创建区块的高度查询。
                double y = height(level, x, z, groundY);
                obstruction += Mth.clamp((y - groundY) / Math.max(2.0, distance * 0.3), 0, 1);
                if (level.hasChunk(x >> 4, z >> 4)) {
                    BlockPos probe = BlockPos.containing(x, Math.max(center.y, groundY) + 1, z);
                    if (!level.getBlockState(probe).getCollisionShape(level, probe).isEmpty()) {
                        obstruction += 1;
                    }
                }
            }
            float open = (float) (1 - Math.min(1, obstruction / SAMPLES_PER_DIRECTION));
            reach.add(visualRadius * (0.65F + 0.35F * open));
        }
        return new RVP_ShockwaveProfile(hitClass, groundY, solidity, reach);
    }

    private static double height(ServerLevel level, int x, int z, double fallback) {
        return level.hasChunk(x >> 4, z >> 4)
                ? level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z) : fallback;
    }
}
