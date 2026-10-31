# Publishes Shiftbell and installs it for the current user. Safe to re-run: it upgrades in place.
#   1. publishes one self-contained Shiftbell.exe to dist\
#   2. stops the installed copy if it is running, and copies the new exe over it
#   3. adds a Start menu shortcut
#   4. starts it through Explorer, so it is not tied to the shell that ran this script
# On start the app writes its own Run key and its "Shiftbell" scheduled task (watchdog + wake-from-sleep).
$ErrorActionPreference = 'Stop'

$root = Resolve-Path (Join-Path $PSScriptRoot '..')
$dist = Join-Path $root 'dist'
$target = Join-Path $env:LOCALAPPDATA 'Programs\Shiftbell'
$exe = Join-Path $target 'Shiftbell.exe'

dotnet publish (Join-Path $root 'src\Shiftbell\Shiftbell.csproj') -c Release -o $dist
if ($LASTEXITCODE -ne 0) { throw "dotnet publish failed ($LASTEXITCODE)" }

if (Test-Path $exe) {
    $running = Get-Process Shiftbell -ErrorAction SilentlyContinue | Where-Object { $_.Path -eq $exe }
    if ($running) {
        Start-Process $exe -ArgumentList '--exit' -Wait
        $running | ForEach-Object { if (-not $_.WaitForExit(10000)) { Stop-Process -Id $_.Id -Force } }
    }
}

New-Item -ItemType Directory -Force $target | Out-Null
Copy-Item (Join-Path $dist 'Shiftbell.exe') $exe -Force

$shortcut = Join-Path ([Environment]::GetFolderPath('Programs')) 'Shiftbell.lnk'
$shell = New-Object -ComObject WScript.Shell
$link = $shell.CreateShortcut($shortcut)
$link.TargetPath = $exe
$link.WorkingDirectory = $target
$link.Description = 'Time-in and time-out alarms that cannot be missed'
$link.Save()

Start-Process explorer.exe "`"$exe`""
Write-Output "Installed $exe ($([math]::Round((Get-Item $exe).Length / 1MB, 1)) MB) and started it."
