# 只读生产 Shader，在隐藏显卡上下文复现单灯多重阴影；不启动游戏、不改资源、不删除文件。
$ErrorActionPreference = 'Stop'
# 从脚本位置解析项目根目录，不依赖调用者当前目录。
$projectRoot = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
# 使用项目指定 Java 17 和已经缓存的 LWJGL（Java 调用显卡的库）。
$jdkBin = 'C:\Users\FishKing0721\.jdks\ms-17.0.16\bin'
$lwjglRoot = Join-Path $env:USERPROFILE '.gradle/caches/forge_gradle/maven_downloader/org/lwjgl'
$classpath = (@('lwjgl', 'lwjgl-glfw', 'lwjgl-opengl') | ForEach-Object {
    Join-Path $lwjglRoot "$_/3.3.1/$_-3.3.1.jar"
    Join-Path $lwjglRoot "$_/3.3.1/$_-3.3.1-natives-windows.jar"
}) -join ';'
# 每次使用新目录保留诊断历史，避免覆盖其它会话的输出。
$outputDir = Join-Path $projectRoot ('build/screen-light-shadow-diagnostic/' + (Get-Date -Format 'yyyyMMdd_HHmmss_fff'))
New-Item -ItemType Directory -Path $outputDir | Out-Null
& (Join-Path $jdkBin 'javac.exe') -encoding UTF-8 -cp $classpath -d $outputDir `
    (Join-Path $PSScriptRoot 'ScreenLightShadowDiagnostic.java') `
    (Join-Path $PSScriptRoot 'ScreenLightShaderSources.java') `
    (Join-Path $projectRoot 'src/main/java/org/ywzj/rvp/light/RVP_LightParameters.java')
if ($LASTEXITCODE -ne 0) { throw 'Single-light shadow diagnostic compilation failed' }
& (Join-Path $jdkBin 'java.exe') "-Dorg.lwjgl.system.SharedLibraryExtractPath=$outputDir/natives" `
    -cp "$classpath;$outputDir" org.ywzj.rvp.client.light.ScreenLightShadowDiagnostic $projectRoot |
    Tee-Object -FilePath (Join-Path $outputDir 'result.txt')
if ($LASTEXITCODE -ne 0) { throw 'Single-light shadow diagnostic failed' }
Write-Output "Diagnostic log: $outputDir/result.txt"
