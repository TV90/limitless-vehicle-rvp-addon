$ErrorActionPreference = 'Stop'

$root = Split-Path -Parent $MyInvocation.MyCommand.Path
Set-Location $root

$logPath = Join-Path $root 'trace_build_output.txt'
$metaPath = Join-Path $root 'trace_build_meta.txt'

if (Test-Path $logPath) { Remove-Item $logPath -Force }
if (Test-Path $metaPath) { Remove-Item $metaPath -Force }

$start = Get-Date
"start=$($start.ToString('yyyy-MM-dd HH:mm:ss'))" | Set-Content $metaPath

& .\gradlew.bat clean build --stacktrace *> $logPath
$exitCode = $LASTEXITCODE

"exit=$exitCode" | Add-Content $metaPath

$jarPaths = @(
    '.\build\libs\ywzj_rvp-1.20.1-0.5.5.jar',
    '.\build\libs\ywzj_rvp-1.20.1-0.5.5-all.jar',
    '.\build\libs\reobf\ywzj_rvp-1.20.1-0.5.5-reobf.jar',
    '.\build\libs\reobf\ywzj_rvp-1.20.1-0.5.5-reobf-all.jar'
)

foreach ($jar in $jarPaths) {
    if (Test-Path $jar) {
        $item = Get-Item $jar
        "$($item.FullName)|$($item.LastWriteTime.ToString('yyyy-MM-dd HH:mm:ss'))|$($item.Length)" | Add-Content $metaPath
    } else {
        "$((Resolve-Path .).Path)\$jar|missing" | Add-Content $metaPath
    }
}

exit $exitCode
