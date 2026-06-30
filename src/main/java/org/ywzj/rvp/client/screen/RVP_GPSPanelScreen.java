package org.ywzj.rvp.client.screen;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;
import org.ywzj.rvp.client.state.RVP_ClientGPSState;
import org.ywzj.rvp.client.state.RVP_ClientGPSUtil;

public class RVP_GPSPanelScreen extends Screen {

    private EditBox xBox;
    private EditBox yBox;
    private EditBox zBox;
    private Button modeButton;

    public RVP_GPSPanelScreen() {
        super(Component.translatable("gui.ywzj_rvp.gps.title"));
    }

    @Override
    protected void init() {
        int boxW = 120;
        int boxH = 20;
        int centerX = this.width / 2;
        int centerY = this.height / 2;

        xBox = new EditBox(this.font, centerX - boxW / 2, centerY - 40, boxW, boxH, Component.translatable("gui.ywzj_rvp.gps.coord.x"));
        yBox = new EditBox(this.font, centerX - boxW / 2, centerY - 15, boxW, boxH, Component.translatable("gui.ywzj_rvp.gps.coord.y"));
        zBox = new EditBox(this.font, centerX - boxW / 2, centerY + 10, boxW, boxH, Component.translatable("gui.ywzj_rvp.gps.coord.z"));

        xBox.setMaxLength(32);
        yBox.setMaxLength(32);
        zBox.setMaxLength(32);

        if (RVP_ClientGPSState.isActive()) {
            Vec3 pos = RVP_ClientGPSState.getPos();
            xBox.setValue(String.format("%.2f", pos.x));
            yBox.setValue(String.format("%.2f", pos.y));
            zBox.setValue(String.format("%.2f", pos.z));
        } else if (Minecraft.getInstance().player != null) {
            Vec3 pos = Minecraft.getInstance().player.position();
            xBox.setValue(String.format("%.2f", pos.x));
            yBox.setValue(String.format("%.2f", pos.y));
            zBox.setValue(String.format("%.2f", pos.z));
        }

        addRenderableWidget(xBox);
        addRenderableWidget(yBox);
        addRenderableWidget(zBox);

        int btnW = 54;
        int btnGap = 4;
        int btnY = centerY + 40;
        int rowW = btnW * 4 + btnGap * 3;
        int leftX = centerX - rowW / 2;
        addRenderableWidget(Button.builder(Component.translatable("gui.ywzj_rvp.gps.bind"), b -> bind())
                .bounds(leftX, btnY, btnW, 20)
                .build());

        modeButton = addRenderableWidget(Button.builder(modeLabel(), b -> {
                    Minecraft mc = Minecraft.getInstance();
                    if (mc.player != null) {
                        RVP_ClientGPSUtil.toggleGpsMode(mc.player);
                        b.setMessage(modeLabel());
                    }
                })
                .bounds(leftX + btnW + btnGap, btnY, btnW, 20)
                .build());

        addRenderableWidget(Button.builder(Component.translatable("gui.ywzj_rvp.gps.clear"), b -> clear())
                .bounds(leftX + (btnW + btnGap) * 2, btnY, btnW, 20)
                .build());

        addRenderableWidget(Button.builder(Component.translatable("gui.ywzj_rvp.gps.cancel"), b -> onClose())
                .bounds(leftX + (btnW + btnGap) * 3, btnY, btnW, 20)
                .build());
    }

    private Component modeLabel() {
        return RVP_ClientGPSState.isMultiMode() ? Component.literal("MULTI") : Component.literal("SINGLE");
    }

    private void clear() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) {
            return;
        }
        RVP_ClientGPSUtil.clearGpsTarget(mc.player);
        if (modeButton != null) {
            modeButton.setMessage(modeLabel());
        }
        if (mc.player != null) {
            Vec3 pos = mc.player.position();
            xBox.setValue(String.format("%.2f", pos.x));
            yBox.setValue(String.format("%.2f", pos.y));
            zBox.setValue(String.format("%.2f", pos.z));
        }
    }

    private void bind() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) {
            onClose();
            return;
        }
        try {
            double x = Double.parseDouble(xBox.getValue().trim());
            double y = Double.parseDouble(yBox.getValue().trim());
            double z = Double.parseDouble(zBox.getValue().trim());
            ResourceLocation dim = mc.player.level().dimension().location();
            Vec3 pos = new Vec3(x, y, z);
            RVP_ClientGPSUtil.setGpsTarget(mc.player, dim, pos);
            if (modeButton != null) {
                modeButton.setMessage(modeLabel());
            }
            onClose();
        } catch (NumberFormatException e) {
            if (mc.player != null) {
                mc.player.displayClientMessage(Component.translatable("message.ywzj_rvp.gps.invalid_coords"), true);
            }
        }
    }

    @Override
    public void onClose() {
        Minecraft.getInstance().setScreen(null);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void render(net.minecraft.client.gui.GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        this.renderBackground(guiGraphics);
        guiGraphics.drawCenteredString(this.font, this.title, this.width / 2, this.height / 2 - 70, 0xFFFFFF);
        guiGraphics.drawCenteredString(this.font, Component.translatable("gui.ywzj_rvp.gps.hint"), this.width / 2, this.height / 2 - 58, 0xA0A0A0);
        String stateLine = "MODE " + RVP_ClientGPSUtil.currentModeTag()
                + "  |  " + RVP_ClientGPSUtil.currentPointTag()
                + "  |  CNT " + RVP_ClientGPSState.getPointCount();
        guiGraphics.drawCenteredString(this.font, stateLine, this.width / 2, this.height / 2 - 46, 0xFFF0DF7A);
        super.render(guiGraphics, mouseX, mouseY, partialTick);
    }
}
