# Ends the background bridge (and keeps run.bat from starting it again).
Set-Location -LiteralPath $PSScriptRoot
New-Item -ItemType File -Force -Path (Join-Path $PSScriptRoot 'stop.flag') | Out-Null
$procs = Get-CimInstance Win32_Process -Filter "Name='java.exe' OR Name='javaw.exe'" |
	Where-Object { $_.CommandLine -like '*spawner-market-discord*' }
foreach ($p in $procs) { Stop-Process -Id $p.ProcessId -Force }
if ($procs) { Write-Host 'Spawner-Markt Discord beendet.' -ForegroundColor Green }
else { Write-Host 'Spawner-Markt Discord lief nicht.' -ForegroundColor Yellow }
Start-Sleep -Seconds 3
