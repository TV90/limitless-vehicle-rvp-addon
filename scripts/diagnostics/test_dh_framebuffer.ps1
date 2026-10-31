# 在隐藏 OpenGL 上下文中验证真实生产类和 GLSL；产物/日志只写入仓库 build 目录。
$ErrorActionPreference = 'Stop'
# 从脚本目录解析仓库，避免依赖调用终端的当前位置。
$projectRoot = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
# 使用项目要求的 JDK 17，以及 ForgeGradle 已下载的 Minecraft LWJGL 3.3.1。
$jdkBin = 'C:\Users\FishKing0721\.jdks\ms-17.0.16\bin'
$lwjglRoot = Join-Path $env:USERPROFILE '.gradle/caches/forge_gradle/maven_downloader/org/lwjgl'
$classpath = (@('lwjgl', 'lwjgl-glfw', 'lwjgl-opengl') | ForEach-Object {
    Join-Path $lwjglRoot "$_/3.3.1/$_-3.3.1.jar"
    Join-Path $lwjglRoot "$_/3.3.1/$_-3.3.1-natives-windows.jar"
}) -join ';'
# 编译本项目生产源码，测试自身不复制挂接逻辑。
$outputDir = Join-Path $projectRoot 'build/dh-framebuffer-regression'
New-Item -ItemType Directory -Force -Path $outputDir | Out-Null
$productionDir = Join-Path $projectRoot 'src/main/java/org/ywzj/rvp/client/compat/distanthorizons'
& (Join-Path $jdkBin 'javac.exe') -encoding UTF-8 -cp $classpath -d $outputDir `
    (Join-Path $PSScriptRoot 'DhFramebufferRegression.java') `
    (Join-Path $productionDir 'RVP_DhFramebufferSet.java') `
    (Join-Path $productionDir 'RVP_DhDepthCopy.java')
if ($LASTEXITCODE -ne 0) { throw 'DH GPU regression compilation failed' }
& (Join-Path $jdkBin 'java.exe') -cp "$classpath;$outputDir" `
    org.ywzj.rvp.client.compat.distanthorizons.DhFramebufferRegression $projectRoot |
    Tee-Object -FilePath (Join-Path $outputDir 'result.txt')
if ($LASTEXITCODE -ne 0) { throw 'DH GPU regression failed' }
