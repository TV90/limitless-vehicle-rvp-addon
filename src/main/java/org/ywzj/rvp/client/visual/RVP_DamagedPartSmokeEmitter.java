package org.ywzj.rvp.client.visual;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.joml.Vector3f;
import org.ywzj.rvp.RVP_MOD;
import org.ywzj.rvp.client.particle.RVP_MchrSmokeParticle;
import org.ywzj.rvp.client.particle.RVP_MchrSmokeRenderType;
import org.ywzj.rvp.client.state.RVP_ClientBoneModuleState;
import org.ywzj.rvp.client.state.RVP_ClientEngineDamageState;
import org.ywzj.rvp.vehicle.BoneModuleType;
import org.ywzj.rvp.weapon.damage.RVP_VehicleHitboxFactorManager;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;
import org.ywzj.vehicle.vehicle.structure.OBB;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * [RVP] 部件损坏冒烟（客户端本地粒子，2026-09-28 用户需求）。
 *
 * <p>语义（用户定版）：<b>除 ERA 外</b>的骨骼模块失效后（雷达/APS/ECM/干扰机/DIRCM/
 * 干扰物/维修等），在失效部件骨对应的<b>实时命中 OBB</b>（不含具名子骨骼的 OBB）内
 * 随机取样冒黑烟；OBB 体积越大每轮取样越多。引擎骨特殊：重创（档位 1）冒黑烟、
 * 瘫痪（档位 2）冒火星火焰粒子。修复后状态侧表回落 → 自动停烟。</p>
 *
 * <p>实现要点：
 * <ul>
 * <li><b>实时 OBB</b>：经 {@link RVP_VehicleHitboxFactorManager#resolveBoneObbsForSampling}
 *     解析——部件实例 OBB（{@code PartUnit.getOBBs()}）由本体
 *     {@code AbstractVehicle.tick → updateOBBs()} 每 tick 随载具旋转/位移重算；无部件实例的
 *     结构骨（Engine 等）回退骨骼 OBB（叠加车体实时变换）。非静态结构模型数据。</li>
 * <li><b>OBB 内随机取样</b>：局部空间 [-extents, extents] 均匀采样后
 *     {@link OBB#localToWorld(Vector3f, Vector3f[])} 变换到世界（参考本体 barrel 的
 *     局部→载具→世界变换链，但按用户要求升级为 OBB 体积内随机取样）。</li>
 * <li><b>状态来源</b>：既有 {@code S2CBoneModuleState}/{@code S2CEngineDamageState} 客户端
 *     侧表（{@link RVP_ClientBoneModuleState}/{@link RVP_ClientEngineDamageState}），
 *     零新增网络包，各客户端对可见载具本地生成粒子。</li>
 * <li><b>黑烟</b>：{@link RVP_MchrSmokeParticle#ofDark}（深灰→近黑 tint，与爆炸烟
 *     {@code of} 灰黄随机分离互不影响），绑定 {@code DAMAGED_SMOKE_RENDER_TYPE}
 *     （深度只测不写，对齐火箭尾焰半透明约定，修复透过烟雾实体被剔穿）；烟团尺寸随
 *     OBB 体积收敛在 2~5（MCHR scale），远小于爆炸烟（1.5~20）——只缩尺寸不缩数量。
 *     本体正常运行时排气口的小浅烟（lifetime 20、size 0.3→0.4）不受影响。</li>
 * </ul></p>
 */
@Mod.EventBusSubscriber(value = Dist.CLIENT, modid = RVP_MOD.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class RVP_DamagedPartSmokeEmitter {

    /** 冒烟轮询周期（tick）：每 N tick 对每个失效骨冒一轮（OBB 解析与取样同步节流）。 */
    private static final int EMIT_PERIOD_TICKS = 4;
    /** 冒烟生效半径（格）：超出玩家该距离的载具不生成（远距烟粒子不可见，纯开销）。 */
    private static final double EMIT_RANGE = 96.0;
    /** 体积→粒子数换算：每多少立方米 OBB 体积出 1 粒/轮（体积越大冒烟越多）。 */
    private static final float CUBIC_METERS_PER_PARTICLE = 4.0f;
    /** 单个 OBB 每轮黑烟粒子数上下限（钳制防止超大骨刷爆粒子）。 */
    private static final int SMOKE_MIN_PER_OBB = 1;
    private static final int SMOKE_MAX_PER_OBB = 8;
    /** 瘫痪火焰每轮粒子数上下限（火焰+火星重粒子，密度低于黑烟）。 */
    private static final int FIRE_MIN_PER_OBB = 1;
    private static final int FIRE_MAX_PER_OBB = 5;

    private static int tickCounter = 0;

    private RVP_DamagedPartSmokeEmitter() {
    }

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) {
            return;
        }
        if (++tickCounter % EMIT_PERIOD_TICKS != 0) {
            return;
        }
        AABB range = mc.player.getBoundingBox().inflate(EMIT_RANGE);
        for (Entity entity : mc.level.entitiesForRendering()) {
            if (!(entity instanceof AbstractVehicle vehicle)) {
                continue;
            }
            if (!vehicle.getBoundingBox().intersects(range)) {
                continue;
            }
            emitForVehicle(vehicle);
        }
    }

    /** 对单台载具：逐部件骨判定损坏状态并取样冒烟（引擎骨走档位分支，其余走失效分支）。 */
    private static void emitForVehicle(AbstractVehicle vehicle) {
        // 目的：拿本载具配置的全部部件骨（骨名→模块类型集），只检查配置了模块的骨
        Map<String, Set<BoneModuleType>> moduleByBone =
                RVP_VehicleHitboxFactorManager.INSTANCE.resolveBoneModules(vehicle);
        if (moduleByBone == null || moduleByBone.isEmpty()) {
            return;
        }
        int entityId = vehicle.getId();
        RandomSource random = vehicle.level().random;
        for (Map.Entry<String, Set<BoneModuleType>> entry : moduleByBone.entrySet()) {
            String bone = entry.getKey();
            Set<BoneModuleType> types = entry.getValue();
            if (types == null || types.isEmpty()) {
                continue;
            }
            // [RVP] 炮管受损档（2026-09-28 两档炮管）：第一档受损**无条件冒黑烟**
            //（不受 smoke 参数钳制——受损三选一需要视觉反馈）；第二档彻底损坏才受钳制
            if (types.contains(BoneModuleType.BARREL)
                    && !RVP_ClientBoneModuleState.isModuleActive(entityId, bone, BoneModuleType.BARREL_DAMAGED)
                    && RVP_ClientBoneModuleState.isModuleActive(entityId, bone, BoneModuleType.BARREL)) {
                emitBlackSmoke(vehicle, bone, random);
                continue;
            }
            // [RVP] 部件冒烟选配（2026-09-28 用户定版）：bone_modules 条目 smoke 字段，
            // 缺省 true——显式 false 的部件失效不生成黑烟特效
            if (!RVP_VehicleHitboxFactorManager.INSTANCE.isPartSmokeEnabled(vehicle, bone)) {
                continue;
            }
            // 引擎骨统一走引擎档位侧表：重创(1)=黑烟、瘫痪(2)=火星火焰（瘫痪时 ENGINE
            // 也进失效表，若走下方失效分支会与档位分支重复冒烟，故优先分流）
            if (types.contains(BoneModuleType.ENGINE)) {
                int stage = RVP_ClientEngineDamageState.getStage(entityId, bone);
                if (stage == 1) {
                    emitBlackSmoke(vehicle, bone, random);
                } else if (stage == 2) {
                    emitEngineFire(vehicle, bone, random);
                }
                continue;
            }
            // 其余部件（雷达/APS/ECM/干扰机/DIRCM/干扰物/维修）：任一非 ERA 类型失效即冒烟；
            // 纯 ERA 骨排除（用户定版：ERA 损坏不冒烟）
            boolean destroyed = false;
            for (BoneModuleType type : types) {
                if (type == BoneModuleType.ERA) {
                    continue;
                }
                if (!RVP_ClientBoneModuleState.isModuleActive(entityId, bone, type)) {
                    destroyed = true;
                    break;
                }
            }
            if (destroyed) {
                emitBlackSmoke(vehicle, bone, random);
            }
        }
    }

    /** 黑烟：在失效骨的实时 OBB 内随机取样（MCHR 黑烟款 + 深度只测不写，体积越大粒数越多）。 */
    private static void emitBlackSmoke(AbstractVehicle vehicle, String bone, RandomSource random) {
        Minecraft mc = Minecraft.getInstance();
        for (OBB obb : resolveObbs(vehicle, bone)) {
            int count = Mth.clamp(Mth.ceil(volume(obb) / CUBIC_METERS_PER_PARTICLE),
                    SMOKE_MIN_PER_OBB, SMOKE_MAX_PER_OBB);
            // [RVP] 烟团尺寸随 OBB 体积（用户 2026-09-28 定版：是太大不是太多——只缩尺寸
            // 不缩数量）：MCHR scale 2~5 = 渲染半宽 0.2~0.5 格起步、每 tick +0.8 扩散到
            // size×2.0 上限，远小于爆炸烟（1.5~20），观感为"部件冒着中等黑烟"而非爆炸
            float size = Mth.clamp(1.5f + volume(obb) * 0.12f, 2f, 5f);
            for (int i = 0; i < count; i++) {
                Vec3 point = randomPointIn(obb, random);
                if (point == null) {
                    continue;
                }
                // ofDark = 深灰→近黑 tint（用户定版黑色烟；与爆炸烟 of 灰黄随机分离，互不影响）；
                // DAMAGED_SMOKE_RENDER_TYPE = 深度只测不写（对齐火箭尾焰半透明约定，
                // 修复"透过烟雾载具实体被剔穿"；爆炸烟 RENDER_TYPE 保持原状）
                mc.particleEngine.add(RVP_MchrSmokeParticle.ofDark((ClientLevel) vehicle.level(),
                        point.x, point.y, point.z,
                        (random.nextDouble() - 0.5) * 0.06,
                        0.02 + random.nextDouble() * 0.02,
                        (random.nextDouble() - 0.5) * 0.06,
                        size, 40 + random.nextInt(40))
                        .withRenderType(RVP_MchrSmokeRenderType.DAMAGED_SMOKE_RENDER_TYPE));
            }
        }
    }

    /** 瘫痪档火星火焰：原版 FLAME 上窜火焰 + LAVA 溅落火星（用户定版保持原版火星），少量黑烟打底。 */
    private static void emitEngineFire(AbstractVehicle vehicle, String bone, RandomSource random) {
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = (ClientLevel) vehicle.level();
        for (OBB obb : resolveObbs(vehicle, bone)) {
            int count = Mth.clamp(Mth.ceil(volume(obb) / (CUBIC_METERS_PER_PARTICLE * 1.5f)),
                    FIRE_MIN_PER_OBB, FIRE_MAX_PER_OBB);
            for (int i = 0; i < count; i++) {
                Vec3 point = randomPointIn(obb, random);
                if (point == null) {
                    continue;
                }
                // 火焰粒子自带向上漂移渲染，补一点初速让火焰从引擎舱内窜出
                level.addParticle(ParticleTypes.FLAME,
                        point.x, point.y, point.z,
                        (random.nextDouble() - 0.5) * 0.04,
                        0.04 + random.nextDouble() * 0.06,
                        (random.nextDouble() - 0.5) * 0.04);
                // 三分之一概率追加熔岩滴火星（自带重力弹跳，火星感）
                if (random.nextInt(3) == 0) {
                    level.addParticle(ParticleTypes.LAVA,
                            point.x, point.y, point.z, 0, 0.05, 0);
                }
            }
            // 少量黑烟打底（燃烧但不完全，与重创档黑烟观感衔接）
            Vec3 smokePoint = randomPointIn(obb, random);
            if (smokePoint != null) {
                mc.particleEngine.add(RVP_MchrSmokeParticle.ofDark(level,
                        smokePoint.x, smokePoint.y, smokePoint.z,
                        0, 0.03, 0, 2.5f, 60)
                        .withRenderType(RVP_MchrSmokeRenderType.DAMAGED_SMOKE_RENDER_TYPE));
            }
        }
    }

    /** 骨名 → 实时命中 OBB 列表（manager 公共取样入口，失败返回空列表跳过）。 */
    private static List<OBB> resolveObbs(AbstractVehicle vehicle, String bone) {
        return RVP_VehicleHitboxFactorManager.INSTANCE.resolveBoneObbsForSampling(vehicle, bone);
    }

    /** OBB 实际体积（立方格）：extents 为半尺寸，故 ×8。 */
    private static float volume(OBB obb) {
        Vector3f extents = obb.extents();
        return 8f * extents.x * extents.y * extents.z;
    }

    /** OBB 体积内均匀随机取样一点并变换到世界坐标（局部 [-extents, extents] → localToWorld）。 */
    private static Vec3 randomPointIn(OBB obb, RandomSource random) {
        Vector3f extents = obb.extents();
        Vector3f local = new Vector3f(
                (random.nextFloat() * 2f - 1f) * extents.x,
                (random.nextFloat() * 2f - 1f) * extents.y,
                (random.nextFloat() * 2f - 1f) * extents.z);
        Vector3f world = obb.localToWorld(local, obb.getAxes());
        return new Vec3(world.x, world.y, world.z);
    }
}
