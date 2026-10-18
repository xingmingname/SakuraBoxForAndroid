param([string]$GoRoot, [string]$RunDirectory, [string]$OutputName = 'libcore-sing-box-1.14.3.aar', [string]$SharedCache, [string]$JavaHome=$env:JAVA_HOME, [string]$AndroidHome=$env:ANDROID_HOME, [string]$ModuleCache=$env:GOMODCACHE)
$ErrorActionPreference='Stop'
$root=Split-Path $PSScriptRoot -Parent
if (!$GoRoot) { $GoRoot=Join-Path $root '.local-builds\toolchains\go1.26.9' }
if (!$RunDirectory) { $RunDirectory=Join-Path $root ('.local-builds\native-'+(Get-Date -Format 'yyyyMMdd-HHmmss')) }
New-Item -ItemType Directory -Force -Path $RunDirectory | Out-Null
$RunDirectory=(Resolve-Path -LiteralPath $RunDirectory).Path
if ([IO.Path]::GetFileName($OutputName) -ne $OutputName) {throw 'OutputName must be a filename'}
$aar=Join-Path $RunDirectory $OutputName
if (Test-Path -LiteralPath $aar) { throw "Refusing to overwrite AAR: $aar" }
function Get-NativeSourceManifest {
    $result=[ordered]@{}
    $files=@(& rg --files --hidden (Join-Path $root 'libcore') (Join-Path $root '.build-deps\sing-box') (Join-Path $root '.build-deps\libneko') -g '*.go' -g 'go.mod' -g 'go.sum' -g '!.git/**' | Where-Object {$_ -notlike '*_test.go'} | Sort-Object)
    foreach ($file in $files) { $result[$file.Substring($root.Length+1)]=(Get-FileHash -Algorithm SHA256 -LiteralPath $file).Hash.ToLowerInvariant() }
    return ($result | ConvertTo-Json -Compress)
}
$sourceManifest=Get-NativeSourceManifest
$env:GOROOT=$GoRoot; $env:GOTOOLCHAIN='local';$env:GOPROXY='off';$env:GOSUMDB='off';$env:GOFLAGS='-mod=readonly'
$env:GOPATH=Join-Path $RunDirectory 'gopath';if (!$ModuleCache) {$ModuleCache=(& "$GoRoot\bin\go.exe" env GOMODCACHE).Trim()}; $env:GOMODCACHE=$ModuleCache;$env:GOCACHE=if ($SharedCache) { $SharedCache } else { Join-Path $RunDirectory 'go-cache' };$env:CGO_ENABLED='1'
New-Item -ItemType Directory -Force -Path (Join-Path $env:GOPATH 'pkg\gomobile') | Out-Null
if (!$JavaHome) {throw 'Set JAVA_HOME or -JavaHome to a JDK 21 directory'}; $env:JAVA_HOME=$JavaHome
if (!$AndroidHome) {throw 'Set ANDROID_HOME or -AndroidHome to your SDK'}; $env:ANDROID_HOME=$AndroidHome
$env:ANDROID_NDK_HOME=Join-Path $env:ANDROID_HOME 'ndk\27.0.12077973'
$env:PATH="$GoRoot\bin;$env:JAVA_HOME\bin;$root\.build-deps\bin;$env:PATH"
$toolDirectory=Join-Path $RunDirectory 'tools'
New-Item -ItemType Directory -Force -Path $toolDirectory | Out-Null
Push-Location (Join-Path $root '.build-deps\gomobile')
try {
    & "$GoRoot\bin\go.exe" build -o "$toolDirectory\gomobile-matsuri.exe" ./cmd/gomobile
    if ($LASTEXITCODE -ne 0) {throw 'Offline gomobile compilation failed'}
    & "$GoRoot\bin\go.exe" build -o "$toolDirectory\gobind-matsuri.exe" ./cmd/gobind
    if ($LASTEXITCODE -ne 0) {throw 'Offline gobind compilation failed'}
} finally {Pop-Location}
& "$GoRoot\bin\go.exe" build -o "$toolDirectory\gobind-launcher.exe" "$root\tools\gobind-launcher.go"
if ($LASTEXITCODE -ne 0) { throw 'Local gobind launcher compilation failed' }
$env:GOBIND="$toolDirectory\gobind-launcher.exe"
$env:CGO_LDFLAGS='-Wl,-z,max-page-size=16384 -Wl,-z,common-page-size=16384'
Push-Location (Join-Path $root 'libcore')
try {
    & "$toolDirectory\gomobile-matsuri.exe" bind -v -androidapi 21 -cache "$RunDirectory\mobile-cache" -o $aar -trimpath '-ldflags=-s -w -extldflags=-Wl,-z,max-page-size=16384' '-tags=with_conntrack,with_gvisor,with_quic,with_wireguard,with_utls,with_clash_api' . 2>&1 | Tee-Object -FilePath (Join-Path $RunDirectory ($OutputName + '.build.txt'))
    if ($LASTEXITCODE -ne 0) {throw "Offline native build failed ($LASTEXITCODE)"}
} finally {Pop-Location}
if ((Get-NativeSourceManifest) -ne $sourceManifest) { throw 'Native sources changed during compilation. Preserve this AAR and rebuild with a new output name.' }
Set-Content -LiteralPath ($aar + '.source-sha256.json') -Value $sourceManifest -Encoding utf8
Write-Output "New AAR: $aar"
