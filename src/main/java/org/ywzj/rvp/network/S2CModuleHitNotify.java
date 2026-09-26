package org.ywzj.rvp.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * [RVP] 部件命中战果通知（服务端 → 射手客户端，2026-09-27 新增，协议 17）。
 *
 * <p>射手命中并摧毁敌方载具骨骼模块（爆反/雷达/引擎/APS 探测器/光电干扰机…），
 * 或把敌方引擎打进受损档（窗口累计伤害跨过受损阈值）时，向射手本人推送本包；
 * 客户端在命中展板（{@code RVP_HitIndicatorOverlay}）下方显示 60 tick（3 秒）文案
 * （"摧毁光电干扰机"/"重创发动机"等）。</p>
 *
 * <p>模块显示名由客户端按 {@code gui.ywzj_rvp.module.<类型小写>} 本地化（服务端只发枚举名），
 * 文案格式：摧毁 = {@code gui.ywzj_rvp.hit.module_destroyed}（"摧毁%s"）；
 * 引擎受损 = {@code gui.ywzj_rvp.hit.engine_damaged}（"重创发动机"）。</p>
 */
public class S2CModuleHitNotify {

    /** 通知类型：摧毁部件 / 引擎受损。 */
    public static final int KIND_MODULE_DESTROYED = 0;
    public static final int KIND_ENGINE_DAMAGED = 1;

    public int kind;
    /** 模块类型枚举名（BoneModuleType.name()，kind=0 时使用）。 */
    public String moduleName = "";

    public static S2CModuleHitNotify create(int kind, String moduleName) {
        S2CModuleHitNotify msg = new S2CModuleHitNotify();
        msg.kind = kind;
        msg.moduleName = moduleName == null ? "" : moduleName;
        return msg;
    }

    public static void encode(S2CModuleHitNotify msg, FriendlyByteBuf buf) {
        buf.writeVarInt(msg.kind);
        buf.writeUtf(msg.moduleName, 32);
    }

    public static S2CModuleHitNotify decode(FriendlyByteBuf buf) {
        return new S2CModuleHitNotify(buf.readVarInt(), buf.readUtf(32));
    }

    public static void handle(S2CModuleHitNotify msg, Supplier<NetworkEvent.Context> ctxSupplier) {
        NetworkEvent.Context ctx = ctxSupplier.get();
        ctx.setPacketHandled(true);
        ctx.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () ->
                org.ywzj.rvp.client.state.RVP_ClientHitNotifyState.apply(msg)));
    }

    private S2CModuleHitNotify() {
    }

    private S2CModuleHitNotify(int kind, String moduleName) {
        this.kind = kind;
        this.moduleName = moduleName;
    }
}
