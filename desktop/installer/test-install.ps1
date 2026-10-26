# Installs, upgrades over, and uninstalls the installer silently, checking what it leaves behind.
# Usage: test-install.ps1 <path to ...-setup.exe> <expected version>
param([Parameter(Mandatory)] [string] $Setup, [Parameter(Mandatory)] [string] $Version)
$ErrorActionPreference = 'Stop'
$dir = Join-Path $env:LOCALAPPDATA 'Programs\CastBay Desktop'
$exe = Join-Path $dir 'CastBay Desktop.exe'
$key = 'HKCU:\Software\Microsoft\Windows\CurrentVersion\Uninstall\CastBayDesktop'
$lnk = Join-Path $env:APPDATA 'Microsoft\Windows\Start Menu\Programs\CastBay Desktop.lnk'
function Check($ok, $what) { if ($ok) { "ok: $what" } else { throw "FAILED: $what" } }

foreach ($round in 1, 2) {
  $p = Start-Process $Setup -ArgumentList '/S' -Wait -PassThru
  Check ($p.ExitCode -eq 0) "install round $round exits 0"
  Check (Test-Path $exe) 'program installed'
  Check (Test-Path (Join-Path $dir 'Uninstall.exe')) 'uninstaller installed'
  Check (Test-Path $lnk) 'Start menu shortcut'
  $item = Get-ItemProperty $key
  Check ($item.DisplayVersion -eq $Version) "Settings > Apps shows version $Version"
  Check ($item.DisplayName -eq 'CastBay Desktop') 'Settings > Apps shows the name'
  Check ((Get-Item $exe).Length -gt 1MB) 'program is not empty'
}

# The program starts and stays up for a few seconds (needs WebView2, which the runner has).
$app = Start-Process $exe -PassThru
Start-Sleep 8
Check (-not $app.HasExited) 'program keeps running after start'
$app.CloseMainWindow() | Out-Null
Start-Sleep 2
if (-not $app.HasExited) { $app.Kill() }

# `_?=` makes the uninstaller run in place and wait, instead of copying itself and returning.
$u = Start-Process (Join-Path $dir 'Uninstall.exe') -ArgumentList '/S', "_?=$dir" -Wait -PassThru
Check ($u.ExitCode -eq 0) 'uninstall exits 0'
Remove-Item (Join-Path $dir 'Uninstall.exe') -Force -ErrorAction SilentlyContinue
Check (-not (Test-Path $exe)) 'program removed'
Check (-not (Test-Path $lnk)) 'Start menu shortcut removed'
Check (-not (Test-Path $key)) 'Settings > Apps entry removed'
Check (-not (Test-Path $dir)) 'install folder removed'
'installer test passed'
