param([string[]]$Tasks = @('assembleDebug', 'testDebugUnitTest', 'lintDebug'))
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
Set-Location -LiteralPath $projectRoot
$jdk = Get-ChildItem -LiteralPath "$projectRoot\.tooling\jdk" -Directory -ErrorAction SilentlyContinue | Select-Object -First 1
if ($jdk) { $env:JAVA_HOME = $jdk.FullName }
if (-not $env:JAVA_HOME) { throw 'Set JAVA_HOME to JDK 17/21, or run python scripts/bootstrap-build.py.' }
if (Test-Path -LiteralPath "$projectRoot\.tooling\sdk\platforms\android-36") {
    $sdkPath = "$projectRoot\.tooling\sdk".Replace('\', '/').Replace(':', '\:')
    [System.IO.File]::WriteAllText("$projectRoot\local.properties", "sdk.dir=$sdkPath`n", [System.Text.UTF8Encoding]::new($false))
}
$env:GRADLE_USER_HOME = "$projectRoot\.gradle-user-home"
$env:ANDROID_USER_HOME = "$projectRoot\.tooling\android"
New-Item -ItemType Directory -Force -Path $env:ANDROID_USER_HOME | Out-Null
# Keep JDK socket paths short and inside the workspace (Windows AF_UNIX has a path limit).
$socketDir = "$projectRoot\.tooling\sockets"
New-Item -ItemType Directory -Force -Path $socketDir | Out-Null
if ($jdk) { $env:JAVA_TOOL_OPTIONS = "-Djdk.net.unixdomain.tmpdir=$socketDir" }
if (Test-Path -LiteralPath "$projectRoot\gradlew.bat") { & "$projectRoot\gradlew.bat" @Tasks --console=plain }
else { & "$projectRoot\.tooling\gradle-8.13\bin\gradle.bat" @Tasks --console=plain }
exit $LASTEXITCODE
