/**
 * 可复用的轨迹与制导纯数学模型。
 *
 * <p>{@code virtualguidance} 子包承载虚拟弹道的不可变状态与 Tick 编排，{@code util}
 * 子包承载可跨实现复用的无状态计算。两者均不读取世界、实体、区块或武器 JSON 数据对象；
 * 调用方负责在业务边界把运行时配置冻结为参数记录。</p>
 */
package org.ywzj.rvp.guidance.trajectorymath;
