param([Parameter(Mandatory=$true)][string]$GradleUserHome)
$ErrorActionPreference='Stop'
$GradleUserHome=[IO.Path]::GetFullPath($GradleUserHome)
if (!(Test-Path -LiteralPath $GradleUserHome -PathType Container)) {throw 'Choose an existing Gradle User Home explicitly'}
$template=Join-Path $PSScriptRoot 'gradle-cache-policy.init.gradle'
$directory=Join-Path $GradleUserHome 'init.d'
$destination=Join-Path $directory 'sakurabox-cache-policy.gradle'
if (Test-Path -LiteralPath $destination) {
    if ((Get-FileHash -Algorithm SHA256 -LiteralPath $destination).Hash -ne (Get-FileHash -Algorithm SHA256 -LiteralPath $template).Hash) {throw 'Existing policy differs; review it manually, do not overwrite it'}
    Write-Output 'Reviewed cache policy is already installed'
    return
}
New-Item -ItemType Directory -Path $directory -Force | Out-Null
$stream=[IO.File]::Open($destination,[IO.FileMode]::CreateNew,[IO.FileAccess]::Write,[IO.FileShare]::Read)
try {$bytes=[IO.File]::ReadAllBytes($template);$stream.Write($bytes,0,$bytes.Length)} finally {$stream.Dispose()}
Write-Output 'Installed Gradle 8.10.2 cache cleanup protection; cache cleanup remains a manual operation'
