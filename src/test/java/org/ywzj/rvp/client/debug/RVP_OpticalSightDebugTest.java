package org.ywzj.rvp.client.debug;

import com.mojang.brigadier.CommandDispatcher;
import net.minecraft.commands.CommandSourceStack;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/** 回归实机诊断的默认关闭、独立文件、追加保留、错误隔离与实际指令语法。 */
class RVP_OpticalSightDebugTest {
    @Test
    void disabledLoggingCreatesNoFileAndHooksDoNotReadGameState(@TempDir Path directory) {
        Path target = directory.resolve("logs/rvp_optical_sight_debug.log");
        RVP_OpticalSightLogFile log = new RVP_OpticalSightLogFile(target);
        // 调用正式关闭态文件与输入钩子；不启动游戏也必须立即返回，且没有日志文件。
        log.write("INPUT", "unused");
        log.flush();
        log.close("already_off");
        assertFalse(log.isOpen());
        assertFalse(Files.exists(target.getParent()));
        assertEquals(0L, RVP_OpticalSightDebug.beforeInput(1, 1));
        assertDoesNotThrow(() -> RVP_OpticalSightDebug.opticalCamera("unused", null, null));
    }

    @Test
    void stoppingFlushesUtf8AndNewSessionAppendsWithoutLosingPreviousEvidence(@TempDir Path directory) throws Exception {
        Path target = directory.resolve("rvp_optical_sight_debug.log");
        RVP_OpticalSightLogFile log = new RVP_OpticalSightLogFile(target);
        // 调用真实文件生命周期，标记里的换行不能伪造另一条记录。
        log.open("test=一");
        log.write("MARK", "正下方\n向左\r移动");
        log.write("INPUT", "input_id=1 yaw_input=-0.2 pitch_input=0.0");
        log.close("command_off");
        String first = Files.readString(target, StandardCharsets.UTF_8);
        assertFalse(log.isOpen());
        assertTrue(first.contains("stage=MARK 正下方 向左 移动"));
        assertTrue(first.contains("stage=SESSION_END"));
        assertTrue(first.contains("elapsed_ns="));
        log.write("INPUT", "must_not_appear");
        assertEquals(first, Files.readString(target, StandardCharsets.UTF_8));
        log.open("test=二");
        log.write("MARK", "后下方向右");
        log.close("logout");
        String second = Files.readString(target, StandardCharsets.UTF_8);
        assertTrue(second.startsWith(first));
        assertTrue(second.contains("test=二"));
        assertTrue(second.contains("后下方向右"));
        assertFalse(second.contains("\uFFFD"));
        assertNull(log.failure());
    }

    @Test
    void fileFailureStopsOnlyDiagnosticsAndDoesNotThrow(@TempDir Path directory) {
        // 用目录充当文件制造真实写入失败，不能把异常抛到输入/渲染调用方。
        RVP_OpticalSightLogFile log = new RVP_OpticalSightLogFile(directory);
        assertDoesNotThrow(() -> log.open("unwritable"));
        assertFalse(log.isOpen());
        assertNotNull(log.failure());
        assertDoesNotThrow(() -> log.write("INPUT", "ignored"));
        assertDoesNotThrow(log::flush);
        assertDoesNotThrow(() -> log.close("off"));
    }

    @Test
    void clientCommandParsesOnOffStatusAndChineseMarksWithoutPermissionGate() {
        CommandDispatcher<CommandSourceStack> dispatcher = new CommandDispatcher<>();
        // 调用实际注册的命令树，仅解析不执行依赖玩家的回调；不要求服务器管理员权限。
        dispatcher.register(RVP_OpticalSightDebug.build());
        for (String command : new String[]{"rvpopticaldiag", "rvpopticaldiag on", "rvpopticaldiag off",
                "rvpopticaldiag status", "rvpopticaldiag mark 正下方 只向左移动"}) {
            var parsed = dispatcher.parse(command, null);
            assertFalse(parsed.getReader().canRead(), command);
            assertTrue(parsed.getExceptions().isEmpty(), command);
        }
    }
}
