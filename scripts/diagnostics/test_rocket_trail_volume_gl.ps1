# 隐藏 OpenGL 窗口运行正式体积尾烟，不启动游戏，不删除或改写任何既有资源。
$ErrorActionPreference = 'Stop'
# 解析项目和既有 Java 17 / LWJGL 缓存，避免下载不同版本的测试运行环境。
$projectRoot = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$jdkBin = 'C:\Users\FishKing0721\.jdks\ms-17.0.16\bin'
$lwjglRoot = Join-Path $env:USERPROFILE '.gradle/caches/forge_gradle/maven_downloader/org/lwjgl'
$classpath = (@('lwjgl', 'lwjgl-glfw', 'lwjgl-opengl') | ForEach-Object {
    Join-Path $lwjglRoot "$_/3.3.1/$_-3.3.1.jar"
    Join-Path $lwjglRoot "$_/3.3.1/$_-3.3.1-natives-windows.jar"
}) -join ';'
# 类文件、日志和四视图仅写入 build，不作为游戏贴图或载具包资产。
$outputDir = Join-Path $projectRoot 'build/rocket-trail-volume-gpu-regression'
New-Item -ItemType Directory -Force -Path $outputDir | Out-Null
& (Join-Path $jdkBin 'javac.exe') -encoding UTF-8 -cp $classpath -d $outputDir `
    (Join-Path $PSScriptRoot 'RocketTrailVolumeGpuRegression.java')
if ($LASTEXITCODE -ne 0) { throw 'Rocket trail volume GPU regression compilation failed' }
& (Join-Path $jdkBin 'java.exe') "-Dorg.lwjgl.system.SharedLibraryExtractPath=$outputDir/natives" `
    -cp "$classpath;$outputDir" org.ywzj.rvp.client.light.RocketTrailVolumeGpuRegression $projectRoot |
    Tee-Object -FilePath (Join-Path $outputDir 'result.txt')
if ($LASTEXITCODE -ne 0) { throw 'Rocket trail volume GPU regression failed' }
