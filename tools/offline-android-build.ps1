param([Parameter(Mandatory=$true)][string]$CoreAar,[string]$RunDirectory,[string[]]$Tasks=@('testOssDebugUnitTest','assembleOssDebug','assembleOssDebugAndroidTest'),[switch]$ResumeIncomplete,[string]$JavaHome=$env:JAVA_HOME,[string]$AndroidHome=$env:ANDROID_HOME,[string]$GradleExecutable,[string]$GradleUserHome=$env:GRADLE_USER_HOME,[string]$DebugKey)
$ErrorActionPreference='Stop'
$root=Split-Path $PSScriptRoot -Parent
. (Join-Path $PSScriptRoot 'build-cache.ps1')
$env:GRADLE_USER_HOME=[IO.Path]::GetFullPath($(if ($GradleUserHome) {$GradleUserHome} elseif ($env:SAKURABOX_CACHE_ROOT) {Join-Path $env:SAKURABOX_CACHE_ROOT 'gradle-home'} else {Join-Path $root '.local-builds/gradle-home'}))
Assert-SakuraGradleCachePolicy -GradleUserHome $env:GRADLE_USER_HOME -ToolsDirectory $PSScriptRoot
$CoreAar=(Resolve-Path -LiteralPath $CoreAar).Path
if (!$RunDirectory) {$RunDirectory=Join-Path $root ('.local-builds\android-'+(Get-Date -Format 'yyyyMMdd-HHmmss'))}
if (Test-Path -LiteralPath $RunDirectory) {
    if (!$ResumeIncomplete -or (Get-ChildItem -LiteralPath $RunDirectory -Recurse -File | Where-Object {$_.Extension -in '.apk','.aar'})) {throw "Use a new build directory to preserve prior outputs: $RunDirectory"}
}
New-Item -ItemType Directory -Force -Path $RunDirectory | Out-Null
$RunDirectory=(Resolve-Path -LiteralPath $RunDirectory).Path
if (!$JavaHome) {throw 'Set JAVA_HOME or -JavaHome to JDK 21'}; $env:JAVA_HOME=$JavaHome
if (!$AndroidHome) {throw 'Set ANDROID_HOME or -AndroidHome to your SDK'}; $env:ANDROID_HOME=$AndroidHome
$env:ANDROID_USER_HOME=Join-Path $RunDirectory 'android-home'
New-Item -ItemType Directory -Force -Path $env:ANDROID_USER_HOME | Out-Null
$javaUserHome=Join-Path $RunDirectory 'user-home'
$javaAndroidHome=Join-Path $javaUserHome '.android'
New-Item -ItemType Directory -Force -Path $javaAndroidHome | Out-Null
$env:JAVA_TOOL_OPTIONS="-Duser.home=$javaUserHome"
if (!$DebugKey) {$DebugKey=Join-Path ([Environment]::GetFolderPath('UserProfile')) '.android/debug.keystore'}; $debugKey=$DebugKey
if (Test-Path -LiteralPath $debugKey) {
    Copy-Item -LiteralPath $debugKey -Destination (Join-Path $env:ANDROID_USER_HOME 'debug.keystore')
    Copy-Item -LiteralPath $debugKey -Destination (Join-Path $javaAndroidHome 'debug.keystore')
}
$env:NEKO_LOCAL_BUILD_ROOT=Join-Path $RunDirectory 'build'
$env:NEKO_CONFIG_FIXTURES=Join-Path $RunDirectory 'config-fixtures'
Write-SakuraBuildRetention -RunDirectory $RunDirectory -Kind android -Caches @{gradle_user_home=$env:GRADLE_USER_HOME;project_cache=(Join-Path $RunDirectory 'project-cache');build=(Join-Path $RunDirectory 'build')}
$env:PATH="$env:JAVA_HOME\bin;$env:PATH"
$gradle=if ($GradleExecutable) {$GradleExecutable} else {Join-Path $root 'gradlew.bat'}
$buildLog=Join-Path $RunDirectory 'gradle-build.txt'
if (Test-Path -LiteralPath $buildLog) { $buildLog=Join-Path $RunDirectory ('gradle-build-'+(Get-Date -Format 'yyyyMMdd-HHmmss')+'.txt') }
Push-Location $root
try {
    & $gradle --offline --no-daemon --max-workers=2 --console=plain '-Pkotlin.compiler.execution.strategy=in-process' --project-cache-dir "$RunDirectory\project-cache" -I "$root\tools\local-build.init.gradle" "-PnekoLocalBuildRoot=$RunDirectory\build" "-PnekoCoreAar=$CoreAar" @Tasks 2>&1 | Tee-Object -FilePath $buildLog
    if ($LASTEXITCODE -ne 0) {throw "Offline Android build failed ($LASTEXITCODE): $RunDirectory"}
} finally {Pop-Location}
Write-Output "New Android outputs: $RunDirectory\build\app\outputs"
