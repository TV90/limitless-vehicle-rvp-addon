package org.ywzj.rvp.client.util;

import net.minecraft.client.Minecraft;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.loading.FMLEnvironment;
import org.ywzj.rvp.weapon.data.RVP_WeaponData;

/**
 * [RVP] 客户端语言环境帮助类（2026-09-28，数据文件双语名机制）。
 *
 * <p>武器/命中别名等数据 JSON 的双语字段（{@code name_CN}、{@code hitbox_display_name_CN}）
 * 不走原版 lang 文件，而由客户端按当前语言代码在读取点二选一。本类提供语言判定与
 * 武器名解析的统一入口：<b>服务端/专用服恒返回原 name</b>（服务端线程不触碰客户端类，
 * dist 门先行返回，@OnlyIn(CLIENT) 私有方法被 dist cleaner 剥离——双端安全）。</p>
 */
public final class RVP_LangHelper {

    private RVP_LangHelper() {
    }

    /** 当前客户端语言是否中文（zh_*）；服务端/专用服恒 false（显示原 name）。 */
    public static boolean isChineseUi() {
        if (FMLEnvironment.dist != Dist.CLIENT) {
            return false;
        }
        return clientLanguageStartsWithZh();
    }

    /** 武器显示名解析：中文环境且 {@code name_CN} 非空时返回 CN 名，否则原 name（null 安全）。 */
    public static String resolveWeaponName(RVP_WeaponData data) {
        if (data == null) {
            return "";
        }
        String cn = data.getNameCn();
        if (cn != null && !cn.isBlank() && isChineseUi()) {
            return cn;
        }
        String name = data.getName();
        return name == null ? "" : name;
    }

    @SuppressWarnings("unused")
    private static boolean clientLanguageStartsWithZh() {
        // 仅客户端调用（isChineseUi 的 dist 门已先行返回）；@OnlyIn 语义由 dist cleaner 剥离。
        // 1.20.1 官方映射：getSelected() 直接返回语言代码字符串（如 "zh_cn"）
        String code = Minecraft.getInstance().getLanguageManager().getSelected();
        return code != null && code.startsWith("zh");
    }
}
