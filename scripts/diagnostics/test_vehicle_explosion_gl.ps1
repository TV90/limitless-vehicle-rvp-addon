# 在隐藏OpenGL窗口运行正式程序大爆炸；仅写build，不启动游戏或改写资源。
$ErrorActionPreference = 'Stop'
# 由脚本位置解析项目，复用项目已指定的Java17与ForgeGradle缓存。
$projectRoot = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$jdkBin = 'C:\Users\FishKing0721\.jdks\ms-17.0.16\bin'
$lwjglRoot = Join-Path $env:USERPROFILE '.gradle/caches/forge_gradle/maven_downloader/org/lwjgl'
$classpath = (@('lwjgl', 'lwjgl-glfw', 'lwjgl-opengl') | ForEach-Object {
    Join-Path $lwjglRoot "$_/3.3.1/$_-3.3.1.jar"
    Join-Path $lwjglRoot "$_/3.3.1/$_-3.3.1-natives-windows.jar"
}) -join ';'
# 独立重编译唯一业务参数表，确保GPU回归无需Minecraft运行环境，也不用陈旧class默认值。
$outputDir = Join-Path $projectRoot 'build/vehicle-explosion-gpu-regression'
New-Item -ItemType Directory -Force -Path $outputDir | Out-Null
& (Join-Path $jdkBin 'javac.exe') -encoding UTF-8 -cp $classpath -d $outputDir `
    (Join-Path $PSScriptRoot 'VehicleExplosionGpuRegression.java') `
    (Join-Path $projectRoot 'src/main/java/org/ywzj/rvp/client/visual/explosion/RVP_VehicleExplosionSettings.java')
if ($LASTEXITCODE -ne 0) { throw 'Vehicle explosion GPU regression compilation failed' }
& (Join-Path $jdkBin 'java.exe') "-Dorg.lwjgl.system.SharedLibraryExtractPath=$outputDir/natives" `
    -cp "$classpath;$outputDir" org.ywzj.rvp.client.light.VehicleExplosionGpuRegression $projectRoot |
    Tee-Object -FilePath (Join-Path $outputDir 'result.txt')
if ($LASTEXITCODE -ne 0) { throw 'Vehicle explosion GPU regression failed' }
