# Gunner 仿真回放

Java 运行当前 Gunner 的数值决策并生成 JSON，Three.js 根据记录显示坐标、目标、操作和事件。仿真不启动 Minecraft 客户端或专用服务端。

在项目根目录运行：

```powershell
$env:JAVA_HOME='C:\Users\FishKing0721\.jdks\ms-17.0.16'
$env:JAVA_TOOL_OPTIONS='-Djdk.net.unixdomain.tmpdir=D:\WgameProject'
./gradlew gunnerSimulation
cd tools/gunner-replay
npm ci
npm start
```

浏览器打开 <http://127.0.0.1:4173>。Node.js 使用 22 或更新版本；安装依赖后，回放过程不需要访问外部 CDN。`Ctrl+C` 停止本地服务。

- 页面自动读取项目 `build/gunner-simulation/index.json`，也支持导入单个回放 JSON。
- 拖动时间轴或点击事件跳转；空格播放/暂停，左右箭头逐刻前后移动，Home/End 跳到开头/结尾。
- 选择对象查看当前坐标、目标和输入；“跟随”拉近并跟随该对象；可切换俯视、轨迹、感知范围与目标连线。
- `PORT=其他端口` 可改端口；PowerShell 写作 `$env:PORT='4174'`。`RVP_REPLAY_DIR` 可指定另一个回放输出目录。
- `npm test` 验证时间采样、JSON 合同和 HTTP 服务；已生成 Java 数据时还会检查实际记录能否被浏览器读取。

地面当前只有按方向驾驶与倒车脱困，没有 A* 绕障。固定翼场景显式测试默认关闭的新公共飞控分支。感知、碰撞与开火条件是测试夹具（用于代替游戏环境的一组简化规则），完整范围与修改场景方法见 [使用说明](../../docs/RVP_gunner/RVP_Gunner无客户端仿真与ThreeJS回放_20261102.md)。
