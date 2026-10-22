# Starts the bridge in the background (no window). It restarts itself if it ever stops; stop.bat ends it.
Set-Location -LiteralPath $PSScriptRoot
$running = Get-CimInstance Win32_Process -Filter "Name='java.exe' OR Name='javaw.exe'" |
	Where-Object { $_.CommandLine -like '*spawner-market-discord*' }
if ($running) {
	Write-Host 'Spawner-Markt Discord laeuft schon.' -ForegroundColor Yellow
} else {
	Remove-Item -LiteralPath (Join-Path $PSScriptRoot 'stop.flag') -ErrorAction SilentlyContinue
	Start-Process -FilePath 'cmd.exe' -ArgumentList '/c', ('"' + (Join-Path $PSScriptRoot 'run.bat') + '"') -WorkingDirectory $PSScriptRoot -WindowStyle Hidden
	Write-Host 'Spawner-Markt Discord laeuft jetzt im Hintergrund.' -ForegroundColor Green
	Write-Host 'Was passiert, steht in bridge.log. Beenden mit stop.bat.'
}
Start-Sleep -Seconds 4
