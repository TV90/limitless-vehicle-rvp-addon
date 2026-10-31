package org.ywzj.rvp.client.tuning;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.components.EditBox;
import org.junit.jupiter.api.Test;
import org.ywzj.rvp.client.tuning.RVP_TuningTheme.CompactNumberEditBox;
import org.ywzj.rvp.light.RVP_LightRuntimeTuning;

import java.lang.reflect.Field;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** 直接使用原版输入框的光标与横向滚动逻辑，不需要启动Minecraft或创建显卡窗口。 */
class RVP_TuningNumberInputTest {
    @Test
    void draggingLongDecimalsKeepsWholeNumberVisibleInNarrowField() throws ReflectiveOperationException {
        CompactNumberEditBox input = new CompactNumberEditBox(measuringFont(), "测试参数");
        input.setWidth(37);
        for (double value : new double[] {9.142857, 66.031746, 99.99999, 100.0001, 210.28572, 256}) {
            // 调用滑条使用的同一数值回填入口，保留完整文本并检查原版实际的横向滚动位置。
            input.syncValue(RVP_TuningOption.format(value));
            assertEquals(RVP_TuningOption.format(value), input.getValue());
            assertEquals(0, firstVisibleCharacter(input), "未编辑时应显示整数开头：" + input.getValue());
        }
    }

    @Test
    void readbackAndCursorMovementDoNotWriteRoundedTextButRealEditingWritesOnce() {
        try {
            // 调用真实亮度业务绑定，复现日志中66.031746因移动光标被误写成66.03175的问题。
            RVP_TuningOption brightness = new RVP_ScreenSpaceTuningModule().groups().stream()
                    .filter(group -> group.id().equals("explosion")).flatMap(group -> group.options().stream())
                    .filter(option -> option.id().equals("brightness")).findFirst().orElseThrow();
            brightness.set(66.031746);
            double actualValue = brightness.value();
            CompactNumberEditBox input = new CompactNumberEditBox(measuringFont(), "测试亮度");
            input.setWidth(37);
            AtomicInteger writes = new AtomicInteger();
            input.setResponder(text -> {
                writes.incrementAndGet();
                // 调用真实写入，只有用户改变文本才能改变业务值或制造覆盖。
                brightness.set(Double.parseDouble(text));
            });
            input.syncValue(RVP_TuningOption.format(actualValue));
            input.setFocused(true);
            input.moveCursorToEnd();
            input.moveCursorToStart();
            input.setHighlightPos(3);
            assertEquals(0, writes.get());
            assertEquals(actualValue, brightness.value());

            input.moveCursorToEnd();
            input.setHighlightPos(0);
            input.insertText("72.125");
            assertEquals(1, writes.get());
            assertEquals(72.125, brightness.value());
            input.syncValue("128.1235");
            assertEquals(1, writes.get(), "后续滑条回填不能触发用户编辑回调");
        } finally {
            // 调用会话覆盖清理，避免真实业务绑定测试影响其它光照测试。
            RVP_LightRuntimeTuning.reset();
        }
    }

    @Test
    void leavingManualEditingAndResizingKeepTheWholeNumberAtTheStart() throws ReflectiveOperationException {
        CompactNumberEditBox input = new CompactNumberEditBox(measuringFont(), "测试参数");
        input.setWidth(37);
        input.setFocused(true);
        input.setValue("210.28572");
        input.moveCursorToEnd();
        input.setFocused(false);
        assertEquals(0, firstVisibleCharacter(input));
        input.setWidth(80);
        input.syncValue("210.28572");
        input.setWidth(37);
        input.syncValue("210.28572");
        assertEquals("210.28572", input.getValue());
        assertEquals(0, firstVisibleCharacter(input));
    }

    /** 原版没有公开可见字符起点，测试只读该字段以核对真实滚动结果，不修改或替换原版行为。 */
    private static int firstVisibleCharacter(EditBox input) throws ReflectiveOperationException {
        Field displayPosition = EditBox.class.getDeclaredField("displayPos");
        displayPosition.setAccessible(true);
        return displayPosition.getInt(input);
    }

    /** 只替换字宽测量，输入、光标移动、选区和滚动全部使用真实EditBox实现。 */
    private static Font measuringFont() {
        return new Font(location -> null, false) {
            @Override public int width(String text) { return text.length() * 6; }
            @Override public String plainSubstrByWidth(String text, int width, boolean reverse) {
                int length = Math.min(text.length(), Math.max(0, width / 6));
                return reverse ? text.substring(text.length() - length) : text.substring(0, length);
            }
            @Override public String plainSubstrByWidth(String text, int width) {
                return plainSubstrByWidth(text, width, false);
            }
        };
    }
}
