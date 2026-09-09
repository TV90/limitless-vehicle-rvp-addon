package org.ywzj.rvp.ext;

/**
 * 载具部件运行时数据（{@code org.ywzj.vehicle.custom.part.data.PartUnitData}）的 RVP 扩展接口。
 *
 * <p>由 {@code PartUnitDataMixin} 以 common 数组注入实现，字段值在构造器 TAIL 从
 * {@link PartUnitPojoExt}（JSON 层）拷贝而来，供客户端视角判定等运行时逻辑消费。</p>
 */
public interface PartUnitDataExt {

    /**
     * 该部件（座位）是否被标记为「无座舱视角」。
     *
     * @return 运行时标志（来源：JSON 字段 {@code rvp_no_cockpit_view}，默认 false）
     */
    boolean ywzj_rvp$isNoCockpitView();
}
