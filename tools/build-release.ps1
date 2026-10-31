<#
.SYNOPSIS
  Builds both release files into dist\: Shiftbell-Setup-<version>.exe and Shiftbell-<version>.apk.

.DESCRIPTION
  Always builds from source, runs the tests first, and refuses to continue if the Windows and
  Android versions disagree. Compiling an installer around whatever happens to be in dist\ is how
  a release ships yesterday's binary.
#>
[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$dist = Join-Path $root 'dist'

# One version for both apps: Directory.Build.props for Windows, versionName for Android.
$version = ([xml](Get-Content (Join-Path $root 'Directory.Build.props'))).Project.PropertyGroup.ShiftbellVersion | Where-Object { $_ } | Select-Object -First 1
$gradle = Get-Content (Join-Path $root 'android\app\build.gradle.kts') -Raw
if ($gradle -notmatch 'versionName\s*=\s*"([^"]+)"') { throw 'versionName not found in android\app\build.gradle.kts' }
if ($Matches[1] -ne $version) { throw "Windows is $version but Android is $($Matches[1]); make them match first." }
Write-Output "Building Shiftbell $version"

function Find-Iscc {
    foreach ($key in @(
        'HKCU:\SOFTWARE\Microsoft\Windows\CurrentVersion\Uninstall\Inno Setup 6_is1',
        'HKLM:\SOFTWARE\WOW6432Node\Microsoft\Windows\CurrentVersion\Uninstall\Inno Setup 6_is1',
        'HKLM:\SOFTWARE\Microsoft\Windows\CurrentVersion\Uninstall\Inno Setup 6_is1')) {
        $location = (Get-ItemProperty $key -ErrorAction SilentlyContinue).InstallLocation
        if ($location -and (Test-Path (Join-Path $location 'ISCC.exe'))) { return Join-Path $location 'ISCC.exe' }
    }
    throw 'Inno Setup 6 not found. Install it with: winget install JRSoftware.InnoSetup'
}

# Windows: test, publish the single self-contained exe, wrap it in the installer.
dotnet test (Join-Path $root 'Shiftbell.slnx') --nologo -v q
if ($LASTEXITCODE -ne 0) { throw 'Windows tests failed' }
dotnet publish (Join-Path $root 'src\Shiftbell\Shiftbell.csproj') -c Release -o $dist --nologo -v q
if ($LASTEXITCODE -ne 0) { throw 'dotnet publish failed' }
& (Find-Iscc) /Q "/DAppVersion=$version" (Join-Path $root 'tools\installer\Shiftbell.iss')
if ($LASTEXITCODE -ne 0) { throw 'Inno Setup failed' }

# Android: test, lint (warnings fail it), build the release APK.
Push-Location (Join-Path $root 'android')
try {
    & .\gradlew.bat :core:test :app:lintDebug :app:assembleRelease --console=plain -q
    if ($LASTEXITCODE -ne 0) { throw 'Android build failed' }
} finally {
    Pop-Location
}
Copy-Item (Join-Path $root 'android\app\build\outputs\apk\release\app-release.apk') (Join-Path $dist "Shiftbell-$version.apk") -Force

foreach ($file in "Shiftbell-Setup-$version.exe", "Shiftbell-$version.apk") {
    $path = Join-Path $dist $file
    $hash = (Get-FileHash $path -Algorithm SHA256).Hash.ToLowerInvariant()
    Write-Output ('{0}  {1:N1} MB  sha256 {2}' -f $file, ((Get-Item $path).Length / 1MB), $hash)
}
