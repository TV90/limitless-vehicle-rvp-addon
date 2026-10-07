package org.ywzj.rvp.client.render;

import com.github.mcmodderanchor.simplebedrockmodel.v2.common.model.runtime.BakedModelInstance;
import com.github.mcmodderanchor.simplebedrockmodel.v2.common.model.runtime.BoneState;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.ywzj.rvp.config.RVP_PartHiddenBonesCache;
import org.ywzj.vehicle.client.render.entity.vehicle.VehiclePartRender;
import org.ywzj.vehicle.entity.misc.VehiclePart;
import org.ywzj.vehicle.vehicle.part.PartUnit;

import java.util.List;

/**
 * 残件（脱落部件）渲染器：按载具 JSON 的 rvp_part_hidden_bones 把指定渲染骨隐藏，再走本体渲染。
 * <p>
 * 起因：本体 VehiclePartRender 直接 renderSingleBone 而不做 setSpecialBoneVisible(false)，
 * 导致特殊骨（炮口焰等）被当普通几何画出来；又因残件不跑动画控制器，static 动画压不下去。
 * 这里在渲染前把骨置 visible = false，渲染库 renderBone 会连同整棵子树一起跳过。
 * 注册：经 RVP_ClientBootstrap#registerVehicleRenderers 覆盖本体 ClientSetupHandler 的注册
 * （该方法在 FMLLoadCompleteEvent 会再调一次，覆盖注册顺序有保证）。
 * 注意：VehiclePart 没有 getPartUnitId()，用公开的 getPartUnit().getId() 取。
 * </p>
 */
@OnlyIn(Dist.CLIENT)
public class RVP_VehiclePartRender extends VehiclePartRender {

    public RVP_VehiclePartRender(EntityRendererProvider.Context context) {
        super(context);
    }

    @Override
    public void render(VehiclePart part, float pEntityYaw, float pPartialTick,
                       PoseStack pPoseStack, MultiBufferSource bufferSource, int pPackedLight) {
        // 调用 RVP_PartHiddenBonesCache 取该载具该部件的隐藏骨配置（无配置返回空表，零开销）
        PartUnit<?> unit = part.getPartUnit();
        List<String> hidden = unit == null
                ? List.of()
                : RVP_PartHiddenBonesCache.get(part.getVehicleId(), unit.getId());
        // 动态快照（2026-10-08 增强）：源车击毁前已被隐藏的骨序号（JS 损坏隐藏/起落架状态等
        // 全部来源的汇总结果，剔除距离 LOD 临时假值）——残件不跑动画，静态配置之外的损坏隐藏
        // 由此快照延续到残件上；借源失败（源车已卸载）返回空集，降级为仅静态配置
        java.util.Set<Integer> wreckHidden =
                org.ywzj.rvp.client.state.RVP_ClientWreckHiddenBonesCache.snapshotFor(part);
        if (!hidden.isEmpty() || !wreckHidden.isEmpty()) {
            // 调用 AbstractVehicle.getVehicleModelInstance 取残件自己的模型实例（可空，未就绪则不处理）
            BakedModelInstance instance = part.getVehicleModelInstance();
            if (instance != null) {
                for (String boneName : hidden) {
                    // 调用 BoneTreeInstance.getBone 取骨状态，置 visible=false 后
                    // 渲染库 renderBone 在画几何与子树递归之前即跳过该骨
                    BoneState bone = instance.getBone(boneName);
                    if (bone != null) {
                        bone.visible = false;   // 不需要恢复：残件渲染器是本实例的唯一消费者
                    }
                }
                // 动态快照按模型骨序应用（残件与源车共用同一 bedrock 模型，骨序一一对应）
                for (int boneIndex : wreckHidden) {
                    BoneState bone = instance.getBone(boneIndex);
                    if (bone != null) {
                        bone.visible = false;
                    }
                }
            }
        }
        super.render(part, pEntityYaw, pPartialTick, pPoseStack, bufferSource, pPackedLight);
    }
}
