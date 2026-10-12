package org.ywzj.rvp.network.firesupport;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.NetworkDirection;
import org.ywzj.rvp.network.RVP_Network;

import java.util.function.Supplier;

/**
 * [RVP] 吊舱火力支援选择保存（C2S，2026-10-13 坐标指示吊舱×火力支援整合）：
 * 玩家在战术地图吊舱工具里选定内联条目后上行；服务端校验条目存在后持久化到玩家
 * NBT 并回发 {@link S2CPodFireSupportSelection} 确认。
 */
public class C2SSavePodFireSupportSelection {

    /** 吊舱武器 path（选择持久化键；不带命名空间）。 */ public String weaponPath;
    /** 选定的内联条目 id；空串 = 清除该武器的选择。 */ public String entryId;

    public C2SSavePodFireSupportSelection() {}

    public C2SSavePodFireSupportSelection(String weaponPath, String entryId) {
        this.weaponPath = weaponPath;
        this.entryId = entryId;
    }

    /** 编码：两条受限长度 UTF 字符串（服务端会复核条目存在性，不采信任意值）。 */
    public static void encode(C2SSavePodFireSupportSelection message, FriendlyByteBuf buffer) {
        buffer.writeUtf(message.weaponPath, 256);
        buffer.writeUtf(message.entryId, 64);
    }

    /** 解码。 */
    public static C2SSavePodFireSupportSelection decode(FriendlyByteBuf buffer) {
        return new C2SSavePodFireSupportSelection(buffer.readUtf(256), buffer.readUtf(64));
    }

    /** 服务端主线程：校验条目存在→持久化→ACK。 */
    public static void handle(C2SSavePodFireSupportSelection message,
                              Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        context.enqueueWork(() -> {
            ServerPlayer player = context.getSender();
            if (player == null || message.weaponPath == null || message.weaponPath.isBlank()
                    || message.weaponPath.length() > 256
                    || !message.weaponPath.matches("[a-z0-9_.-]+")
                    || message.entryId == null || message.entryId.length() > 64) {
                return;
            }
            // 调用本项目选择存储：校验条目存在（防伪造）→持久化→回发确认。
            if (org.ywzj.rvp.firesupport.server.RVP_PodFireSupportSelectionStore.isSelectionValid(
                    message.weaponPath, message.entryId)) {
                org.ywzj.rvp.firesupport.server.RVP_PodFireSupportSelectionStore.save(
                        player, message.weaponPath, message.entryId);
            }
        });
        context.setPacketHandled(true);
    }
}
