package org.ywzj.rvp.firesupport.data;

/** 一发炮弹的计划水平落点。Y 坐标由后续投送阶段查询地形。 */
public record RVP_FireSupportImpactPoint(
        /** 世界 X 坐标。 */ double x,
        /** 世界 Z 坐标。 */ double z) {}
