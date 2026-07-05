package org.ywzj.rvp.mixin;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.ywzj.rvp.entity.gunner.GunnerEntity;
import org.ywzj.rvp.entity.gunner.ai.profile.RVP_EnumGunnerFaction;
import org.ywzj.rvp.accessor.AbstractVehicleGunnerDataAccessor;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;

/**
 * 为 AbstractVehicle.writeData() / readData() 注入 Gunner faction 和载具航向信息。
 *
 * <p><b>问题</b>：本体 writeData() 只传 driver.getTeam()，不传 Gunner 的 PROFILE_FACTION。
 * 当 Gunner 的 owner 不在线时 getTeam() 返回 null，导致外部雷达/远程实体无法获取 IFF。
 * 同时 writeData() 不传航向，远程实体的 YRot 始终为 0。</p>
 *
 * <p><b>修复</b>：</p>
 * <ul>
 *   <li>writeData() 尾部追加 rvp_yRot、rvp_xRot、rvp_gunnerFaction 字段</li>
 *   <li>当 Gunner faction 为 ENEMY/FRIENDLY 但 getTeam() 返回 null 时，
 *       写入 fallback teamName（"__rvp_enemy__" / "__rvp_friendly__"）以兼容本体 remoteTeam 机制</li>
 *   <li>readData() 尾部读取航向并应用到远程实体，读取 faction 存入 rvpRemoteFaction</li>
 * </ul>
 */
@Mixin(value = AbstractVehicle.class, remap = false)
public abstract class AbstractVehicleGunnerDataMixin implements AbstractVehicleGunnerDataAccessor {

    /** 远程实体的 Gunner faction 缓存（仅远程实体使用）。 */
    @Unique
    private RVP_EnumGunnerFaction ywzj_rvp$rvpRemoteFaction;

    @Override
    public RVP_EnumGunnerFaction ywzj_rvp$getRemoteFaction() {
        return ywzj_rvp$rvpRemoteFaction;
    }

    // ---- writeData 尾部注入 ----

    @Inject(method = "writeData", at = @At("TAIL"))
    private void ywzj_rvp$writeGunnerData(CompoundTag data, CallbackInfo ci) {
        AbstractVehicle self = (AbstractVehicle) (Object) this;

        // 写入航向
        data.putFloat("rvp_yRot", self.getYRot());
        data.putFloat("rvp_xRot", self.getXRot());

        // 写入 Gunner 驾驶员的 faction
        LivingEntity driver = self.getDriver();
        if (driver instanceof GunnerEntity gunner) {
            RVP_EnumGunnerFaction faction = gunner.getProfileFaction();
            if (faction != null) {
                data.putString("rvp_gunnerFaction", faction.name());
            }
            // fallback：当本体 writeData() 未写入 teamName（driver.getTeam() 为 null），
            // 但 Gunner faction 为 ENEMY/FRIENDLY 时，写入虚拟 teamName
            if (!data.contains("teamName")) {
                if (faction == RVP_EnumGunnerFaction.ENEMY) {
                    data.putString("teamName", "__rvp_enemy__");
                } else if (faction == RVP_EnumGunnerFaction.FRIENDLY) {
                    data.putString("teamName", "__rvp_friendly__");
                }
            }
        }
    }

    // ---- readData 尾部注入 ----

    @Inject(method = "readData", at = @At("TAIL"))
    private void ywzj_rvp$readGunnerData(CompoundTag data, CallbackInfo ci) {
        AbstractVehicle self = (AbstractVehicle) (Object) this;

        // 读取航向（仅远程实体需要）
        if (data.contains("rvp_yRot")) {
            float yRot = data.getFloat("rvp_yRot");
            self.setYRot(yRot);
            self.yRotO = yRot;
        }
        if (data.contains("rvp_xRot")) {
            float xRot = data.getFloat("rvp_xRot");
            self.setXRot(xRot);
            self.xRotO = xRot;
        }

        // 读取 Gunner faction
        if (data.contains("rvp_gunnerFaction")) {
            ywzj_rvp$rvpRemoteFaction = RVP_EnumGunnerFaction.parse(data.getString("rvp_gunnerFaction"));
        } else {
            ywzj_rvp$rvpRemoteFaction = null;
        }
    }
}
