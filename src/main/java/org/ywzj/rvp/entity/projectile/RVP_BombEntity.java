package org.ywzj.rvp.entity.projectile;

import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.level.Level;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.network.PlayMessages;
import org.ywzj.rvp.all.RVP_Entities;
import org.ywzj.rvp.all.RVP_Sounds;
import org.ywzj.vehicle.audio.VehicleSound;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;
import org.ywzj.vehicle.vehicle.PhysicsEngine;

/**
 * Bomb entity with gravity-first motion for {@code rvp:bomb}.
 */
public class RVP_BombEntity extends RVP_BaseBullet {

    /**
     * 划破空气哨音（客户端专属，服务端永不触碰）。
     * 移植自本体 {@code AerialBombEntity#tickSound} 的 whistle 分支：RVP 炸弹继承
     * {@link RVP_BaseBullet} 而非 {@code AerialBombEntity}，本体那套音效不会生效。
     */
    private VehicleSound soundWhistle;

    /**
     * 临近命中的来袭轰鸣（客户端专属，服务端永不触碰），仅徒步玩家可闻，见 {@link #tickSound()}。
     *
     * <p>注：本体的同位置字段名叫 {@code soundWhistle} 却装 {@code BOMBS_INCOMING}、
     * {@code soundIncoming} 反而装 {@code BOMB_WHISTLE}——字段名是反的。此处按语义正确命名。</p>
     */
    private VehicleSound soundIncoming;

    public RVP_BombEntity(EntityType<? extends Projectile> type, Level level) {
        super(type, level);
    }

    public RVP_BombEntity(EntityType<? extends Projectile> type, Level level, ResourceLocation weaponId) {
        super(type, level, weaponId);
    }

    public RVP_BombEntity(PlayMessages.SpawnEntity msg, Level level) {
        super(RVP_Entities.RVP_BOMB.get(), level);
    }

    @Override
    public void tick() {
        super.tick();
        // 基类 tick 在客户端分支提前 return，音效需在其后独立驱动
        if (level().isClientSide()) {
            tickSound();
        }
    }

    /**
     * 炸弹飞行音效（移植自本体 {@code AerialBombEntity#tickSound}，两个声部）：
     * <ul>
     *   <li>{@code bomb_whistle}——本地玩家 32 格内播放一次划破空气的哨音（渐入渐出各 50 tick），
     *       无附加条件，投弹者与旁观者都听得见。</li>
     *   <li>{@code bombs_incoming}——8 格内<b>且玩家不在载具上</b>时播放一次临近命中的来袭轰鸣，
     *       条件与本体一致：只作用于地面上将被命中的<b>徒步目标</b>，投弹者自己在座舱里不会响。</li>
     * </ul>
     * 落地时全部停止；弹药被移除时由 {@code VehicleSound#updateRelativePos} 自行停止。
     * 与本体一致每个声部只播放一次（字段非空后不再重放），避免多枚炸弹并发时反复触发。
     */
    @OnlyIn(Dist.CLIENT)
    public void tickSound() {
        if (onGround()) {
            if (soundWhistle != null) {
                soundWhistle.stop();
            }
            if (soundIncoming != null) {
                soundIncoming.stop();
            }
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        Player player = mc.player;
        if (player == null) {
            return;
        }
        double distance = player.distanceTo(this);
        // 来袭轰鸣：仅步兵（不在载具上）——投弹者自身不响
        if (soundIncoming == null && distance < 8 && !(player.getVehicle() instanceof AbstractVehicle)) {
            soundIncoming = new VehicleSound(RVP_Sounds.BOMBS_INCOMING.get(), 1f, 2f, 1f, false, 50, true, true, this.getId());
            soundIncoming.play();
        }
        // 划破空气哨音：任何人 32 格内都听得到
        if (soundWhistle == null && distance < 32) {
            soundWhistle = new VehicleSound(RVP_Sounds.BOMB_WHISTLE.get(), 1f, 2f, 1f, false, 50, true, true, this.getId());
            soundWhistle.play();
        }
    }

    @Override
    protected void tickMotion() {
        // 仅简化弹道且未配置 gravity 时补默认重力；推进模式在 tickPropulsionMotion 内处理，避免重复叠加 G
        if (rvpData != null && !rvpData.usesPropulsion() && rvpData.getGravity() == 0f) {
            setDeltaMovement(getDeltaMovement().add(0, -PhysicsEngine.G, 0));
        }
        super.tickMotion();
    }
}
