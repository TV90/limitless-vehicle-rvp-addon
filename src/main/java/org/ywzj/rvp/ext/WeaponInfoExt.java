package org.ywzj.rvp.ext;

/**
 * [RVP] {@code org.ywzj.vehicle.vehicle.pojo.WeaponInfo}（载具 JSON {@code weapons} 数组的
 * 条目 pojo）的 RVP 扩展字段访问接口。
 *
 * <p>注意：{@code WeaponInfo} 是 {@code WeaponUnitPojo.weapons} <b>列表的元素</b>，
 * 现有 {@code WeaponUnitPojoMixin} 作用于最外层 pojo，管不到列表元素——
 * 因此 {@code rvp_full_salvo_immune} 由独立的 {@code WeaponInfoMixin} 注入本接口。</p>
 */
public interface WeaponInfoExt {

    /**
     * 查询该武器条目是否免疫所在武器站的 full_salvo 齐射接管
     * （{@code rvp_full_salvo_immune}，默认 false = 受接管 = 本体原行为）。
     */
    boolean ywzj_rvp$fullSalvoImmune();
}
