package org.ywzj.rvp.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import org.ywzj.rvp.client.util.RVP_DamagedPartWireframeHelper;
import org.ywzj.vehicle.all.AllKeys;
import org.ywzj.vehicle.client.render.util.OBBRenderer;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;
import org.ywzj.vehicle.entity.vehicle.FixedWingVehicle;
import org.ywzj.vehicle.vehicle.LocalVehiclePlayer;
import org.ywzj.vehicle.vehicle.part.DecorationUnit;
import org.ywzj.vehicle.vehicle.part.PartUnit;
import org.ywzj.vehicle.vehicle.part.WeaponUnit;
import org.ywzj.vehicle.vehicle.structure.OBB;
import org.ywzj.vehicle.vehicle.structure.VehicleCubeOBB;
import java.util.ArrayList;
import java.util.List;

/** F3+B/检视 OBB 沿用本体原色；模块一级橙、二级红，取消旧近黑失效描边。 */
public final class RVP_ModuleHitboxRenderer {
    private RVP_ModuleHitboxRenderer() { }

    public static boolean render(AbstractVehicle vehicle, PoseStack pose, MultiBufferSource source) {
        var dispatcher = Minecraft.getInstance().getEntityRenderDispatcher();
        boolean inspect = AllKeys.INSPECT_VEHICLE.isDown();
        boolean all = dispatcher.shouldRenderHitBoxes() || inspect && vehicle == LocalVehiclePlayer.instance.vehicle;
        PartUnit<?> selected = LocalVehiclePlayer.instance.lookAtPartUnit;
        if ((!all && (!inspect || selected == null)) || !RVP_DamagedPartWireframeHelper.hasDamagedBone(vehicle)) return false;
        VertexConsumer buffer = source.getBuffer(RenderType.lines());
        List<PartUnit<?>> parts = new ArrayList<>();
        if (all) {
            if (vehicle.getMainCubeOBB() != null) OBBRenderer.INSTANCE.render(vehicle.position(),
                    List.of(vehicle.getMainCubeOBB().obb()), pose, buffer, 0, 0, 1, 1);
            drawCubes(vehicle, vehicle.getVehicleCubeOBBs(), pose, buffer, 0x00FF00);
            if (vehicle instanceof FixedWingVehicle wing && wing.aerodynamicCubeOBB != null)
                OBBRenderer.INSTANCE.render(vehicle.position(), List.of(wing.aerodynamicCubeOBB.obb()), pose, buffer, 0, 1, 1, 1);
            parts.addAll(vehicle.getPartUnits());
            parts.addAll(vehicle.getDecorationUnits().values());
        } else parts.add(selected);
        for (PartUnit<?> part : parts) {
            if (part.isDetached()) continue;
            int normal = part instanceof WeaponUnit ? 0xFF0000 : part instanceof DecorationUnit ? 0xFF00FF : 0xFFFF00;
            drawCubes(vehicle, part.getPartCubeOBBs(), pose, buffer, normal);
        }
        return true;
    }

    private static void drawCubes(AbstractVehicle vehicle, List<VehicleCubeOBB> cubes, PoseStack pose,
                                  VertexConsumer buffer, int normal) {
        List<List<OBB>> byStage = List.of(new ArrayList<>(), new ArrayList<>(), new ArrayList<>());
        for (VehicleCubeOBB cube : cubes) byStage.get(RVP_DamagedPartWireframeHelper.cubeStage(vehicle, cube)).add(cube.obb());
        for (int stage = 0; stage < byStage.size(); stage++) {
            if (byStage.get(stage).isEmpty()) continue;
            int color = stage == 0 ? normal : RVP_DamagedPartWireframeHelper.chooseFill(true, stage, 0);
            // 调用本体公开 OBB 绘制器，同一档位一次提交，不改变碰撞几何或世界姿态。
            OBBRenderer.INSTANCE.render(vehicle.position(), byStage.get(stage), pose, buffer,
                    ((color >>> 16) & 255) / 255f, ((color >>> 8) & 255) / 255f, (color & 255) / 255f, 1);
        }
    }
}
