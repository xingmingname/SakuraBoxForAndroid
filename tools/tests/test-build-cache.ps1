param([Parameter(Mandatory=$true)][string]$OutputDirectory)
$ErrorActionPreference='Stop'
$tools=Split-Path $PSScriptRoot -Parent
. (Join-Path $tools 'build-cache.ps1')
if (Test-Path -LiteralPath $OutputDirectory) {throw 'Use a new test fixture directory'}
New-Item -ItemType Directory -Path $OutputDirectory | Out-Null
$OutputDirectory=[IO.Path]::GetFullPath($OutputDirectory)
$fixture=Join-Path $OutputDirectory 'fixture';$go=Join-Path $fixture 'go';$root=Join-Path $fixture 'source';$run=Join-Path $fixture 'run'
New-Item -ItemType Directory -Path $go,$root,$run | Out-Null
[IO.File]::WriteAllText((Join-Path $go 'VERSION'),'go1.26.9')
$saved=@{};foreach($n in @('GOCACHE','GOMODCACHE','SAKURABOX_CACHE_ROOT','GRADLE_USER_HOME')) {$saved[$n]=[Environment]::GetEnvironmentVariable($n,'Process');[Environment]::SetEnvironmentVariable($n,$null,'Process')}
$results=[Collections.Generic.List[object]]::new()
function Check([string]$Name,[scriptblock]$Action) {
    try {& $Action;$results.Add(@{name=$Name;result='PASS'})}
    catch {$results.Add(@{name=$Name;result='FAIL';error=$_.Exception.Message})}
}
function Equal($Actual,$Expected) {if ($Actual -ne $Expected) {throw 'Unexpected result'}}
function Rejected([scriptblock]$Action) {$failed=$false;try {& $Action|Out-Null} catch {$failed=$true};if (!$failed) {throw 'Expected rejection was not raised'}}
try {
    $base=@{Root=$root;GoRoot=$go;Role='host';RunDirectory=$run}
    Check 'Stable cache reused across run directories' {
        $a=Get-SakuraGoCache @base;$b=Get-SakuraGoCache -Root $root -GoRoot $go -Role host -RunDirectory (Join-Path $fixture 'another-run');Equal $a $b
    }
    Check 'Host and native defaults are separate' {if ((Get-SakuraGoCache @base) -eq (Get-SakuraGoCache -Root $root -GoRoot $go -Role native -RunDirectory $run)) {throw 'Roles collided'}}
    Check 'Explicit cache wins over environment' {$env:GOCACHE=Join-Path $fixture 'env-cache';$cache=Join-Path $fixture 'explicit-cache';Equal (Get-SakuraGoCache @base -SharedCache $cache) $cache}
    Check 'Existing GOCACHE is reused' {Equal (Get-SakuraGoCache @base) $env:GOCACHE}
    $env:GOCACHE=$null
    Check 'Disabled Go cache is rejected explicitly' {$env:GOCACHE='off';try {Rejected {Get-SakuraGoCache @base}} finally {$env:GOCACHE=$null}}
    Check 'Shared cache root controls the default' {$env:SAKURABOX_CACHE_ROOT=Join-Path $fixture 'shared';Equal (Get-SakuraGoCache @base) (Join-Path $env:SAKURABOX_CACHE_ROOT 'go/go1.26.9/host')}
    $env:SAKURABOX_CACHE_ROOT=$null
    Check 'Cold cache is explicit and local to the run' {Equal (Get-SakuraGoCache @base -ColdCache) (Join-Path $run 'cold-go-cache')}
    Check 'Cold and shared flags conflict' {Rejected {Get-SakuraGoCache @base -ColdCache -SharedCache (Join-Path $fixture 'shared')}}
    Check 'Existing cold cache rejected' {New-Item -ItemType Directory -Path (Join-Path $run 'cold-go-cache')|Out-Null;Rejected {Get-SakuraGoCache @base -ColdCache}}
    Check 'Cache file rejected' {$p=Join-Path $fixture 'file';[IO.File]::WriteAllText($p,'test');Rejected {Get-SakuraGoCache @base -SharedCache $p}}
    Check 'Source root rejected' {Rejected {Get-SakuraGoCache @base -SharedCache $root}}
    Check 'Source ancestor rejected' {Rejected {Get-SakuraGoCache @base -SharedCache $fixture}}
    foreach($source in @('app','libcore','.git','.build-deps')) {
        $sourceName=$source
        Check "Protected $source cache rejected" {Rejected {Get-SakuraGoCache @base -SharedCache (Join-Path $root "$sourceName/cache")}}
    }
    Check 'Module download cache rejected' {$m=Join-Path $fixture 'modules';Rejected {Get-SakuraGoCache @base -SharedCache (Join-Path $m 'cache') -ModuleCache $m}}
    Check 'Native module discovery precedes isolated GOPATH' {$text=[IO.File]::ReadAllText((Join-Path $tools 'offline-native-build.ps1'));if ($text.IndexOf('env GOMODCACHE') -gt $text.IndexOf('$env:GOPATH=Join-Path')) {throw 'Module discovery follows the new GOPATH'}}
    $testGradleHome=Join-Path $fixture 'gradle-home';New-Item -ItemType Directory -Path $testGradleHome|Out-Null
    Check 'Missing Gradle protection rejected' {Rejected {Assert-SakuraGradleCachePolicy -GradleUserHome $testGradleHome -ToolsDirectory $tools}}
    Check 'Policy installer and exact guard' {& "$tools/install-gradle-cache-policy.ps1" -GradleUserHome $testGradleHome|Out-Null;Assert-SakuraGradleCachePolicy -GradleUserHome $testGradleHome -ToolsDirectory $tools}
    Check 'Policy installation is idempotent' {$p=Join-Path $testGradleHome 'init.d/sakurabox-cache-policy.gradle';$t=(Get-Item -LiteralPath $p).LastWriteTimeUtc;& "$tools/install-gradle-cache-policy.ps1" -GradleUserHome $testGradleHome|Out-Null;Equal (Get-Item -LiteralPath $p).LastWriteTimeUtc $t}
    Check 'Conflicting policy is preserved and rejected' {$p=Join-Path $testGradleHome 'init.d/sakurabox-cache-policy.gradle';[IO.File]::WriteAllText($p,'synthetic conflicting policy');$h=(Get-FileHash -LiteralPath $p).Hash;Rejected {& "$tools/install-gradle-cache-policy.ps1" -GradleUserHome $testGradleHome};Equal (Get-FileHash -LiteralPath $p).Hash $h;Rejected {Assert-SakuraGradleCachePolicy -GradleUserHome $testGradleHome -ToolsDirectory $tools}}
    Check 'Android entry stops before creating output without protection' {
        $newRun=Join-Path $fixture 'must-not-exist';Rejected {& "$tools/offline-android-build.ps1" -CoreAar 'not-used.aar' -RunDirectory $newRun -GradleUserHome $testGradleHome}
        if (Test-Path -LiteralPath $newRun) {throw 'Guard created output before rejecting unsafe cache policy'}
    }
    Check 'Retention receipt is nondeleting and manual' {Write-SakuraBuildRetention -RunDirectory $run -Kind android -Caches @{gocache='test'};$p=Get-ChildItem -LiteralPath $run -Filter 'retention-*.json';$d=Get-Content -LiteralPath $p.FullName -Raw|ConvertFrom-Json;Equal $d.automatic_deletion $false;if (!$d.retain -or !$d.review_generated_only) {throw 'Missing retention categories'}}
    foreach($name in @('build-cache.ps1','install-gradle-cache-policy.ps1','offline-android-build.ps1','offline-native-build.ps1','offline-go-tests.ps1')) {
        $scriptName=$name
        Check "PowerShell syntax $name" {$tokens=$null;$errors=$null;[Management.Automation.Language.Parser]::ParseFile((Join-Path $tools $scriptName),[ref]$tokens,[ref]$errors)|Out-Null;if ($errors.Count) {throw 'PowerShell syntax error'}}
    }
} finally {foreach($n in $saved.Keys) {[Environment]::SetEnvironmentVariable($n,$saved[$n],'Process')}}
$summary=@{passed=@($results|Where-Object result -eq 'PASS').Count;failed=@($results|Where-Object result -eq 'FAIL').Count;tests=$results}
$summary|ConvertTo-Json -Depth 6|Set-Content -LiteralPath (Join-Path $OutputDirectory 'results.json') -Encoding utf8
Write-Output "Cache helper tests: $($summary.passed) passed, $($summary.failed) failed"
if ($summary.failed) {exit 1}
