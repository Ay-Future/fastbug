param(
  [Parameter(Mandatory = $true)][string]$Serial,
  [Parameter(Mandatory = $true)][string]$Package,
  [string]$Session,
  [int]$Port = 52741
)

$requiredVariables = @('YUNXIAO_TOKEN', 'FASTBUG_OSS_ENDPOINT', 'FASTBUG_OSS_ACCESS_KEY_ID', 'FASTBUG_OSS_ACCESS_KEY_SECRET')
$missing = @()
foreach ($name in $requiredVariables) {
  $value = [Environment]::GetEnvironmentVariable($name, 'User')
  if ([string]::IsNullOrWhiteSpace($value)) {
    $missing += $name
  } else {
    Set-Item -LiteralPath "Env:$name" -Value $value
  }
}
if ($missing.Count -gt 0) {
  throw "Collector 未启动：Windows 用户环境变量缺失：$($missing -join '、')"
}

$arguments = @('collector/index.js', 'start', '--serial', $Serial, '--package', $Package, '--port', $Port)
if (-not [string]::IsNullOrWhiteSpace($Session)) { $arguments += @('--session', $Session) }
& node @arguments
exit $LASTEXITCODE
