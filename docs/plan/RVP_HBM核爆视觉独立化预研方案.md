# RVP HBM 核爆视觉独立化预研方案

## 1. 目标

在不安装 HBM 时，RVP 仍能提供接近 HBM 的 `shell`、`bomb`、`nuclear` 粒子与音效。核爆后端包含蘑菇云、冲击波、闪光、屏幕震动与延迟音效，但不提供 HBM 的真实核爆毁伤、辐射、污染、方块扫描和核爆区块处理。

安装 HBM 时继续优先使用现有反射桥接，不把 HBM 变成 RVP 的硬依赖。

## 2. 后端选择

新增统一接口 `RVP_NuclearVisualBackend`，由运行环境选择实现：

| 环境 | `visual_backend = auto` 行为 |
| --- | --- |
| 已安装兼容 HBM | `shell / bomb / nuclear` 优先调用 HBM 原生特效与音效 |
| 未安装 HBM | `shell / bomb / nuclear` 全部使用 RVP 内置视觉与音效 |
| HBM 接口解析失败 | 记录一次警告并回退 RVP 内置视觉，不影响游戏启动 |

可选配置值：`auto / hbm / rvp`。默认 `auto`。

真实毁伤必须继续独立处理：

- `real_explosion = nuclear` 只有安装 HBM 且接口可用时才允许执行。
- 未安装 HBM 时，绝不模拟 HBM 真实核爆核心。
- RVP 自身的 `explosion_data` 是否造成伤害和破坏方块，仍完全由 `explode / damage / radius / destroy_block` 决定。

## 3. RVP 内置视觉架构

### 3.1 服务端轻量网络事件

当前实现使用 `S2CNuclearVisualEffect` 轻量网络事件，不新增实体。事件只同步：

- 爆心坐标与维度
- 服务端起始 game time
- 随机种子
- 视觉当量
- 云层密度
- 颜色预设
- 音效预设

它不扫描方块、不计算伤害，也不强加载大范围区块。客户端根据同一随机种子独立重建特效；`shell/bomb` 与 `nuclear` 共用这一同步入口。

### 3.2 客户端模拟器

将视觉拆成明确层级：

1. `CORE_FLASH`：初始高亮闪光
2. `STEM`：蘑菇柄与地面连接
3. `CAP`：蘑菇帽主体
4. `RING`：环流与凝结环
5. `SHOCK`：地面冲击尘云
6. `WAVE`：空间冲击波

核心 `STEM / CAP / RING` 保持满密度；`SHOCK / CONDENSATION` 支持 `visual_density` 与距离 LOD。

模拟器直接复用本次完成的性能策略：

- Cloudlet 分块并行更新
- Cloudlet convection 与颜色更新使用 primitive 缓存，避免逐云团逐 tick 创建临时数组
- 主线程统一增删列表
- 死亡对象批量压缩
- 同 tick 排序缓存与 RenderCloud 对象池复用
- 稳定哈希 LOD
- 近距离 overdraw LOD：稳定抽样并对保留云团做尺寸补偿
- 低 `visual_density` 时冲击尘云自动降频生成
- Warm Start 数量与时间预算

### 3.3 渲染器

使用 RVP 自己的 RenderType：

- 开启 `LEQUAL` depth test
- 关闭 depth write
- 全局或分层从远到近排序
- 只在渲染线程提交 `VertexConsumer`
- 后续可升级为 GPU instancing，但第一阶段不依赖它

渲染阶段应位于世界实体与天气完成后、载具 CRT/Thermal/过载后处理之前，避免核爆穿透载具机身或被后处理覆盖。

## 4. 音效方案

实现独立的 `RVP_NuclearSoundController`：

- 按玩家与爆心距离计算声速延迟
- 近距离播放爆裂与低频主体
- 中远距离播放延迟轰鸣和尾音
- 每次核爆使用 UUID 去重，避免区块重载后重复播放
- 音量上限受客户端配置约束

HBM 未安装时使用 RVP 注册的 `SoundEvent`；HBM 已安装且使用 HBM 后端时沿用 HBM 原生声音链。

## 5. 资源与许可证

