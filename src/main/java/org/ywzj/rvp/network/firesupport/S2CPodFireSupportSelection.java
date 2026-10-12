package org.ywzj.rvp.network.firesupport;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * [RVP] 吊舱火力支援选择同步（S2C，2026-10-13 坐标指示吊舱×火力支援整合）：
 * 保存 ACK 与登录/reload 同步共用；客户端更新静态状态供吊舱地图工具显示当前选择。
 * entryId 空串 = 该武器无选择。
 *
 * <p>客户端类经 {@code DistExecutor.unsafeRunWhenOn} 全限定名内联分发（架构测试禁止
 * network 包新增 client import，先例 S2CEngineDamageState）。</p>
 */
public class S2CPodFireSupportSelection {

    /** 吊舱武器 path。 */ public String weaponPath;
    /** 已保存条目 id（空串 = 无）。 */ public String entryId;

    public S2CPodFireSupportSelection() {}

    public S2CPodFireSupportSelection(String weaponPath, String entryId) {
        this.weaponPath = weaponPath;
        this.entryId = entryId;
    }

    /** 编码：两条受限长度 UTF 字符串。 */
    public static void encode(S2CPodFireSupportSelection message, FriendlyByteBuf buffer) {
        buffer.writeUtf(message.weaponPath, 256);
        buffer.writeUtf(message.entryId, 64);
    }

    /** 解码。 */
    public static S2CPodFireSupportSelection decode(FriendlyByteBuf buffer) {
        return new S2CPodFireSupportSelection(buffer.readUtf(256), buffer.readUtf(64));
    }

    /** 客户端主线程：更新吊舱选择状态（null 安全，早期包丢弃）。 */
    public static void handle(S2CPodFireSupportSelection message,
                              Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        context.setPacketHandled(true);
        context.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> {
            if (message.weaponPath != null && message.entryId != null) {
                org.ywzj.rvp.client.firesupport.RVP_ClientPodFireSupportState.INSTANCE
                        .onSelection(message.weaponPath, message.entryId);
            }
        }));
    }
}
