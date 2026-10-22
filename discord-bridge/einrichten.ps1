# Fragt die vier Webhook-URLs ab und schreibt sie in bridge.json (die URLs bleiben nur auf diesem PC).
Set-Location -LiteralPath $PSScriptRoot
$pattern = '^https://(ptb\.|canary\.)?(discord|discordapp)\.com/api/webhooks/\d+/[\w-]+$'
Write-Host ''
Write-Host 'Spawner-Search Discord einrichten' -ForegroundColor Cyan
Write-Host 'In Discord: Kanal bearbeiten > Integrationen > WebHooks > Spawner-Search > WebHook-URL kopieren.'
Write-Host 'Dann hier mit Rechtsklick (oder Strg+V) einfuegen und Enter druecken.'
Write-Host ''
$hooks = [ordered]@{}
foreach ($channel in 'verkauf', 'ankauf', 'preise', 'vouches') {
	while ($true) {
		$url = (Read-Host "Webhook-URL fuer #$channel").Trim().Trim('"')
		if ($url -match $pattern) { $hooks[$channel] = $url; break }
		Write-Host '  Das ist keine Discord-Webhook-URL (sie beginnt mit https://discord.com/api/webhooks/). Nochmal:' -ForegroundColor Yellow
	}
}
$server = (Read-Host 'Minecraft-Server (z.B. donutsmp.net) - leer lassen fuer alle Server').Trim()
$config = [ordered]@{
	server = $server
	webhooks = $hooks
	maxPerSpawner = 10
	postExistingVouches = $false
}
$json = $config | ConvertTo-Json -Depth 4
[System.IO.File]::WriteAllText((Join-Path $PSScriptRoot 'bridge.json'), $json, (New-Object System.Text.UTF8Encoding $false))
Write-Host ''
Write-Host 'Gespeichert in bridge.json. Jetzt start.bat starten.' -ForegroundColor Green
Read-Host 'Enter zum Schliessen'
