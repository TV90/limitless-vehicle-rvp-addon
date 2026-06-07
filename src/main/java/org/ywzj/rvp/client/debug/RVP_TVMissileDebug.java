package org.ywzj.rvp.client.debug;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import org.lwjgl.opengl.GL30;
import org.slf4j.Logger;
import org.ywzj.rvp.client.shader.TVMissileVideoPostHandler;
import org.ywzj.rvp.client.state.RVP_ClientHitlState;
import org.ywzj.rvp.entity.projectile.RVP_MissileEntity;
import org.ywzj.vehicle.client.shader.CrtHandler;
import org.ywzj.vehicle.client.shader.ThermalHandler;
import org.ywzj.vehicle.vehicle.LocalVehiclePlayer;

import java.util.concurrent.atomic.AtomicBoolean;

public class RVP_TVMissileDebug {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final AtomicBoolean dumpRequested = new AtomicBoolean(false);
    private static volatile boolean bwSpamEnabled = false;
    private static int bwSpamLastTick = Integer.MIN_VALUE;

    public static void requestDump() {
        dumpRequested.set(true);
    }

    public static void setBwSpamEnabled(boolean enabled) {
        bwSpamEnabled = enabled;
        bwSpamLastTick = Integer.MIN_VALUE;
        LOGGER.info("[RVP][TVMissile][BW] spam {}", enabled ? "enabled" : "disabled");
    }

    public static boolean isBwSpamEnabled() {
        return bwSpamEnabled;
    }

    public static boolean shouldSpamBw(Minecraft mc) {
        if (!bwSpamEnabled || mc.player == null) {
            return false;
        }
        int tick = mc.player.tickCount;
        if (tick == bwSpamLastTick) {
            return false;
        }
        // Keep it readable: one line every 5 ticks while enabled.
        if (bwSpamLastTick != Integer.MIN_VALUE && tick - bwSpamLastTick < 5) {
            return false;
        }
        bwSpamLastTick = tick;
        return true;
    }

    public static void tryDumpOnce() {
        if (!dumpRequested.getAndSet(false)) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        int missileId = RVP_ClientHitlState.getActiveMissileId();
        Entity e = mc.level == null ? null : mc.level.getEntity(missileId);

        int drawFbo = GlStateManager._getInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
        int readFbo = GlStateManager._getInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);

        LOGGER.info("[RVP][TVMissile][Dump] active={}, mode={}, missileId={}, missileClass={}, removed={}, cam={}, viewType={}, thermalImaging={}, crtActive={}, thermalActive={}, bwActive={}, drawFbo={}, readFbo={}, mainRTStencil={}",
                RVP_ClientHitlState.isActive(),
                RVP_ClientHitlState.getVideoMode(),
                missileId,
                e == null ? "null" : e.getClass().getName(),
                e != null && e.isRemoved(),
                mc.options.getCameraType(),
                LocalVehiclePlayer.instance.viewType,
                LocalVehiclePlayer.instance.thermalImaging,
                CrtHandler.isActive(),
                ThermalHandler.isActive(),
                TVMissileVideoPostHandler.isActive(),
                drawFbo,
                readFbo,
                mc.getMainRenderTarget() != null && mc.getMainRenderTarget().isStencilEnabled()
        );

        if (e instanceof RVP_MissileEntity) {
            LOGGER.info("[RVP][TVMissile][Dump] missileRot yRot={}, xRot={}, yRotO={}, xRotO={}, pos=({}, {}, {})",
                    e.getYRot(), e.getXRot(), e.yRotO, e.xRotO, e.getX(), e.getY(), e.getZ());
        }
    }
}
