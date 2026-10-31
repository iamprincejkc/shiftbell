# Removes Shiftbell for the current user: its scheduled task, Run key, shortcut and program folder.
# Settings and history in %APPDATA%\Shiftbell are kept; delete that folder by hand to forget them too.
$ErrorActionPreference = 'Stop'

$target = Join-Path $env:LOCALAPPDATA 'Programs\Shiftbell'
$exe = Join-Path $target 'Shiftbell.exe'

if (Test-Path $exe) {
    Start-Process $exe -ArgumentList '--unregister' -Wait
    $running = Get-Process Shiftbell -ErrorAction SilentlyContinue | Where-Object { $_.Path -eq $exe }
    if ($running) {
        Start-Process $exe -ArgumentList '--exit' -Wait
        $running | ForEach-Object { if (-not $_.WaitForExit(10000)) { Stop-Process -Id $_.Id -Force } }
    }
}

$shortcut = Join-Path ([Environment]::GetFolderPath('Programs')) 'Shiftbell.lnk'
if (Test-Path $shortcut) { Remove-Item $shortcut -Force }
if (Test-Path $target) { Remove-Item $target -Recurse -Force }
Write-Output "Shiftbell removed. Settings kept in $(Join-Path $env:APPDATA 'Shiftbell')."
