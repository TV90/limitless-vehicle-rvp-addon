---
name: "ywzj-rvp-only"
description: "默认只在附属模组 ywzj_rvp 中做代码改动（用 mixin/扩展实现），避免改动本体。仅当用户明确要求修改本体或附属无法实现时才改本体。Invoke when planning/performing code changes in this repo."
---

# YWZJ RVP Only

## 目标

在本项目里做任何“需要改代码”的需求时，默认只改动附属模组 `addons/ywzj_rvp`，优先用 Mixin/注入/扩展等方式实现，避免改动本体工程代码。

## 何时调用

当用户提出任何需要改动代码的需求（功能、修 bug、优化、重构、兼容、UI/渲染、网络、实体、武器等）时，先调用本规则。

## 执行规则

1. 默认落点
   - 代码改动默认只落在：`e:/ywzj/ywzj_vehicle/addons/ywzj_rvp/**`
   - 资源改动（如需要）：`addons/ywzj_rvp/src/main/resources/**`

2. 实现手段优先级
   - 优先：Mixin（`@Mixin/@Inject/@Redirect/@ModifyArg/@Accessor` 等）实现对本体行为的扩展/替换
   - 其次：附属侧注册/事件监听/网络包/客户端 overlay 等（不改本体）
   - 最后：只有当用户明确下达“改本体”的指令，或证明“附属无法实现”且用户接受时，才允许改动本体源码

3. 本体改动的判定（必须同时满足其一）
   - 用户明确说“改本体/改 ywzj_vehicle/不要用附属/必须改核心代码”
   - 或者：该功能无法通过附属实现（例如需要改动本体的 API、注册表、关键访问权限且无法通过 Mixin/Accessor 达成），并且已在回复中说明原因与替代方案

4. 构建与交付
   - 默认构建产物：`addons:ywzj_rvp:reobfJar`
   - 输出给用户的 jar 优先指向：`addons/ywzj_rvp/build/reobfJar/output.jar`

## 操作检查清单

- 是否把改动误落到了本体目录（`src/main/java/org/ywzj/vehicle/**`）？
- 如果需要读/写本体的私有字段或方法：是否通过 Mixin Accessor/Invoker 解决？
- Mixin 配置是否在 `addons/ywzj_rvp` 内完整（mixin plugin、mixins json、manifest）？
- 是否仍保持“老包不配新字段即保持旧行为”的兼容性？
