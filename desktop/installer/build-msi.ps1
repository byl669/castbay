# Builds the English and Chinese MSIs. Needs the WiX 4 tool (dotnet tool install --global wix --version 4.0.5).
# Usage: build-msi.ps1 -Version 0.1.0 -AppExe <exe> -LicenseTxt <LICENSE> -OutDir <dir> -NameStem CastBay-Desktop-0.1.0-windows-x64
param(
  [Parameter(Mandatory)] [string] $Version, [Parameter(Mandatory)] [string] $AppExe,
  [Parameter(Mandatory)] [string] $LicenseTxt, [Parameter(Mandatory)] [string] $OutDir,
  [Parameter(Mandatory)] [string] $NameStem
)
$ErrorActionPreference = 'Stop'
$here = Split-Path -Parent $MyInvocation.MyCommand.Path
$rtf = Join-Path $env:TEMP 'castbay-license.rtf'
& "$here\make-license-rtf.ps1" -Text $LicenseTxt -Out $rtf
New-Item -ItemType Directory -Force $OutDir | Out-Null
$env:DOTNET_ROLL_FORWARD = 'Major'
wix extension add -g WixToolset.UI.wixext/4.0.5
foreach ($culture in @(@('en-US', 1033), @('zh-CN', 2052))) {
  $name, $lcid = $culture
  $out = Join-Path $OutDir "$NameStem-$name.msi"
  wix build -arch x64 -ext WixToolset.UI.wixext -culture $name -loc "$here\$name.wxl" `
    -d "Version=$Version" -d "Lcid=$lcid" -d "AppExe=$AppExe" -d "AppIcon=$here\..\app\icons\icon.ico" `
    -d "LicenseTxt=$LicenseTxt" -d "LicenseRtf=$rtf" -o $out "$here\castbay-desktop.wxs"
  if ($LASTEXITCODE -ne 0) { throw "wix build failed for $name" }
  Get-Item $out | Select-Object Name, Length
}
