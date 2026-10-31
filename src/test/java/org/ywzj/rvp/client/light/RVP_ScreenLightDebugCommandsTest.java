package org.ywzj.rvp.client.light;

import com.mojang.brigadier.CommandDispatcher;
import net.minecraft.commands.CommandSourceStack;
import org.junit.jupiter.api.Test;
import org.ywzj.rvp.light.RVP_LightRuntimeTuning.Group;
import org.ywzj.rvp.light.RVP_LightRuntimeTuning.Parameter;

import static org.junit.jupiter.api.Assertions.*;

/** 验证真正注册的命令树、参数范围和原有预览语法，不执行依赖玩家的回调。 */
class RVP_ScreenLightDebugCommandsTest {
    @Test
    void everySupportedParameterHasSetStatusAndResetAndUnsupportedPairsAreAbsent() {
        // 调用真实命令树与参数适用性定义，检查每个有效参数同时具备修改、查询和重置入口。
        CommandDispatcher<CommandSourceStack> dispatcher = new CommandDispatcher<>();
        dispatcher.register(RVP_ScreenLightDebugCommands.build());
        var root = dispatcher.getRoot().getChild("rvplight");
        for (Group group : Group.values()) {
            for (Parameter parameter : Parameter.values()) {
                for (String operation : new String[] {"set", "status", "reset"}) {
                    var node = root.getChild(operation).getChild(group.name()).getChild(parameter.name());
                    assertEquals(parameter.supports(group), node != null, operation + " " + group + " " + parameter);
                    if (node != null) { assertNotNull(node.getCommand()); }
                }
            }
        }
    }

    @Test
    void originalPreviewSyntaxAndTuningCommandsParseCompletelyAndBadRangesAreRejected() {
        // 调用正式命令构造后交给游戏同款解析器，验证旧语法可用且越界值无法完成解析。
        CommandDispatcher<CommandSourceStack> dispatcher = new CommandDispatcher<>();
        dispatcher.register(RVP_ScreenLightDebugCommands.build());
        for (String command : new String[] {"rvplight preview", "rvplight preview 8", "rvplight engine",
                "rvplight engine 0.3 300", "rvplight clear", "rvplight help", "rvplight copy", "rvplight occlusion",
                "rvplight isolate explosion", "rvplight isolate muzzle", "rvplight isolate engine", "rvplight isolate off",
                "rvplight set engine haloIntensity 8", "rvplight status engine coreIntensity",
                "rvplight set engine worldHaloIntensity 4", "rvplight set bloom nearStrength 0",
                "rvplight set explosion worldHaloIntensity 16", "rvplight set explosion worldHaloIntensity 0",
                "rvplight status explosion worldHaloIntensity", "rvplight reset explosion worldHaloIntensity",
                "rvplight set bloom farStrength 2", "rvplight reset bloom farStrength",
                "rvplight set explosion haloRadiusScale 1024", "rvplight set muzzle radiusScale 0",
                "rvplight set engine haloRadiusScale 0", "rvplight set engine radiusScale 1024",
                "rvplight set engine worldHaloIntensity 60000", "rvplight set bloom nearStrength 256",
                "rvplight set bloom farStrength 256", "rvplight set global brightness 256",
                "rvplight set global distanceScale 128", "rvplight set explosion decayScale 0",
                "rvplight set global haloDecayCurve 2", "rvplight set explosion haloDecayCurve 1",
                "rvplight set muzzle haloDecayCurve 0", "rvplight set engine haloDecayCurve 2",
                "rvplight set explosion haloDropFraction 0.1", "rvplight set explosion haloLowBrightness 0.15",
                "rvplight set explosion haloHoldUntil 0.8", "rvplight reset explosion haloDecayCurve",
                "rvplight status global haloDecayCurve", "rvplight preview 8192", "rvplight engine 256 300",
                "rvplight set explosion haloDecayCurve 3", "rvplight set explosion haloPeakHoldTicks 3",
                "rvplight set explosion haloFadeOutTicks 29",
                "rvplight set explosion haloShrink 1", "rvplight status explosion haloShrink",
                "rvplight reset explosion haloShrink",
                "rvplight set explosion skyPeakBrightness 0.8", "rvplight set explosion skyContract 0.6",
                "rvplight status explosion skyPeakBrightness", "rvplight reset explosion skyContract",
                "rvplight skydebug", "rvplight skydebug on", "rvplight skydebug off", "rvplight skydebug status",
                "rvplight set explosion coreIntensity 60000", "rvplight set explosion coreHaloIntensity 12",
                "rvplight status explosion coreRadius", "rvplight set explosion coreRadius 0.8",
                "rvplight set explosion coreRadiusScale 1", "rvplight set explosion coreHaloScale 2",
                "rvplight set explosion coreShrink 0.5", "rvplight set explosion coreHaloRadius 3",
                "rvplight reset explosion coreShrink", "rvplight reset explosion coreHaloScale",
                "rvplight set global skyDistance 2048", "rvplight status global skyDistance",
                "rvplight reset global skyDistance",
                "rvplight set explosion skyHoldTicks 3", "rvplight set explosion skyFadeTicks 29",
                "rvplight status explosion skyHoldTicks", "rvplight reset explosion skyFadeTicks",
                "rvplight reset engine radiusScale", "rvplight reset"}) {
            var parsed = dispatcher.parse(command, null);
            assertFalse(parsed.getReader().canRead(), command);
            assertTrue(parsed.getExceptions().isEmpty(), command);
            assertNotNull(parsed.getContext().getCommand(), command);
        }
        for (String command : new String[] {"rvplight set engine coreIntensity 60001",
                "rvplight set engine worldHaloIntensity 60001", "rvplight set bloom nearStrength -1",
                "rvplight set explosion worldHaloIntensity 60001", "rvplight set muzzle worldHaloIntensity 16",
                "rvplight set bloom farStrength 257", "rvplight set engine nearStrength 1",
                "rvplight set explosion haloRadiusScale -1", "rvplight set muzzle haloRadiusScale 1025",
                "rvplight set engine radiusScale -1", "rvplight set bloom coreIntensity 2",
                "rvplight set global haloDecayCurve 4", "rvplight set explosion haloDropFraction 0",
                "rvplight set explosion haloPeakHoldTicks -1", "rvplight set explosion haloFadeOutTicks 641",
                "rvplight set explosion haloHoldUntil 1", "rvplight set explosion haloLowBrightness -0.1",
                "rvplight set explosion haloShrink 1.5", "rvplight set engine haloShrink 1",
                "rvplight set explosion skyPeakBrightness 8.5", "rvplight set explosion skyContract -0.1",
                "rvplight set engine skyPeakBrightness 1", "rvplight skydebug unknown",
                "rvplight set explosion coreRadius 65", "rvplight set explosion coreShrink 1.5",
                "rvplight set explosion coreHaloRadius 129", "rvplight set engine coreRadius 1",
                "rvplight set explosion coreRadiusScale 9", "rvplight set explosion coreHaloScale 9",
                "rvplight set explosion coreHaloIntensity 60001", "rvplight set explosion haloIntensity 12",
                "rvplight set global skyDistance -1", "rvplight set global skyDistance 32769",
                "rvplight set explosion skyHoldTicks 41", "rvplight set explosion skyFadeTicks 641"}) {
            var parsed = dispatcher.parse(command, null);
            assertTrue(parsed.getReader().canRead(), command);
        }
    }
}

