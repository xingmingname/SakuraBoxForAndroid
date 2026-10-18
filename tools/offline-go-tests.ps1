param([string]$GoRoot=$env:GOROOT,[string]$RunDirectory,[string]$SharedCache,[string]$ConfigFixtures,[string]$TestPattern='.',[string]$ModuleCache=$env:GOMODCACHE)
$ErrorActionPreference='Stop'
$root=Split-Path $PSScriptRoot -Parent
if (!$GoRoot) {throw 'Set GOROOT or -GoRoot to the installed Go toolchain'}
if (!$RunDirectory) {$RunDirectory=Join-Path $root ('.local-builds/host-tests-'+(Get-Date -Format 'yyyyMMdd-HHmmss'))}
& python (Join-Path $PSScriptRoot 'prepare-host-tests.py') --output $RunDirectory
if ($LASTEXITCODE -ne 0) {throw 'Host source preparation failed'}
$RunDirectory=(Resolve-Path -LiteralPath $RunDirectory).Path
$env:GOROOT=$GoRoot;$env:GOTOOLCHAIN='local';$env:GOPROXY='off';$env:GOSUMDB='off';$env:CGO_ENABLED='0'
if ($ModuleCache) {$env:GOMODCACHE=$ModuleCache}
$env:GOCACHE=if ($SharedCache) {$SharedCache} else {Join-Path $RunDirectory 'cache'}
$env:NEKO_CONFIG_FIXTURES=if ($ConfigFixtures) {(Resolve-Path -LiteralPath $ConfigFixtures).Path} else {''}
$env:SB_BUNDLED_UI_ZIP=Join-Path $root 'app/src/main/assets/yacd.zip'
Push-Location $RunDirectory
try {
    & "$GoRoot/bin/go.exe" test -mod=readonly -tags 'with_conntrack,with_gvisor,with_quic,with_wireguard,with_utls,with_clash_api' -count=1 -run $TestPattern -json ./... 1> 'test-results.jsonl' 2> 'test-errors.txt'
    $testExit=$LASTEXITCODE
    Set-Content -LiteralPath 'test-exit.txt' -Value $testExit
    if ($testExit -ne 0) {throw "Host regression failed ($testExit); inspect logs"}
} finally {Pop-Location}
Write-Output "Host regressions passed: $RunDirectory. Not Android physical-network verification."
