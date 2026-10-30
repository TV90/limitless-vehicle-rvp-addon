# 隐藏 OpenGL 上下文中编译生产 GLSL 并回读像素；不启动 Minecraft，不写资源文件。
$ErrorActionPreference = 'Stop'
# 由脚本所在目录确定项目，避免改变用户其它工作目录。
$projectRoot = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
# 沿用 ForgeGradle 已缓存的 Java 17 / LWJGL，不下载额外工具。
$jdkBin = 'C:\Users\FishKing0721\.jdks\ms-17.0.16\bin'
$lwjglRoot = Join-Path $env:USERPROFILE '.gradle/caches/forge_gradle/maven_downloader/org/lwjgl'
$classpath = (@('lwjgl', 'lwjgl-glfw', 'lwjgl-opengl') | ForEach-Object {
    Join-Path $lwjglRoot "$_/3.3.1/$_-3.3.1.jar"
    Join-Path $lwjglRoot "$_/3.3.1/$_-3.3.1-natives-windows.jar"
}) -join ';'
# 产物和日志仅写入项目 build，不执行任何文件删除。
$outputDir = Join-Path $projectRoot 'build/screen-light-gpu-regression'
New-Item -ItemType Directory -Force -Path $outputDir | Out-Null
& (Join-Path $jdkBin 'javac.exe') -encoding UTF-8 -cp $classpath -d $outputDir `
    (Join-Path $PSScriptRoot 'ScreenLightGpuRegression.java') `
    (Join-Path $projectRoot 'src/main/java/org/ywzj/rvp/light/RVP_LightParameters.java') `
    (Join-Path $projectRoot 'src/main/java/org/ywzj/rvp/light/RVP_BloomParameters.java') `
    (Join-Path $projectRoot 'src/main/java/org/ywzj/rvp/light/RVP_TransientLightTimeline.java')
if ($LASTEXITCODE -ne 0) { throw 'Screen light GPU regression compilation failed' }
& (Join-Path $jdkBin 'java.exe') "-Dorg.lwjgl.system.SharedLibraryExtractPath=$outputDir/natives" `
    -cp "$classpath;$outputDir" org.ywzj.rvp.client.light.ScreenLightGpuRegression $projectRoot |
    Tee-Object -FilePath (Join-Path $outputDir 'result.txt')
if ($LASTEXITCODE -ne 0) { throw 'Screen light GPU regression failed' }
