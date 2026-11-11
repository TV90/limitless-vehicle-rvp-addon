package org.ywzj.rvp.mixin;

import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.event.OnDatapackSyncEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.network.PacketDistributor;
import org.ywzj.vehicle.YwzjVehicle;
import org.ywzj.vehicle.custom.CommonAssetsManager;
import org.ywzj.vehicle.custom.StructureModelManager;
import org.ywzj.vehicle.custom.VehicleDataManager;
import org.ywzj.vehicle.custom.VehicleWeaponManager;
import org.ywzj.vehicle.network.Channel;
import org.ywzj.vehicle.network.SliceReassembler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.zip.GZIPInputStream;
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
 * <p>写入端放宽后，客户端本体的 {@code fromNetwork} 仍用默认上限的
 * {@code readMap(..., FriendlyByteBuf::readUtf)} 读取（单串上限 32767），超过上限的
 * 结构模型（vt4/t84bm）会抛 {@code DecoderException}，被 {@code fromNetwork} 的
 * {@code catch (Exception)} 整体吞掉：这一次同步里三张缓存全部没导入，客户端悄悄退回
 * 本地包，表现为服务端数据包改动不生效。因此读写两端必须同时放宽。</p>
 *
 * <p>实现采用 {@code @Overwrite} 而非 {@code @Redirect}：目标为本体类（remap=false 的
 * mixin 不会为 vanilla @At 写 refmap 条目，Redirect 在生产环境不生效）；Overwrite 的
 * 方法体在本体与映射环境一致，dev/生产行为相同。线格式（VarInt 数量 + 逐条 key
 * + VarInt 长度 + UTF-8 字节）与本体完全一致。本体更新这两个方法时需同步此处。</p>
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

    /** 与写入端 {@code rvp$writeUtfMapRelaxed} 对称：VarInt 数量 + 逐条 key/放宽上限的值。 */
    private static Map<ResourceLocation, String> rvp$readUtfMapRelaxed(FriendlyByteBuf buf) {
        int size = buf.readVarInt();
        Map<ResourceLocation, String> map = new HashMap<>(Math.max(size, 1));
        for (int i = 0; i < size; i++) {
            map.put(buf.readResourceLocation(), buf.readUtf(65535));
        }
        return map;
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

    /**
     * @reason 本体原实现用 {@code readMap(FriendlyByteBuf::readResourceLocation, FriendlyByteBuf::readUtf)}
     * 读取三张缓存，而 {@code readUtf()} 默认单串上限仍是 32767：写入端放宽后，结构模型
     * JSON（vt4 32793 / t84bm 32831 字符）在客户端抛 {@code DecoderException}，整个同步被本方法的
     * catch 吞掉，三张缓存一起退回本地包。本覆写仅把值读取放宽为 {@code readUtf(65535)}，
     * gzip 解压与三次导入顺序和本体一致。
     */
    @Overwrite(remap = false)
    public static void fromNetwork(FriendlyByteBuf buf) {
        try {
            byte[] data = new byte[buf.readableBytes()];
            buf.getBytes(buf.readerIndex(), data);
            try (GZIPInputStream gzip = new GZIPInputStream(new ByteArrayInputStream(data))) {
                data = gzip.readAllBytes();
            }
            FriendlyByteBuf decoded = new FriendlyByteBuf(Unpooled.wrappedBuffer(data));
            Map<ResourceLocation, String> structureModels = rvp$readUtfMapRelaxed(decoded);
            Map<ResourceLocation, String> vehicleWeapons = rvp$readUtfMapRelaxed(decoded);
            Map<ResourceLocation, String> vehicleData = rvp$readUtfMapRelaxed(decoded);
            StructureModelManager.fromNetwork(structureModels);
            VehicleWeaponManager.fromNetwork(vehicleWeapons);
            VehicleDataManager.fromNetwork(vehicleData);
        } catch (Exception exception) {
            YwzjVehicle.LOGGER.error("Failed to read common assets from network", exception);
        }
    }
}