当前 HBM-NTM-Rebirth 声明为 `LGPL-3.0-only`。RVP 的 `gradle.properties` 声明 `All Rights Reserved`，但根目录 `LICENSE` 是 GPLv3，RVP 自身许可证元数据存在冲突。因此当前复制资源只能视作带明确来源记录的临时开发资产，公开分发前必须处理许可证一致性，并逐步替换为自制或单独授权资产。

仍必须：

- 保留原作者与来源说明
- 在 RVP 源码仓库中公开修改后的对应源码
- 保留 GPLv3 许可证和修改声明
- 对贴图、OGG 音效逐项确认是否确实由仓库许可证覆盖
- 对来源不清晰的第三方音效重新制作或取得单独授权

不建议把 HBM jar、包名或完整类直接嵌入 RVP。应把经过审计的必要代码和资源迁移到 `org.ywzj.rvp` 与 `assets/ywzj_rvp` 命名空间，避免类冲突和资源覆盖。

## 6. 建议参数

```json
"hbm_effect_data": {
  "enabled": true,
  "real_explosion": "none",
  "visual_preset": "nuclear",
  "visual_backend": "auto",
  "effect_yield": 30,
  "visual_scale": 1.0,
  "visual_density": 0.5,
  "visual_sound": true,
  "suppress_native_explosion_effect": true,
  "nuclear_sound": true,
  "nuclear_flash": true,
  "nuclear_shake": true
}
```

未安装 HBM 时，`auto` 自动使用 RVP 内置视觉；`real_explosion = none` 不产生 HBM 真实毁伤，但 `explosion_data` 仍可独立提供 RVP 爆炸伤害。

`suppress_native_explosion_effect = true` 时，仅在 `shell / bomb / nuclear` 特殊视觉成功生成后屏蔽本体普通爆炸视觉与声音；`explosion_data` 的实际伤害和方块破坏不受影响。

## 7. 实施阶段

### 第一阶段：视觉最小闭环（已完成代码实现）

- 已建立 `auto / hbm / rvp` 路由，覆盖 `shell / bomb / nuclear`
- 已实现轻量网络同步、RVP 标准蘑菇云与普通爆炸客户端模拟
- 核爆云团已改为 HBM 风格的地面持续生成、toroidal convection 卷吸、伞帽环流和热核心距离着色
- 已移植 HBM 1.20.1 风格的球形屏幕折射冲击波，并保留 depth test
- 已实现 `shell` 小型火球、碎屑、近远延迟音效
- 已实现 `bomb` 十层火焰烟云、additive 平面冲击波、碎屑、近远延迟音效
- 已保持当前 HBM 反射桥接兼容
- 当前粒子贴图和音频来自 HBM 临时资产，详见 `docs/RVP_HBM临时资产迁移清单.md`

### 第二阶段：音效与观察反馈（基础实现已完成）

- 已实现声速延迟、普通爆炸远近分层音效和核爆延迟音效
- 已实现核爆闪光、震动和冲击波反馈
- 增加客户端音量和特效质量配置

### 第三阶段：视觉类型扩展

- 标准核爆
- Balefire/野火颜色预设
- 小型核爆、超大当量和空爆差异
- 地爆自动连接地面，空爆保持悬空云柱

### 第四阶段：性能与兼容验收

- 单次与多次核爆帧时间测试
- 低端四核与高端多核测试
- Oculus/Iris、载具透明材质和后处理兼容测试
- 无 HBM、安装 HBM、HBM 接口变化三种启动测试
- 服务端无客户端类加载测试

## 8. 验收标准

- 未安装 HBM 时 RVP 正常启动并能显示核爆视觉与音效
- 安装 HBM 时不重复生成两套蘑菇云或播放两次声音
- 纯视觉模式不产生 HBM 辐射、污染和真实核爆方块破坏
- RVP `explosion_data` 的伤害与方块破坏仍可独立启用
- 单次标准核爆近距离稳定维持目标帧时间，不出现数万 Cloudlet 同帧死亡尖峰
- 核爆受到地形和载具机身深度遮挡，不始终浮在最高层
