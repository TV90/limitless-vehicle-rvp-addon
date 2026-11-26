package org.ywzj.rvp.client.shader;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.DimensionSpecialEffects;
import net.minecraft.client.renderer.PostChain;
import net.minecraft.client.renderer.PostPass;
import net.minecraft.util.Mth;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.ywzj.rvp.client.state.RVP_ClientHitlState;
import org.ywzj.rvp.client.state.RVP_ThermalImagerSelection;
import org.ywzj.rvp.config.RVP_ThermalImagerType;
import org.ywzj.vehicle.client.render.util.PostPassesGetter;
import org.ywzj.vehicle.vehicle.LocalVehiclePlayer;
import org.ywzj.vehicle.vehicle.part.WeaponUnit;

import java.io.IOException;
import java.lang.ref.WeakReference;
import java.util.List;

/** 复用本体热成像的两个处理阶段。先合成灰阶、场景亮度与光源掩码，再按当前视角的热像类型输出。 */
public final class RVP_ThermalImagerPass extends PostPass {
    /** 同一条资源链加载失败只报告一次，重载后允许重试。 */
    private static WeakReference<PostChain> failedChain = new WeakReference<>(null);
    /** 输出阶段记录已写入的显示类型。 */
    private RVP_ThermalImagerType type = RVP_ThermalImagerType.WHITE_HOT;

    private RVP_ThermalImagerPass(PostPass original, boolean composeScene, RenderTarget heat) throws IOException {
        super(Minecraft.getInstance().getResourceManager(), "ywzj_rvp:thermal_imager_palette",
                original.inTarget, original.outTarget);
        if (composeScene) {
            addAuxAsset("ThermalSampler", heat::getColorTextureId, heat.width, heat.height);
            // 热缓冲保有合成前复制的场景深度，观瞄 CRT 回写主画面时不会清掉它。
            addAuxAsset("SceneDepth", heat::getDepthTextureId, heat.width, heat.height);
            getEffect().safeGetUniform("ComposeScene").set(1);
        }
        // 与 PostChain.resize 使用同一投影；本帧已写入的热源和深度不能因安装调色而重建。
        setOrthoMatrix(new Matrix4f().setOrtho(0, outTarget.width, 0, outTarget.height, 0.1F, 1000F));
    }

    /** 热缓冲就绪后准备本帧的灰阶、光源与本站显示方式。 */
    public static void configure(PostChain chain, RenderTarget heat, RenderLevelStageEvent event) {
        if (!(chain instanceof PostPassesGetter getter)) return;
        List<PostPass> passes = getter.getPasses();
        if (passes.size() != 2 || failedChain.get() == chain) return;
        if (!(passes.get(0) instanceof RVP_ThermalImagerPass) && !install(chain, passes, heat)) {
            return;
        }
        RVP_ThermalImagerPass scene = (RVP_ThermalImagerPass) passes.get(0);
        RVP_ThermalImagerPass palette = (RVP_ThermalImagerPass) passes.get(1);
        scene.updateSky(event);
        RVP_ThermalImagerType selected = stationType();
        if (palette.type != selected) {
            palette.type = selected;
            palette.getEffect().safeGetUniform("Palette").set(selected.shaderMode);
        }
    }

    private static boolean install(PostChain chain, List<PostPass> passes, RenderTarget heat) {
        PostPass composite = passes.get(0);
        PostPass output = passes.get(1);
        RenderTarget main = Minecraft.getInstance().getMainRenderTarget();
        RenderTarget swap = chain.getTempTarget("swap");
        // 复用本体 main → swap → main。资源包改写过的热成像链维持自己的处理方式。
        if (!"ywzj_vehicle:thermal".equals(composite.getName()) || composite.inTarget != main || composite.outTarget != swap
                || !("blit".equals(output.getName()) || "minecraft:blit".equals(output.getName()))
                || output.inTarget != swap || output.outTarget != main) return false;

        RVP_ThermalImagerPass scene = null;
        RVP_ThermalImagerPass palette;
        try {
            scene = new RVP_ThermalImagerPass(composite, true, heat);
            palette = new RVP_ThermalImagerPass(output, false, heat);
        } catch (IOException | RuntimeException e) {
            if (scene != null) scene.close();
            failedChain = new WeakReference<>(chain);
            LogUtils.getLogger().warn("[RVP] 热像调色加载失败，继续使用本体热像", e);
            return false;
        }
        passes.set(0, scene);
        passes.set(1, palette);
        composite.close();
        output.close();
        return true;
    }

    private void updateSky(RenderLevelStageEvent event) {
        var level = Minecraft.getInstance().level;
        var sky = getEffect().safeGetUniform("SkyLight");
        if (level == null || level.effects().skyType() != DimensionSpecialEffects.SkyType.NORMAL) {
            sky.set(0.0F, 0.0F, 0.0F, 0.0F);
            return;
        }
        float partialTick = event.getPartialTick();
        // 原版天空绕 Y 轴转 -90° 再绕 X 轴转日夜角；月亮在太阳的对侧。
        float angle = level.getTimeOfDay(partialTick) * Mth.TWO_PI;
        Vector3f solar = new Vector3f(-Mth.sin(angle), Mth.cos(angle), 0.0F);
        event.getPoseStack().last().pose().transformDirection(solar).normalize();
        sky.set(solar.x, solar.y, solar.z, 1.0F - level.getRainLevel(partialTick));
        Matrix4f projection = event.getProjectionMatrix();
        getEffect().safeGetUniform("ViewScale").set(1.0F / projection.m00(), 1.0F / projection.m11());
    }

    private static RVP_ThermalImagerType stationType() {
        if (RVP_ClientHitlState.isActive()) return RVP_ClientHitlState.getVideoThermalType();
        LocalVehiclePlayer player = LocalVehiclePlayer.instance;
        if (player == null || player.viewType != LocalVehiclePlayer.ViewType.SCOPE
                || !player.onVehicle()) return RVP_ThermalImagerType.WHITE_HOT;
        WeaponUnit station = player.getWeaponUnit();
        return station != null && station.withThermalImager()
                ? RVP_ThermalImagerSelection.selected(station) : RVP_ThermalImagerType.WHITE_HOT;
    }

}
