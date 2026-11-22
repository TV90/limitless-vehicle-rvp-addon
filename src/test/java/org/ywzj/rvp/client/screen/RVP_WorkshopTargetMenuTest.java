package org.ywzj.rvp.client.screen;

import com.sighs.apricityui.element.Select;
import com.sighs.apricityui.event.KeyEvent;
import com.sighs.apricityui.init.Document;
import com.sighs.apricityui.init.Element;
import com.sighs.apricityui.parser.HTML;
import org.junit.jupiter.api.Test;
import org.lwjgl.glfw.GLFW;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

class RVP_WorkshopTargetMenuTest {
    @Test void nativePopupUsesWorkshopColorsAndTracksKeyboardSelection() throws IOException {
        String template = "test-workshop-target-menu.html";
        try (var resource = getClass().getResourceAsStream("/assets/apricityui/apricity/screens/rvp_variants.html")) {
            assertNotNull(resource);
            // 无客户端窗口时跳过翻译节点，保留实际改装页的 DOM 与整份样式表。
            String page = new String(resource.readAllBytes(), StandardCharsets.UTF_8);
            HTML.putTemple(template, page.replaceAll("<translation>[^<]*</translation>", ""));
        }
        Document doc = Document.create(template);
        Element label = doc.getElementById("facts").appendChild(doc.createElement("div"));
        label.setClassName("damage-controls");
        Element name = label.appendChild(doc.createElement("span"));
        name.setClassName("fact-name");
        name.setTextContent("威力");
        Select select = (Select) label.appendChild(new Select(doc));
        select.setClassName("damage-select");
        for (String type : new String[]{"默认", "玩家", "生物"}) {
            Element option = select.appendChild(doc.createElement("option"));
            option.setAttribute("value", type);
            option.setTextContent(type);
        }
        select.setValue("默认");
        doc.commitPendingStyleRecalcForRender();
        assertEquals(name.getComputedStyle().fontSize, label.getComputedStyle().gap);
        select.handleClickDefault();
        try {
            doc.commitPendingStyleRecalcForRender();
            var rows = doc.querySelectorAll(".aui-select-option");
            assertEquals(3, rows.size());
            var closed = select.getComputedStyle();
            var chosen = rows.get(0).getComputedStyle();
            var idle = rows.get(1).getComputedStyle();
            assertNotEquals("none", closed.backgroundImage);
            assertEquals(closed.backgroundImage, idle.backgroundImage);
            assertEquals(closed.backgroundColor, doc.querySelector(".aui-select-popup").getComputedStyle().backgroundColor);
            assertEquals(doc.getElementById("vehicle-title").getComputedStyle().backgroundImage, chosen.backgroundImage);
            assertNotEquals(chosen.color, idle.color);
            // 原生列表单独维护键盘指向行；移动时应可见，回车才改变选项。
            assertTrue(press(select, GLFW.GLFW_KEY_DOWN));
            doc.commitPendingStyleRecalcForRender();
            assertEquals("默认", select.getValue());
            assertNotEquals(closed.backgroundImage, rows.get(1).getComputedStyle().backgroundImage);
            assertTrue(press(select, GLFW.GLFW_KEY_ENTER));
            assertEquals("玩家", select.getValue());
            assertFalse(select.isPopupOpen());
        } finally {
            select.closePopup();
        }
        doc.body.setClassName("compact");
        doc.commitPendingStyleRecalcForRender();
        assertEquals(name.getComputedStyle().fontSize, label.getComputedStyle().gap);
    }

    private static boolean press(Select select, int key) {
        return select.handleKeyDownDefault(new KeyEvent(select, "keydown", key, 0, 0, false, KeyEvent.Source.SCREEN_EVENT));
    }
}
