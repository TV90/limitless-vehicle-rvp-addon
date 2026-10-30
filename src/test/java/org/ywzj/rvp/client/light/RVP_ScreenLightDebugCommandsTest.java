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
                "rvplight engine 0.3 300", "rvplight clear", "rvplight help", "rvplight copy",
                "rvplight set engine haloIntensity 8", "rvplight status engine coreIntensity",
                "rvplight reset engine radiusScale", "rvplight reset"}) {
            var parsed = dispatcher.parse(command, null);
            assertFalse(parsed.getReader().canRead(), command);
            assertTrue(parsed.getExceptions().isEmpty(), command);
            assertNotNull(parsed.getContext().getCommand(), command);
        }
        for (String command : new String[] {"rvplight set engine coreIntensity 60001",
                "rvplight set engine radiusScale -1", "rvplight set explosion coreIntensity 2"}) {
            var parsed = dispatcher.parse(command, null);
            assertTrue(parsed.getReader().canRead(), command);
        }
    }
}
