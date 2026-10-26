# The licence page of the MSI wants RTF; this wraps the plain GPL text (monospaced, line by line).
param([Parameter(Mandatory)] [string] $Text, [Parameter(Mandatory)] [string] $Out)
$lines = Get-Content -LiteralPath $Text -Encoding UTF8 | ForEach-Object {
  ($_ -replace '\\', '\\\\' -replace '\{', '\{' -replace '\}', '\}')
}
$body = ($lines -join "\par`r`n")
"{\rtf1\ansi\deff0{\fonttbl{\f0\fmodern Consolas;}}\f0\fs18 $body}" | Out-File -LiteralPath $Out -Encoding ascii
