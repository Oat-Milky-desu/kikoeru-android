param([string]$Emulator = "$env:LOCALAPPDATA\Android\Sdk\emulator\emulator.exe")
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$env:ANDROID_HOME = "$projectRoot\.tooling\sdk"
$env:ANDROID_AVD_HOME = "$projectRoot\.tooling\avd"
$env:ANDROID_USER_HOME = "$projectRoot\.tooling\android"
$avd = "$env:ANDROID_AVD_HOME\KikoeruTest.avd"
New-Item -ItemType Directory -Force -Path $avd | Out-Null
@"
avd.ini.encoding=UTF-8
path=$avd
target=android-35
"@ | Set-Content -LiteralPath "$env:ANDROID_AVD_HOME\KikoeruTest.ini" -Encoding utf8
@"
AvdId=KikoeruTest
avd.ini.displayname=Kikoeru test device
abi.type=x86_64
hw.cpu.arch=x86_64
hw.cpu.ncore=4
hw.ramSize=2048
hw.lcd.width=1080
hw.lcd.height=2400
hw.lcd.density=420
hw.keyboard=yes
hw.gpu.enabled=yes
hw.gpu.mode=swiftshader_indirect
hw.audioInput=no
hw.audioOutput=no
hw.camera.back=none
hw.camera.front=none
hw.mainKeys=no
disk.dataPartition.size=2G
image.sysdir.1=system-images\android-35\default\x86_64\
tag.id=default
tag.display=Default
"@ | Set-Content -LiteralPath "$avd\config.ini" -Encoding utf8
Start-Process -FilePath $Emulator -ArgumentList @('-avd','KikoeruTest','-port','5580','-no-window','-no-audio','-no-snapshot','-gpu','swiftshader_indirect') -WindowStyle Hidden -RedirectStandardOutput "$projectRoot\.tooling\emulator.log" -RedirectStandardError "$projectRoot\.tooling\emulator-error.log" -PassThru | Select-Object Id
