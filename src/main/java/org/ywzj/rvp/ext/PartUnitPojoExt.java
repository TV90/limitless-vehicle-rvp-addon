package org.ywzj.rvp.ext;

/**
 * 载具部件 Pojo（{@code org.ywzj.vehicle.custom.part.data.PartUnitPojo}）的 RVP 扩展接口。
 *
 * <p>由 {@code PartUnitPojoMixin} 以 common 数组注入实现（PartUnitPojo 是全部部件 Pojo 的基类，
 * 仅依赖 gson/Vec3 等双端安全类型，无带毒风险）。{@link PartUnitPojoExt} 字段经
 * {@code PartUnitDataMixin} 在运行时数据层拷贝为 {@link PartUnitDataExt} 供逻辑消费。</p>
 */
public interface PartUnitPojoExt {

    /**
     * 该部件（座位）是否被标记为「无座舱视角」。
     *
     * @return JSON 字段 {@code rvp_no_cockpit_view} 的反序列化值
     */
    boolean ywzj_rvp$isNoCockpitView();
}
