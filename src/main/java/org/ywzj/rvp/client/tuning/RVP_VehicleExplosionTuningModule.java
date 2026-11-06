package org.ywzj.rvp.client.tuning;

import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.ywzj.rvp.RVP_MOD;
import org.ywzj.rvp.client.visual.explosion.RVP_VehicleExplosionPreview;
import org.ywzj.rvp.client.visual.explosion.RVP_VehicleExplosionSettings;
import org.ywzj.rvp.client.visual.explosion.RVP_VehicleExplosionSettings.Parameter;

import java.util.Arrays;
import java.util.List;

/** F8程序大爆炸的独立美术分类；复用现有保存、搜索、撤销及观察画面功能。 */
@Mod.EventBusSubscriber(modid = RVP_MOD.MOD_ID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.MOD)
public final class RVP_VehicleExplosionTuningModule implements RVP_TuningModule {
    /** 按业务枚举生成的固定目录；登记时不启动预览、不写默认覆盖。 */
    private final List<Group> groups;

    public RVP_VehicleExplosionTuningModule() {
        // 调用唯一参数表构建控件；每组都提供预览和停止操作，通用界面不增加特殊判断。
        groups = Arrays.stream(RVP_VehicleExplosionSettings.Group.values()).map(group -> new Group(
                group.id, group.title, "仅本机无伤害预览；除放置距离外下一帧生效。右键组标题可预览或停止。",
                Arrays.stream(Parameter.values()).filter(parameter -> parameter.group == group)
                        .map(RVP_VehicleExplosionTuningModule::option).toList(),
                List.of(new Action("在准星处预览 / 重播", "生成一团世界定位的程序爆炸，替换旧预览；不击毁车辆。",
                                RVP_VehicleExplosionTuningModule::preview, true),
                        new Action("停止爆炸预览", "立即清除本地预览，保留当前调参值。", () -> {
                            // 调用独立预览清理，只移除本轮手动效果。
                            RVP_VehicleExplosionPreview.INSTANCE.clear();
                            return "已停止程序大爆炸预览。";
                        })))).toList();
    }

    /** 由MOD加载总线登记，首次打开F8即可看见分类。 */
    @SubscribeEvent
    public static void register(RVP_RegisterTuningModulesEvent event) {
        // 调用既有扩展事件，不修改通用面板及真实爆炸注册链。
        event.register(new RVP_VehicleExplosionTuningModule());
    }

    @Override public String id() { return "vehicle_explosion"; }
    @Override public String title() { return "程序大爆炸"; }
    @Override public String description() { return "屏幕空间体积火球、翻卷黑烟与热浪；当前仅F8手动预览。"; }
    @Override public List<Group> groups() { return groups; }

    private static RVP_TuningOption option(Parameter parameter) {
        // 调用业务读写和清理入口；本地保存及撤销直接复用相同覆盖。
        return new RVP_TuningOption(parameter.id, parameter.title, parameter.description,
                parameter.unit, parameter.toggle() ? RVP_TuningOption.Control.TOGGLE : RVP_TuningOption.Control.NUMBER,
                parameter.min, parameter.max, 0, () -> parameter.defaultValue,
                () -> RVP_VehicleExplosionSettings.override(parameter),
                value -> RVP_VehicleExplosionSettings.set(parameter, value),
                () -> RVP_VehicleExplosionSettings.reset(parameter));
    }

    /** 只从用户明确点击的面板操作进入，不接车辆击毁事件或爆炸网络包。 */
    private static String preview() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) { throw new IllegalStateException("需要先进入世界才能预览大爆炸。"); }
        double distance = RVP_VehicleExplosionSettings.value(Parameter.DISTANCE);
        HitResult hit = mc.player.pick(distance, 1F, false);
        Vec3 position = hit.getLocation();
        if (hit instanceof BlockHitResult block && hit.getType() == HitResult.Type.BLOCK) {
            position = position.add(Vec3.atLowerCornerOf(block.getDirection().getNormal()).scale(0.15));
        }
        // 调用本地预览时间线，所有视觉都由客户端计算，不发管理指令或伤害请求。
        RVP_VehicleExplosionPreview.INSTANCE.start(mc.level, position, mc.level.getGameTime() / 20.0);
        return "已在准星方向预览程序大爆炸；F8返回调参，开启定格可逐阶段观察。";
    }
}
