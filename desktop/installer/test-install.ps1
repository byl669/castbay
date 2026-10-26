# Installs, installs over, starts and uninstalls the MSI silently, checking what it leaves behind.
# Usage: test-install.ps1 <path to the .msi> <expected version>
param([Parameter(Mandatory)] [string] $Msi, [Parameter(Mandatory)] [string] $Version)
$ErrorActionPreference = 'Stop'
$dir = Join-Path $env:LOCALAPPDATA 'Programs\CastBay Desktop'
$exe = Join-Path $dir 'CastBay Desktop.exe'
$lnk = Join-Path $env:APPDATA 'Microsoft\Windows\Start Menu\Programs\CastBay Desktop.lnk'
$log = Join-Path $env:TEMP 'castbay-msi.log'
function Check($ok, $what) { if ($ok) { "ok: $what" } else { throw "FAILED: $what" } }
function Arp { Get-ItemProperty 'HKCU:\Software\Microsoft\Windows\CurrentVersion\Uninstall\*' -ErrorAction SilentlyContinue | Where-Object { $_.DisplayName -eq 'CastBay Desktop' } }
function Msiexec($arguments) {
  # WaitForExit(ms), not Start-Process -Wait: that also waits for every process the installer started.
  $p = Start-Process msiexec.exe -ArgumentList $arguments -PassThru
  if (-not $p.WaitForExit(240000)) {
    "msiexec $arguments did not finish in 4 minutes; its log:"
    Get-Content $log -Tail 60 -ErrorAction SilentlyContinue
    Get-Process msiexec -ErrorAction SilentlyContinue | Select-Object Id, StartTime | Format-Table | Out-String
    $p.Kill()
    throw "msiexec $arguments hung"
  }
  if ($p.ExitCode -ne 0) { Get-Content $log -Tail 60 -ErrorAction SilentlyContinue; throw "msiexec $arguments exited $($p.ExitCode)" }
}

foreach ($round in 1, 2) {
  Msiexec "/i `"$Msi`" /qn /l*v `"$log`""
  Check (Test-Path $exe) "program installed (round $round)"
  Check (Test-Path $lnk) 'Start menu shortcut'
  $entry = Arp
  Check ($null -ne $entry) 'Settings > Apps entry'
  Check ($entry.DisplayVersion -eq $Version) "Settings > Apps shows version $Version"
  Check ((Get-Item $exe).Length -gt 1MB) 'program is not empty'
}

# A start-with-Windows entry survives an upgrade and is removed by an uninstall.
$run = 'HKCU:\Software\Microsoft\Windows\CurrentVersion\Run'
Set-ItemProperty $run -Name CastBayDesktop -Value "`"$exe`" --minimized"
Msiexec "/i `"$Msi`" /qn /l*v `"$log`""
Check ($null -ne (Get-ItemProperty $run -Name CastBayDesktop -ErrorAction SilentlyContinue)) 'installing over keeps start-with-Windows'

# The program starts and stays up for a few seconds (needs WebView2, which the runner has).
$app = Start-Process $exe -PassThru
Start-Sleep 8
Check (-not $app.HasExited) 'program keeps running after start'

# Uninstalling closes the running program, then removes everything.
Msiexec "/x `"$Msi`" /qn /l*v `"$log`""
Check (-not (Test-Path $exe)) 'program removed'
Check (-not (Test-Path $lnk)) 'Start menu shortcut removed'
Check ($null -eq (Arp)) 'Settings > Apps entry removed'
Check ($null -eq (Get-ItemProperty $run -Name CastBayDesktop -ErrorAction SilentlyContinue)) 'start-with-Windows entry removed'
Check (-not (Test-Path $dir)) 'install folder removed'
'installer test passed'
