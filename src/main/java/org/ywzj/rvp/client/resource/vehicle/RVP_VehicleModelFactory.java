package org.ywzj.rvp.client.resource.vehicle;

import com.github.mcmodderanchor.simplebedrockmodel.v1.common.resource.pojo.BedrockModelPOJO;
import com.github.mcmodderanchor.simplebedrockmodel.v1.common.resource.pojo.BonesItem;
import com.github.mcmodderanchor.simplebedrockmodel.v1.common.resource.pojo.GeometryModelLegacy;
import com.github.mcmodderanchor.simplebedrockmodel.v1.common.resource.pojo.GeometryModelNew;
import com.github.mcmodderanchor.simplebedrockmodel.v2.common.model.baked.BakerOptions;
import org.ywzj.vehicle.client.resource.vehicle.SpecialBoneEffect;
import org.ywzj.vehicle.client.resource.vehicle.VehicleBedrockModel;

import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

public final class RVP_VehicleModelFactory {

    /**
     * 武器 display 的共享烘焙缓存：POJO -> 后端 -> 模型。
     *
     * <p>只缓存武器 display：武器 display JSON 没有 state_hidden_bones / no_cull_bones /
     * distance_hidden_bones 等 display 级骨骼规则，按 model 共享不会把别的 display 的规则
     * 串进来。载具 display 这些配置可以不同，仍保持每个 display 独立烘焙。</p>
     *
     * <p>弱键 POJO：资源 reload 后旧 POJO 被新 display 替换，本缓存条目可随之回收；
     * value 不反向引用 POJO，因此不会出现 {@code WeakHashMap} 自锁。</p>
     */
    private static final Map<BedrockModelPOJO, Map<RVP_BedrockBackend, VehicleBedrockModel>> WEAPON_BAKE_CACHE =
            Collections.synchronizedMap(new WeakHashMap<>());

    private RVP_VehicleModelFactory() {
    }

    /** 资源 reload 时主动释放旧烘焙缓存；弱键只是兜底。 */
    public static void clearBakeCache() {
        WEAPON_BAKE_CACHE.clear();
    }

    public static VehicleBedrockModel createVehicleModel(BedrockModelPOJO pojo,
                                                         List<SpecialBoneEffect> effects,
                                                         RVP_BedrockBackend backend) {
        return bake(pojo, effects, backend, Set.of(), false);
    }

    public static VehicleBedrockModel createVehicleModel(BedrockModelPOJO pojo,
                                                         List<SpecialBoneEffect> effects,
                                                         RVP_BedrockBackend backend,
                                                         Set<String> extraPreservedBones) {
        return bake(pojo, effects, backend, extraPreservedBones, false);
    }

    public static VehicleBedrockModel createEffectlessModel(BedrockModelPOJO pojo, RVP_BedrockBackend backend) {
        return bake(pojo, List.of(), backend, Set.of(), false);
    }

    /** 弹药/武器 display 使用同款 RVP 烘焙模型，并让所有 mesh 都进入共享静态几何通道。 */
    public static VehicleBedrockModel createWeaponModel(BedrockModelPOJO pojo,
                                                        List<SpecialBoneEffect> effects,
                                                        RVP_BedrockBackend backend) {
        // 带特殊骨的武器 display 让烘焙输入不同，直接独立烘焙；常规弹药/武器共享。
        if (effects != null && !effects.isEmpty()) {
            return bake(pojo, effects, backend, Set.of(), true);
        }
        synchronized (WEAPON_BAKE_CACHE) {
            Map<RVP_BedrockBackend, VehicleBedrockModel> byBackend =
                    WEAPON_BAKE_CACHE.computeIfAbsent(pojo, ignored -> new HashMap<>());
            return byBackend.computeIfAbsent(backend,
                    ignored -> bake(pojo, effects, backend, Set.of(), true));
        }
    }

    private static VehicleBedrockModel bake(BedrockModelPOJO pojo,
                                            List<SpecialBoneEffect> effects,
                                            RVP_BedrockBackend backend,
                                            Set<String> extraPreservedBones,
                                            boolean sharedMesh) {
        BakerOptions bakerOptions = createStaticBakerOptions(pojo, effects, backend, extraPreservedBones);
        if (backend == RVP_BedrockBackend.RVP) {
            return sharedMesh
                    ? new RVP_VehicleBedrockModel(pojo, effects, bakerOptions, true)
                    : new RVP_VehicleBedrockModel(pojo, effects, bakerOptions);
        }
        return new VehicleBedrockModel(pojo, effects, bakerOptions);
    }

    private static BakerOptions createStaticBakerOptions(BedrockModelPOJO pojo,
                                                         List<SpecialBoneEffect> effects,
                                                         RVP_BedrockBackend backend,
                                                         Set<String> extraPreservedBones) {
        Set<String> preservedBones = new LinkedHashSet<>();
        if (backend == RVP_BedrockBackend.RVP && pojo != null) {
            // 直接读 POJO 骨名；原实现 new BedrockModel(pojo) 只为拿 keySet，
            // 会为每个 display 多完整解析一次全部 cube/poly_mesh，是启动/重载峰值的主要来源之一。
            collectBoneNames(pojo, preservedBones);
        }
        if (extraPreservedBones != null) {
            for (String bone : extraPreservedBones) {
                if (bone != null && !bone.isBlank()) {
                    preservedBones.add(bone);
                }
            }
        }
        if (effects != null) {
            for (SpecialBoneEffect effect : effects) {
                if (effect != null && effect.isValid() && effect.bone != null && !effect.bone.isBlank()) {
                    preservedBones.add(effect.bone);
                }
            }
        }
        Set<String> animatedBones = backend == RVP_BedrockBackend.RVP ? preservedBones : Set.of();
        return new BakerOptions(animatedBones, preservedBones, Set.of(), true, false, true);
    }

    /** 从 POJO 的新版/旧版 geometry 中收集骨名，语义与 {@code BedrockModel.getBoneMap().keySet()} 一致。 */
    private static void collectBoneNames(BedrockModelPOJO pojo, Set<String> out) {
        GeometryModelNew geometry = pojo.getGeometryModelNew();
        if (geometry != null && geometry.getBones() != null) {
            for (BonesItem bone : geometry.getBones()) {
                addBoneName(out, bone);
            }
        }
        GeometryModelLegacy legacy = pojo.getGeometryModelLegacy();
        if (legacy != null && legacy.getBones() != null) {
            for (BonesItem bone : legacy.getBones()) {
                addBoneName(out, bone);
            }
        }
    }

    private static void addBoneName(Set<String> out, BonesItem bone) {
        if (bone != null && bone.getName() != null && !bone.getName().isBlank()) {
            out.add(bone.getName());
        }
    }
}
