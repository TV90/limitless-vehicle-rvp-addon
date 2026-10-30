package org.ywzj.rvp.client.shader;

import com.mojang.blaze3d.pipeline.RenderTarget;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.PostChain;
import net.minecraft.client.renderer.EffectInstance;
import net.minecraft.client.renderer.PostPass;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.ResourceManagerReloadListener;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.AddReloadListenerEvent;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.ywzj.rvp.RVP_MOD;
import org.ywzj.vehicle.client.render.util.PostPassesGetter;

/**
 * 夜视仪（NVG）滤镜后处理（2026-10-31）。
 *
 * <p>链路：光源提取 → 水平模糊 → 垂直模糊 → 合成（像增强 + P43 绿荧光 + 眩光）→ blit。
 * 参照本体热成像 {@code ThermalHandler} 的 PostChain 范式，但**不需要**把实体二次绘制：
 * 夜视是纯色彩映射，直接吃主目标即可。</p>
 *
 * <p><b>时序要点</b>：必须晚于 {@code RVP_ScreenLightPass}（它在 AFTER_LEVEL 用 HIGHEST）。
 * 枪口火花光影、爆炸光源、光电干扰机的暗处红光都是那个 pass 合成进主目标的，
 * 这里用 LOWEST 才能把它们一起纳入眩光提取。若调到 HIGHEST/NORMAL，这些自发光会漏掉。</p>
 *
 * <p><b>光影</b>：2026-10-31 起不再"检测到光影就跳过"——Iris 下 PostChain 有时仍可工作，
 * 先让它真跑，只有在构造或 process 真抛异常时才置 postChainFailed，转由
 * {@link RVP_NvgGuiFallback} 兜底。干扰机光斑走 {@link RVP_NvgGlowPass} 自绘，不依赖本类。</p>
 */
@Mod.EventBusSubscriber(value = Dist.CLIENT, modid = RVP_MOD.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public class RVP_NvgHandler implements ResourceManagerReloadListener {

    private static final ResourceLocation NVG_EFFECT =
            RVP_MOD.resourceLocation("ywzj_rvp:shaders/post/night_vision.json");

    private static PostChain postChain;
    private static int lastWidth;
    private static int lastHeight;
    private static boolean active;
    /** PostChain 已确认不可用（构造或 process 抛过异常）；为 true 时改由 GUI 叠层兜底。 */
    private static boolean postChainFailed;

    public static void setActive(boolean value) {
        if (active != value) {
            active = value;
            if (!value) {
                cleanup();
            }
        }
    }

    public static boolean isActive() {
        return active;
    }

    /** PostChain 确认失败、需要 GUI 叠层兜底时为 true。 */
    public static boolean isFallbackActive() {
        return active && postChainFailed;
    }

    @Override
    public void onResourceManagerReload(ResourceManager resourceManager) {
        // F3+T 后 program 引用会失效，重建一次，避免下一帧拿到已释放的 GL 对象
        cleanup();
    }

    @SubscribeEvent
    public static void onAddReloadListener(AddReloadListenerEvent event) {
        event.addListener(new RVP_NvgHandler());
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onRenderLevel(RenderLevelStageEvent event) {
        if (!active || event.getStage() != RenderLevelStageEvent.Stage.AFTER_LEVEL) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (ensureChain(minecraft)) {
            applyUniforms(minecraft, event.getPartialTick());
            try {
                postChain.process(event.getPartialTick());
                minecraft.getMainRenderTarget().bindWrite(true);
            } catch (Exception exception) {
                exception.printStackTrace();
                cleanup();
                // 保持 active：逻辑上玩家仍开着夜视，只是换 GUI 叠层表现
                postChainFailed = true;
            }
        }
        // 干扰机光斑是自绘 quad，不经过 PostChain：滤镜失败时它照样要画
        RVP_NvgGlowPass.render(event);
    }

    /** 每帧把窗口尺寸、模糊方向、时间推给各 pass（program JSON 里的默认值只在创建时生效）。 */
    private static void applyUniforms(Minecraft minecraft, float partialTick) {
        float width = (float) minecraft.getWindow().getWidth();
        float height = (float) minecraft.getWindow().getHeight();
        float time = minecraft.level == null ? 0.0F
                : (minecraft.level.getGameTime() + partialTick) / 20.0F;
        if (!(postChain instanceof PostPassesGetter getter)) {
            return;
        }
        for (PostPass pass : getter.getPasses()) {
            EffectInstance effect = pass.getEffect();
            if (effect == null) {
                continue;
            }
            String name = pass.getName();
            if (name == null) {
                continue;
            }
            if (effect.safeGetUniform("Resolution") != null) {
                effect.safeGetUniform("Resolution").set(width, height);
            }
            // 两遍模糊共用同一 program，靠 BlurDir 区分横竖
            if (name.endsWith("nvg_blur_h") && effect.safeGetUniform("BlurDir") != null) {
                effect.safeGetUniform("BlurDir").set(1.0F, 0.0F);
            }
            if (name.endsWith("nvg_blur_v") && effect.safeGetUniform("BlurDir") != null) {
                effect.safeGetUniform("BlurDir").set(0.0F, 1.0F);
            }
            if (name.endsWith("nvg_composite") && effect.safeGetUniform("Time") != null) {
                effect.safeGetUniform("Time").set(time);
            }
        }
    }

    private static boolean ensureChain(Minecraft minecraft) {
        // 已确认失败就不再每帧重试刷日志；资源重载或重新开启会经 cleanup 复位
        if (postChainFailed) {
            return false;
        }
        if (postChain == null) {
            try {
                postChain = new PostChain(minecraft.getTextureManager(), minecraft.getResourceManager(),
                        minecraft.getMainRenderTarget(), NVG_EFFECT);
                postChain.resize(minecraft.getWindow().getWidth(), minecraft.getWindow().getHeight());
                lastWidth = minecraft.getWindow().getWidth();
                lastHeight = minecraft.getWindow().getHeight();
            } catch (Exception exception) {
                exception.printStackTrace();
                cleanup();
                postChainFailed = true;
                return false;
            }
        }
        if (lastWidth != minecraft.getWindow().getWidth()
                || lastHeight != minecraft.getWindow().getHeight()) {
            lastWidth = minecraft.getWindow().getWidth();
            lastHeight = minecraft.getWindow().getHeight();
            postChain.resize(lastWidth, lastHeight);
        }
        return true;
    }

    private static void cleanup() {
        if (postChain != null) {
            postChain.close();
            postChain = null;
        }
        // 复位失败标志：资源重载或重新开启夜视时允许再试一次
        postChainFailed = false;
    }
}
