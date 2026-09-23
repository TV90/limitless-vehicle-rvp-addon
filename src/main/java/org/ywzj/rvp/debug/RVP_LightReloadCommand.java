package org.ywzj.rvp.debug;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.Commands;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.registries.ForgeRegistries;
import org.apache.commons.lang3.time.StopWatch;
import org.ywzj.rvp.config.RVP_VehicleExtendedConfigManager;
import org.ywzj.rvp.config.RVP_WingSweepConfigManager;
import org.ywzj.rvp.weapon.damage.RVP_VehicleHitboxFactorManager;
import org.ywzj.vehicle.all.AllConfigs;
import org.ywzj.vehicle.custom.CommonAssetsManager;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 轻量热重载命令：{@code /rvp reload}。
 *
 * <p>与本体 {@code /ywzj_vehicle reload} 的差异：跳过 {@code ClientAssetsManager} 全量重载
 * （三个 DisplayManager 的 bedrock 模型重新烘焙、全部贴图重新解码上传、JS 脚本引擎重建——
 * 热更新耗时的绝对大头），只重载<b>数据侧</b>，供"只改 JSON 不改资源"的调试循环提速。</p>
 *
 * <p>覆盖范围：载具 JSON、武器 JSON、<b>结构模型</b>（structureModelManager 在
 * {@link CommonAssetsManager} 数据侧）、RVP 全部载具级数据缓存（挂架配置/出弹点写入/UI 预设/
 * 分角度 RCS/热量/发射架/无人机/起落架——均挂在本体 {@code VehicleDataManager.apply} TAIL 的
 * {@code VehicleDataManagerMixin}，手动调用 apply 同样经过，无需单独处理），
 * 以及已放置载具的销毁重建（与本体语义一致）。</p>
 *
 * <p>不覆盖：display JSON、bedrock 模型、动画、贴图、音效——改这些仍需
 * {@code /ywzj_vehicle reload} 全量。客户端单机额外补跑三个 RVP 客户端数据监听器
 * （rvp 载具包以资源包注册，其载具 JSON 在客户端从资源仓库扫描，平时仅 F3+T 触发）。</p>
 */
public final class RVP_LightReloadCommand {

    private RVP_LightReloadCommand() {}

    /** 构建子命令树（挂在 {@code /rvp} 根下），权限与本体 reload 一致（OP 2 级）。 */
    public static LiteralArgumentBuilder<CommandSourceStack> get() {
        return Commands.literal("reload")
                .requires(source -> source.hasPermission(2))
                .executes(RVP_LightReloadCommand::reload);
    }

    private static int reload(CommandContext<CommandSourceStack> context) {
        StopWatch watch = StopWatch.createStarted();
        {
            // 轻量客户端段：不重载 ClientAssetsManager（模型/贴图/脚本），只补跑 RVP 客户端数据监听器。
            // 与本体 ReloadCommand 同款 DistExecutor 双 lambda 写法，防服务端 dist 加载客户端类。
            DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> {
                var clientResources = net.minecraft.client.Minecraft.getInstance().getResourceManager();
                RVP_VehicleExtendedConfigManager.INSTANCE.reloadFrom(clientResources);
                RVP_VehicleHitboxFactorManager.INSTANCE.reloadFrom(clientResources);
                RVP_WingSweepConfigManager.INSTANCE.reloadFrom(clientResources);
            });
            MinecraftServer server = context.getSource().getServer();
            // 数据侧全量：结构模型 + 武器 + 载具 JSON；RVP 钩子经 VehicleDataManagerMixin（apply TAIL）自动重建
            CommonAssetsManager.INSTANCE.reload(server.getResourceManager());
            reloadAllVehicles(context.getSource());
        }
        watch.stop();
        double time = watch.getTime(TimeUnit.MICROSECONDS) / 1000.0;
        context.getSource().sendSuccess(() -> Component.literal(String.format(
                "[RVP] 轻量重载完成，耗时 %.3f ms（仅数据+结构模型；改模型/贴图/动画/音效请用 /ywzj_vehicle reload）",
                time)), false);
        AllConfigs.loadExternal();
        return Command.SINGLE_SUCCESS;
    }

    /**
     * 全维度载具 NBT 快照销毁重建（与本体 {@code ReloadCommand#reloadAllVehicles} 同逻辑）：
     * 载具 JSON / 结构模型的改动需实体重建才对已放置载具生效。
     */
    private static void reloadAllVehicles(CommandSourceStack source) {
        MinecraftServer server = source.getServer();
        record VehicleSnapshot(
                ResourceLocation type,
                CompoundTag nbt,
                ServerLevel level
        ) {}
        List<VehicleSnapshot> snapshots = new ArrayList<>();
        for (ServerLevel level : server.getAllLevels()) {
            for (Entity entity : level.getAllEntities()) {
                if (!(entity instanceof AbstractVehicle vehicle)) {
                    continue;
                }
                CompoundTag nbt = new CompoundTag();
                vehicle.saveWithoutId(nbt);
                snapshots.add(new VehicleSnapshot(
                        EntityType.getKey(vehicle.getType()),
                        nbt,
                        level
                ));
                vehicle.discard();
            }
        }
        for (VehicleSnapshot snapshot : snapshots) {
            EntityType<?> type = ForgeRegistries.ENTITY_TYPES.getValue(snapshot.type());
            if (type == null) {
                continue;
            }
            Entity entity = type.create(snapshot.level());
            if (entity == null) {
                continue;
            }
            entity.load(snapshot.nbt());
            snapshot.level().addFreshEntity(entity);
        }
    }
}
