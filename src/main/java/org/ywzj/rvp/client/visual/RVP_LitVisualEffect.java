package org.ywzj.rvp.client.visual;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.client.event.ViewportEvent;
import org.ywzj.rvp.client.light.RVP_LightSourceType;
import org.ywzj.rvp.client.light.RVP_ScreenLightManager;
import org.ywzj.rvp.light.RVP_LightParameters;
import org.ywzj.rvp.weapon.visual.api.RVP_VisualEffectEvent;

/** 为成功创建的其它爆炸工厂附加同一层光，不另发事件、不改变原声音和震动。 */
final class RVP_LitVisualEffect implements RVP_ClientVisualEffect {
    /** 被包装的原效果，所有世界绘制与反馈继续转发。 */
    private final RVP_ClientVisualEffect delegate;
    /** 管理器返回的取消句柄。 */
    private final long handle;
    /** 原效果结束后仅关闭一次，不能在等待短光时继续推进已关闭模拟。 */
    private boolean delegateClosed;

    RVP_LitVisualEffect(ClientLevel level, RVP_VisualEffectEvent event, RVP_ClientVisualEffect delegate) {
        this.delegate = delegate;
        // 调用代码中的爆炸光参数，现有事件仍只负责爆心、半径、时间和闪光事实。
        RVP_LightParameters parameters = RVP_LightParameters.explosion(event.baseExplosionRadius());
        RVP_ScreenLightManager.INSTANCE.beginWorld(level, level.getGameTime());
        handle = RVP_ScreenLightManager.INSTANCE.addTransient(event.position(), RVP_LightSourceType.EXPLOSION,
                null, parameters, event.startGameTime());
    }

    @Override
    public void tick() {
        if (!delegateClosed) {
            // 调用原工厂实例，附加光不会替换其时间线。
            delegate.tick();
            if (delegate.isFinished()) { delegate.close(); delegateClosed = true; }
        }
    }

    @Override
    public void render(RenderLevelStageEvent event) {
        // 调用原效果绘制，关闭后不再触碰它已释放的资源。
        if (!delegateClosed) { delegate.render(event); }
    }

    @Override
    public void renderThermal(RenderLevelStageEvent event) {
        // 调用原热源重画，普通彩色屏幕光的热成像门控由合成器独立负责。
        if (!delegateClosed) { delegate.renderThermal(event); }
    }

    @Override
    public void applyCameraShake(ViewportEvent.ComputeCameraAngles event) {
        // 调用原反馈，不生成第二份震动任务。
        if (!delegateClosed) { delegate.applyCameraShake(event); }
    }

    @Override
    public boolean isFinished() {
        // 调用整盏光源的实际存活查询；原效果结束后等待短光平滑淡出，不重播已关闭效果。
        return delegateClosed && !RVP_ScreenLightManager.INSTANCE.hasTransient(handle);
    }

    @Override
    public void close() {
        // 调用原关闭与灯光取消，预算淘汰和换世界保持一致。
        if (!delegateClosed) { delegate.close(); delegateClosed = true; }
        RVP_ScreenLightManager.INSTANCE.removeTransient(handle);
    }

    /** 让复杂效果预算仍能识别原来的威尔逊实例，包装不能绕过四个复杂效果的上限。 */
    boolean isComplex() { return delegate instanceof RVP_WilsonMachEffectInstance; }
}
