package org.ywzj.rvp.client.light;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 独立追踪日志：UTF-8 无 BOM、追加会话、超限轮转一代、写入失败不抛异常。 */
class RVP_SkyScatterLogFileTest {
    @Test
    void writesUtf8WithoutBomAndKeepsBothSessions(@TempDir Path directory) throws IOException {
        Path file = directory.resolve("logs").resolve("rvp-sky-scatter.log");
        RVP_SkyScatterLogFile first = new RVP_SkyScatterLogFile(file, 0);
        first.open("会话一 天空散射");
        first.write("事件到达 位置=(1.0, 2.0, 3.0)");
        first.close();
        assertFalse(first.failed());
        RVP_SkyScatterLogFile second = new RVP_SkyScatterLogFile(file, 0);
        second.open("会话二");
        second.write("本帧 提交=1 天空灯=1");
        second.close();
        byte[] bytes = Files.readAllBytes(file);
        // 不写 BOM：首字节必须直接是行首的 '['。
        assertEquals('[', (char) bytes[0]);
        String text = new String(bytes, StandardCharsets.UTF_8);
        // 中文必须按 UTF-8 正确往返；出现替换字符说明编码被打回平台默认编码。
        assertFalse(text.contains("\uFFFD"), text);
        assertTrue(text.contains("会话一 天空散射"), text);
        assertTrue(text.contains("事件到达 位置=(1.0, 2.0, 3.0)"), text);
        assertTrue(text.contains("会话二"), text);
        assertTrue(text.contains("本帧 提交=1 天空灯=1"), text);
        // 每行都带毫秒级时间戳，便于和游戏画面时序对齐。
        assertTrue(text.matches("(?s).*\\[\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}\\.\\d{3}\\].*"), text);
    }

    @Test
    void oversizedFileRotatesToSingleOldGeneration(@TempDir Path directory) throws IOException {
        Path file = directory.resolve("rvp-sky-scatter.log");
        RVP_SkyScatterLogFile first = new RVP_SkyScatterLogFile(file, 16);
        first.open("第一代 内容足够长");
        first.close();
        RVP_SkyScatterLogFile second = new RVP_SkyScatterLogFile(file, 16);
        second.open("第二代");
        second.close();
        Path old = file.resolveSibling(file.getFileName() + ".old");
        assertTrue(Files.isRegularFile(old), "超过阈值后应转存一代旧文件");
        assertTrue(Files.readString(old, StandardCharsets.UTF_8).contains("第一代"));
        String current = Files.readString(file, StandardCharsets.UTF_8);
        assertTrue(current.contains("第二代"));
        assertFalse(current.contains("第一代"), current);
    }

    @Test
    void unwritableTargetOnlyReportsFailureWithoutThrowing(@TempDir Path directory) throws IOException {
        // 目标本身是目录，必然无法按文件打开；追踪不能把异常抛给渲染线程。
        Path target = directory.resolve("occupied");
        Files.createDirectories(target);
        RVP_SkyScatterLogFile log = new RVP_SkyScatterLogFile(target, 0);
        assertDoesNotThrow(() -> log.open("会话"));
        assertTrue(log.failed());
        assertDoesNotThrow(() -> log.write("本帧 提交=0"));
        assertDoesNotThrow(log::close);
    }
}
