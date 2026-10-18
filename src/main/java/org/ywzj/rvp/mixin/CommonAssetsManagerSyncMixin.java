package org.ywzj.rvp.mixin;

import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.event.OnDatapackSyncEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.network.PacketDistributor;
import org.ywzj.vehicle.custom.CommonAssetsManager;
import org.ywzj.vehicle.network.Channel;
import org.ywzj.vehicle.network.SliceReassembler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.zip.GZIPOutputStream;

/**
 * 放宽本体公共资源同步（结构模型/武器/数据三张缓存）的单字符串上限至 65535（2026-10-19）。
 *
 * <p>背景：本体 {@code CommonAssetsManager.onDatapackSync} 把三张缓存按
 * {@code Map<ResourceLocation,String>} 逐条 {@code writeUtf} 写进同步缓冲，而默认
 * {@code writeUtf(String)} 单串上限 32767 字符——大结构模型（ZTZ99B 新增履带悬挂骨后
 * 重序列化 32872 字符）在玩家进服时直接 {@code EncoderException: String too big}，
 * 服务端 {@code Couldn't place player in world}，客户端表现为"无效的玩家数据"断线。</p>
 *
 * <p>实现采用 {@code @Overwrite} 而非 {@code @Redirect}：目标为本体类（remap=false 的
 * mixin 不会为 vanilla @At 写 refmap 条目，Redirect 在生产环境不生效）；Overwrite 的
 * 方法体在本体与映射环境一致，dev/生产行为相同。除三处值写入放宽为
 * {@code writeUtf(value, 65535)} 外与本体逻辑逐行一致——本体更新此方法时需同步此处。</p>
 *
 * <p>为什么只放宽写入端是安全的：写完的整段缓冲随后经 gzip 压缩再分片发送（本体已有），
 * 单串上限是压缩前唯一的隐形瓶颈；客户端 {@code fromNetwork} 用无上限校验的
 * {@code readUtf()} 读取，线格式（VarInt 长度 + UTF-8 字节）完全不变。</p>
 *
 * <p>65535 = 单串上限放宽一倍；更大的结构模型仍会撞线，根治（分块传输或字节数组负载）
 * 需本体侧改造，已同步本体作者。</p>
 */
@Mixin(value = CommonAssetsManager.class, remap = false)
public abstract class CommonAssetsManagerSyncMixin {

    /** 与原版 {@code writeMap} 线格式一致（VarInt 数量 + 逐条 key/value），仅值写入放宽上限。 */
    private static void rvp$writeUtfMapRelaxed(FriendlyByteBuf buf, Map<ResourceLocation, String> map,
                                               BiConsumer<FriendlyByteBuf, ResourceLocation> keyWriter) {
        buf.writeVarInt(map.size());
        for (Map.Entry<ResourceLocation, String> entry : map.entrySet()) {
            keyWriter.accept(buf, entry.getKey());
            buf.writeUtf(entry.getValue(), 65535);
        }
    }

    /**
     * @reason 本体原实现三张缓存均走默认 {@code writeUtf(String)}（单串上限 32767），
     * 大结构模型（如 ZTZ99B）同步即炸导致玩家无法进世界；本覆写仅把三处值写入
     * 放宽为 {@code writeUtf(value, 65535)}，其余逻辑（gzip → 分片 → 发送）与本体一致。
     */
    @Overwrite(remap = false)
    @SubscribeEvent
    public static void onDatapackSync(OnDatapackSyncEvent event) {
        if (CommonAssetsManager.INSTANCE != null) {
            FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
            rvp$writeUtfMapRelaxed(buf,
                    ((CommonAssetsManagerAccessor) (Object) CommonAssetsManager.INSTANCE).rvp$structureModelManager().getCache(),
                    FriendlyByteBuf::writeResourceLocation);
            rvp$writeUtfMapRelaxed(buf,
                    ((CommonAssetsManagerAccessor) (Object) CommonAssetsManager.INSTANCE).rvp$vehicleWeaponManager().getCache(),
                    FriendlyByteBuf::writeResourceLocation);
            rvp$writeUtfMapRelaxed(buf,
                    ((CommonAssetsManagerAccessor) (Object) CommonAssetsManager.INSTANCE).rvp$vehicleDataManager().getCache(),
                    FriendlyByteBuf::writeResourceLocation);
            byte[] data = new byte[buf.readableBytes()];
            buf.getBytes(buf.readerIndex(), data);
            try (ByteArrayOutputStream output = new ByteArrayOutputStream();
                 GZIPOutputStream gzip = new GZIPOutputStream(output)) {
                gzip.write(data);
                gzip.finish();
                buf = new FriendlyByteBuf(Unpooled.wrappedBuffer(output.toByteArray()));
            } catch (IOException exception) {
                throw new IllegalStateException("Failed to gzip common assets", exception);
            }
            var packets = SliceReassembler.sliceData(buf);
            for (var packet : packets) {
                if (event.getPlayer() != null) {
                    Channel.CHANNEL.send(PacketDistributor.PLAYER.with(event::getPlayer), packet);
                } else {
                    Channel.CHANNEL.send(PacketDistributor.ALL.noArg(), packet);
                }
            }
        }
    }
}
