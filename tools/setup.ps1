# 新电脑一键准备环境（Windows PowerShell 5.1 可用）
# 用法：在项目根目录运行  powershell -ExecutionPolicy Bypass -File tools\setup.ps1
# 需要：已装 Python 3.10+、Git for Windows（提供 bash）。中途会弹 2 次 UAC，和 1 次 Android SDK 许可协议确认。

$ErrorActionPreference = "Stop"
$ProgressPreference = "SilentlyContinue"
$base = Join-Path $env:USERPROFILE "Android"
$dl = Join-Path $base "dl"
New-Item -ItemType Directory -Force $dl | Out-Null

function Get-File($url, $name) {
    $out = Join-Path $dl $name
    if (-not (Test-Path $out)) { Write-Host "下载 $name ..."; Invoke-WebRequest $url -OutFile $out -UseBasicParsing }
    return $out
}

# 1. JDK 17（便携版，不用管理员）
if (-not (Test-Path "$base\jdk17\bin\java.exe")) {
    $zip = Get-File "https://aka.ms/download-jdk/microsoft-jdk-17-windows-x64.zip" "jdk17.zip"
    Expand-Archive $zip "$base\jdk-tmp" -Force
    Move-Item (Get-ChildItem "$base\jdk-tmp" -Directory | Select-Object -First 1).FullName "$base\jdk17"
    Remove-Item "$base\jdk-tmp" -Recurse -Force
}

# 2. Android SDK 命令行工具 + platform 34 + build-tools 34
$sdk = "$base\Sdk"
if (-not (Test-Path "$sdk\cmdline-tools\latest\bin\sdkmanager.bat")) {
    $zip = Get-File "https://dl.google.com/android/repository/commandlinetools-win-11076708_latest.zip" "cmdline-tools.zip"
    Expand-Archive $zip "$sdk\cmdline-tools-tmp" -Force
    New-Item -ItemType Directory -Force "$sdk\cmdline-tools" | Out-Null
    Move-Item "$sdk\cmdline-tools-tmp\cmdline-tools" "$sdk\cmdline-tools\latest"
    Remove-Item "$sdk\cmdline-tools-tmp" -Recurse -Force
}
$env:JAVA_HOME = "$base\jdk17"
if (-not (Test-Path "$sdk\build-tools\34.0.0\aapt2.exe")) {
    Write-Host "接下来需要你阅读并接受 Android SDK 许可协议（输入 y）"
    & "$sdk\cmdline-tools\latest\bin\sdkmanager.bat" --sdk_root="$sdk" --licenses
    & "$sdk\cmdline-tools\latest\bin\sdkmanager.bat" --sdk_root="$sdk" "platforms;android-34" "build-tools;34.0.0" "platform-tools"
}

# 3. Python 虚拟手柄库
python -m pip install vgamepad

# 4. ViGEmBus 虚拟手柄驱动（弹 UAC）
if (-not (Get-PnpDevice -FriendlyName "*Virtual Gamepad Emulation Bus*" -ErrorAction SilentlyContinue | Where-Object Status -eq OK)) {
    $exe = Get-File "https://github.com/nefarius/ViGEmBus/releases/download/v1.22.0/ViGEmBus_1.22.0_x64_x86_arm64.exe" "vigembus.exe"
    Start-Process $exe -ArgumentList "/quiet", "/norestart" -Verb RunAs -Wait
}

# 5. 防火墙放行（弹 UAC）：只允许局域网和 EasyTier 虚拟网 10.126.126.0/24
if (-not (Get-NetFirewallRule -DisplayName "TouchPad UDP 39500" -ErrorAction SilentlyContinue)) {
    $cmd = "New-NetFirewallRule -DisplayName 'TouchPad UDP 39500' -Direction Inbound -Protocol UDP -LocalPort 39500 -Action Allow -Profile Any -RemoteAddress LocalSubnet,10.126.126.0/24; " +
           "New-NetFirewallRule -DisplayName 'TouchPad APK 39501' -Direction Inbound -Protocol TCP -LocalPort 39501 -Action Allow -Profile Any -RemoteAddress LocalSubnet,10.126.126.0/24"
    Start-Process powershell -ArgumentList "-NoProfile", "-Command", $cmd -Verb RunAs -Wait
}

Write-Host "`n完成。打包 APK：bash android/build.sh    启动电脑端：pc\启动电脑端.bat"
