function Get-SakuraGoCache {
    param([string]$Root,[string]$GoRoot,[ValidateSet('host','native')][string]$Role,[string]$SharedCache,[string]$RunDirectory,[switch]$ColdCache,[string]$ModuleCache=$env:GOMODCACHE)
    if ($ColdCache -and $SharedCache) {throw 'Choose -ColdCache or -SharedCache, not both'}
    if ($ColdCache) {
        $path=Join-Path $RunDirectory 'cold-go-cache'
        if (Test-Path -LiteralPath $path) {throw 'Cold cache path must not already exist'}
    } elseif ($SharedCache) {
        $path=$SharedCache
    } elseif ($env:GOCACHE) {
        $path=$env:GOCACHE
    } else {
        $version=(Get-Content -LiteralPath (Join-Path $GoRoot 'VERSION') -TotalCount 1).Trim()
        if ($version -notmatch '^go[0-9]+\.[0-9]+(?:\.[0-9]+)?$') {throw 'Cannot determine the Go cache toolchain partition'}
        $cacheRoot=if ($env:SAKURABOX_CACHE_ROOT) {$env:SAKURABOX_CACHE_ROOT} else {Join-Path $Root '.local-builds/shared-cache'}
        $path=Join-Path $cacheRoot "go/$version/$Role"
    }
    if ($path -eq 'off') {throw 'A reusable directory is required for the Go build cache'}
    $path=[IO.Path]::GetFullPath($path)
    if (Test-Path -LiteralPath $path -PathType Leaf) {throw 'Go cache path is a file'}
    $rootPath=[IO.Path]::GetFullPath($Root).TrimEnd([IO.Path]::DirectorySeparatorChar,[IO.Path]::AltDirectorySeparatorChar)
    if ($path -eq $rootPath -or $rootPath.StartsWith($path.TrimEnd([IO.Path]::DirectorySeparatorChar,[IO.Path]::AltDirectorySeparatorChar)+[IO.Path]::DirectorySeparatorChar,[StringComparison]::OrdinalIgnoreCase)) {throw 'Go cache must not be a source root or its ancestor'}
    if ($ModuleCache) {
        $modulePath=[IO.Path]::GetFullPath($ModuleCache).TrimEnd([IO.Path]::DirectorySeparatorChar,[IO.Path]::AltDirectorySeparatorChar)
        if ($path -eq $modulePath -or $path.StartsWith($modulePath+[IO.Path]::DirectorySeparatorChar,[StringComparison]::OrdinalIgnoreCase)) {throw 'GOCACHE must not use module download storage'}
    }
    foreach ($source in @('app','libcore','.git','.build-deps')) {
        $protected=[IO.Path]::GetFullPath((Join-Path $Root $source)).TrimEnd([IO.Path]::DirectorySeparatorChar,[IO.Path]::AltDirectorySeparatorChar)
        if ($path -eq $protected -or $path.StartsWith($protected+[IO.Path]::DirectorySeparatorChar,[StringComparison]::OrdinalIgnoreCase)) {throw 'Go cache must not be placed in source, Git or core dependencies'}
    }
    return $path
}

function Assert-SakuraGradleCachePolicy {
    param([string]$GradleUserHome,[string]$ToolsDirectory)
    $policy=Join-Path $GradleUserHome 'init.d/sakurabox-cache-policy.gradle'
    $template=Join-Path $ToolsDirectory 'gradle-cache-policy.init.gradle'
    if (!(Test-Path -LiteralPath $policy -PathType Leaf) -or (Get-FileHash -Algorithm SHA256 -LiteralPath $policy).Hash -ne (Get-FileHash -Algorithm SHA256 -LiteralPath $template).Hash) {
        throw 'Install the reviewed cache policy into this Gradle User Home with tools/install-gradle-cache-policy.ps1 before building'
    }
}

function Write-SakuraBuildRetention {
    param([string]$RunDirectory,[string]$Kind,[hashtable]$Caches)
    $record=[ordered]@{
        schema=1; kind=$Kind; recorded_before_execution=$true; execution_result='See build/test logs'; caches=$Caches; automatic_deletion=$false
        retain=@('APK/AAR and source manifests','mapping','build/test logs and results','reports','config fixtures','copied test source and patches','signing materials: never cleanup candidates')
        review_generated_only=if ($Kind -eq 'android') {@('build/app/intermediates','build/app/tmp','build/app/kspCaches','project-cache')} else {@('cold-go-cache when explicitly requested')}
        cleanup_requires='Manual review of actual inputs, references, links and retained assets; this record does not authorize deletion'
    }
    # A fresh receipt per invocation also preserves evidence from resumed or failed runs.
    $path=Join-Path $RunDirectory ('retention-'+[Guid]::NewGuid().ToString('N')+'.json')
    [IO.File]::WriteAllText($path,($record|ConvertTo-Json -Depth 6),[Text.UTF8Encoding]::new($false))
}
