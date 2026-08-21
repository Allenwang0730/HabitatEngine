# 节点自测驱动脚本（阶段 A · 自跑测试架）
# 用法：
#   .\scripts\run_selftest.ps1                 # 直接跑（用已构建的 APK）
#   .\scripts\run_selftest.ps1 -Build          # 先 gradlew assembleDebug 再跑
#   .\scripts\run_selftest.ps1 -Only "ACTION_MATH"   # 只跑指定名称用例
# 产物：
#   selftest_results\latest.json               # 本机结果报告
#   /sdcard/Habitat/test_results.json          # 设备端结果（adb push）
param(
    [string]$Adb = "C:\Users\wang\AppData\Local\Android\Sdk\platform-tools\adb.exe",
    [string]$Package = "com.ailun.habitat",
    [string]$Repo = "C:\Users\wang\Habitat",
    [string]$Apk = "$Repo\app\build\outputs\apk\debug\app-debug.apk",
    [switch]$Build,
    [switch]$SkipInstall,
    [string]$Only = "",
    [int]$TimeoutSec = 240
)

$ErrorActionPreference = "Stop"
$adb = (Resolve-Path $Adb).Path
$outDir = Join-Path $PSScriptRoot "..\selftest_results"
New-Item -ItemType Directory -Path $outDir -Force | Out-Null
$localJson = Join-Path $outDir "latest.json"

Write-Host "== Habitat 节点自测 ==" -ForegroundColor Cyan

# 1. 构建（可选）
if ($Build) {
    Write-Host "[1/5] 构建 $Repo ..." -ForegroundColor Cyan
    Push-Location $Repo
    try { & .\gradlew.bat :app:assembleDebug --console=plain -q; if ($LASTEXITCODE -ne 0) { throw "gradle build failed" } }
    finally { Pop-Location }
}

if (-not (Test-Path $Apk)) { Write-Host "APK 不存在: $Apk（先 -Build）" -ForegroundColor Red; exit 1 }

# 2. 检查设备
Write-Host "[2/5] 检查设备..." -ForegroundColor Cyan
$devices = & $adb devices
$hasDevice = $false
foreach ($line in $devices) { if ($line -match "`tdevice`$") { $hasDevice = $true } }
if (-not $hasDevice) { Write-Host "没有已连接的设备（adb devices 应显示 device）" -ForegroundColor Red; exit 1 }

# 3. 安装（可选）
if (-not $SkipInstall) {
    Write-Host "[3/5] 安装 APK..." -ForegroundColor Cyan
    & $adb install -r $Apk
    if ($LASTEXITCODE -ne 0) { Write-Host "安装失败" -ForegroundColor Red; exit 1 }
}

# 4. 清 logcat → 启动测试
Write-Host "[4/5] 启动节点自测（auto 模式）..." -ForegroundColor Cyan
& $adb logcat -c
if ($Only) {
    & $adb shell am start -n "$Package/.NodeSelfTestActivity" --ez auto true --es only $Only | Out-Null
} else {
    & $adb shell am start -n "$Package/.NodeSelfTestActivity" --ez auto true | Out-Null
}

# 5. 轮询结束标记
$deadline = (Get-Date).AddSeconds($TimeoutSec)
$done = $false
while ((Get-Date) -lt $deadline) {
    $out = & $adb logcat -d -s HabitatSelfTest 2>$null
    if ($out -match "HABITAT_TEST_DONE") { $done = $true; break }
    Start-Sleep -Seconds 2
}
if (-not $done) {
    Write-Host "超时（${TimeoutSec}s）未等到 HABITAT_TEST_DONE" -ForegroundColor Red
    & $adb logcat -d -s HabitatSelfTest 2>$null | Select-Object -Last 30
    exit 1
}

# 6. 拉取结果：run-as 读 app 私有文件 → 推到 /sdcard/Habitat → 拉到本机
Write-Host "[5/5] 收集结果..." -ForegroundColor Cyan
& $adb shell "mkdir -p /sdcard/Habitat" | Out-Null
& $adb shell "run-as $Package cat files/test_results.json > /sdcard/Habitat/test_results.json" | Out-Null
& $adb pull /sdcard/Habitat/test_results.json $localJson | Out-Null

Write-Host "`n== 测试报告 ==" -ForegroundColor Green
& $adb logcat -d -s HabitatSelfTest 2>$null | Where-Object { $_ -match "RESULT|HABITAT_TEST_DONE" } | ForEach-Object {
    $line = $_ -replace '^.*?: ', ''
    if ($line -match "RESULT FAIL") { Write-Host $line -ForegroundColor Red }
    elseif ($line -match "RESULT SKIP") { Write-Host $line -ForegroundColor Yellow }
    elseif ($line -match "RESULT PASS") { Write-Host $line -ForegroundColor DarkGray }
    else { Write-Host $line -ForegroundColor Cyan }
}
Write-Host "`n完整报告: $localJson  （设备端: /sdcard/Habitat/test_results.json）" -ForegroundColor Green
