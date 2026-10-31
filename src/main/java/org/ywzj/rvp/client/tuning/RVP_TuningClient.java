package org.ywzj.rvp.client.tuning;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.commands.Commands;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.ConfigScreenHandler;
import net.minecraftforge.client.event.RegisterClientCommandsEvent;
import net.minecraftforge.client.event.InputEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.ModLoader;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.loading.FMLPaths;
import org.lwjgl.glfw.GLFW;
import org.ywzj.rvp.RVP_MOD;
import org.ywzj.rvp.client.RVP_Keys;
import org.ywzj.rvp.client.screen.RVP_TuningScreen;

import java.io.IOException;
import java.util.Map;

/** 物理客户端调参入口；服务端不加载界面、按键或 Minecraft 类型。 */
@Mod.EventBusSubscriber(modid = RVP_MOD.MOD_ID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class RVP_TuningClient {
    /** 客户端自动订阅本工具时即准备独立日志，避免后续初始化失败没有记录。 */
    private static final RVP_TuningLog LOG = new RVP_TuningLog(
            FMLPaths.GAMEDIR.get().resolve("logs").resolve("rvp-tuning.log"));
    static {
        // 调用独立日志记录版本标记，用于判断游戏是否实际载入了本轮新工具。
        LOG.info("BOOT ui=reference-20261031-v5 mod=" + RVP_MOD.MOD_ID
                + " gameDir=" + FMLPaths.GAMEDIR.get().toAbsolutePath() + " java=" + System.getProperty("java.version"));
    }
    /** 所有扩展共用的唯一参数目录；不含具体特效类型判断。 */
    private static final RVP_TuningRegistry REGISTRY = new RVP_TuningRegistry();
    /** 只记住工具的窗口与浏览状态，不跨重启保存，也不引用旧界面或世界。 */
    private static final RVP_TuningViewState VIEW_STATE = new RVP_TuningViewState();
    /** 当前游戏目录内的本地预设，不写载具包或服务端配置。 */
    private static final RVP_TuningPresetStore STORE = new RVP_TuningPresetStore(
            FMLPaths.CONFIGDIR.get().resolve("ywzj_rvp-tuning-client.json"));
    /** 上次成功读取或保存的覆盖，用于提示尚未写盘的调整。 */
    private static Map<String, Double> savedValues = Map.of();
    /** 只发布一次注册事件，也只自动加载一次预设。 */
    private static boolean initialized;
    /** 启动载入反馈；预设错误不会在界面中被误报为成功。 */
    private static String startupMessage = "拖动滑条或输入数值即可调节；悬停查看说明。";

    private RVP_TuningClient() { }

    /** 在客户端 setup 事件的活动模组上下文内登记 Mods 配置按钮。 */
    public static void registerConfigScreen() {
        ModLoadingContext.get().registerExtensionPoint(ConfigScreenHandler.ConfigScreenFactory.class,
                () -> new ConfigScreenHandler.ConfigScreenFactory((minecraft, parent) -> new RVP_TuningScreen(parent)));
    }

    /** 先发布扩展注册事件，再锁定目录并读取当前格式的本地预设。 */
    public static void initialize() {
        if (initialized) { return; }
        try {
            // 调用MOD加载总线同步分发登记事件；setup期间游戏总线尚未start，向其post会直接漏掉所有模块。
            ModLoader.get().postEvent(new RVP_RegisterTuningModulesEvent(REGISTRY));
            LOG.info("REGISTER bus=MOD modules=" + REGISTRY.modules().stream().map(RVP_TuningModule::id).toList()
                    + " options=" + REGISTRY.options().size());
        } catch (RuntimeException | LinkageError failure) {
            LOG.error("REGISTER_FAILED 调参模块注册失败", failure);
            throw failure;
        }
        // 调用目录封存，保证保存与界面生成使用相同的参数集合。
        REGISTRY.seal();
        initialized = true;
        try {
            int ignored = STORE.load(REGISTRY);
            savedValues = REGISTRY.snapshot();
            LOG.info("PRESET_LOAD file=" + STORE.path().toAbsolutePath() + " overrides=" + savedValues + " unknown=" + ignored);
            if (ignored > 0) { startupMessage = "已载入本地预设；忽略 " + ignored + " 个未注册的扩展参数。"; }
        } catch (IOException failure) {
            startupMessage = failure.getMessage();
            LOG.error("PRESET_LOAD_FAILED file=" + STORE.path().toAbsolutePath(), failure);
        }
    }

    public static RVP_TuningRegistry registry() { return REGISTRY; }
    /** 新面板从同一份会话记忆恢复，业务参数仍走原来的覆盖表。 */
    public static RVP_TuningViewState viewState() { return VIEW_STATE; }
    /** 界面与扩展共用同一个文件，不再让错误分散到其它调试日志。 */
    public static RVP_TuningLog log() { return LOG; }
    public static String startupMessage() { return startupMessage; }

    /** 保存后才跨游戏重启保留；单纯关闭面板仍只是会话调整。 */
    public static void save() throws IOException {
        // 调用原子文件保存，写盘成功后才更新“已保存”的比较基线。
        try {
            STORE.save(REGISTRY);
            savedValues = REGISTRY.snapshot();
            LOG.info("PRESET_SAVE file=" + STORE.path().toAbsolutePath() + " overrides=" + savedValues);
        } catch (IOException | RuntimeException failure) {
            LOG.error("PRESET_SAVE_FAILED file=" + STORE.path().toAbsolutePath(), failure);
            throw failure;
        }
    }

    /** 从业务绑定读取状态，命令产生的修改也计入尚未保存提示。 */
    public static boolean hasUnsavedChanges() { return !REGISTRY.snapshot().equals(savedValues); }

    /** 只有本地界面变化，不向服务器申请权限或发送指令。 */
    public static void open(Screen parent) {
        try {
            LOG.info("OPEN_REQUEST parent=" + (parent == null ? "game" : parent.getClass().getName())
                    + " smoothCamera=" + Minecraft.getInstance().options.smoothCamera);
            Minecraft.getInstance().setScreen(new RVP_TuningScreen(parent));
        } catch (RuntimeException | LinkageError failure) {
            LOG.error("OPEN_FAILED 调参面板未能打开", failure);
            throw failure;
        }
    }

    /** 键盘队列刚写入时先消费同键平滑视角动作，防止第一次F8打开工具顺带开启电影式视角。 */
    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onKeyInput(InputEvent.Key event) {
        Minecraft minecraft = Minecraft.getInstance();
        if (event.getAction() != GLFW.GLFW_PRESS || minecraft.screen != null || minecraft.player == null) { return; }
        InputConstants.Key pressed = InputConstants.getKey(event.getKey(), event.getScanCode());
        // 只处理与打开工具完全相同的有效绑定；平滑视角绑定其它键时继续保持原版行为。
        boolean openingPanel = RVP_Keys.OPEN_TUNING.isActiveAndMatches(pressed);
        boolean sameCameraKey = minecraft.options.keySmoothCamera.isActiveAndMatches(pressed);
        // 调用独立输入策略处理点击队列，实际设置不参与此策略，因而不会被强制关闭。
        int consumed = RVP_TuningInputGuard.consumeCameraConflict(openingPanel, sameCameraKey,
                minecraft.options.keySmoothCamera::consumeClick);
        // 不强行修改smoothCamera设置，保留玩家原先主动选择的平滑视角状态。
        if (openingPanel && sameCameraKey) {
            LOG.info("HOTKEY_CONFLICT_SUPPRESSED key=" + event.getKey() + " smoothClicks=" + consumed
                    + " smoothCamera=" + minecraft.options.smoothCamera);
        }
    }

    /** 单独消费通用工具按键，避免被载具座位或手持武器条件挡住。 */
    @SubscribeEvent
    public static void onTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) { return; }
        Minecraft minecraft = Minecraft.getInstance();
        // 调用统一按键定义，玩家可在原版控制设置里改键。
        while (RVP_Keys.OPEN_TUNING.consumeClick()) {
            if (minecraft.player != null && minecraft.screen == null) { open(null); }
        }
    }

    /** 聊天关闭后才打开面板，避免客户端指令执行完成时又被聊天界面清空。 */
    @SubscribeEvent
    public static void registerCommands(RegisterClientCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("rvptune").executes(context -> {
            // 排入下一主线程任务；复用同一个面板入口，不发送服务端聊天命令。
            Minecraft.getInstance().tell(() -> open(null));
            return 1;
        }));
    }
}
