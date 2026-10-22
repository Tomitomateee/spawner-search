@echo off
rem Starts the bridge in the background (javaw has no window). A second start just ends.
cd /d "%~dp0"
if exist stop.flag del stop.flag
start "" javaw -jar "%~dp0spawner-search-discord.jar"
echo Spawner-Search Discord laeuft jetzt im Hintergrund.
echo Was passiert, steht in bridge.log. Beenden mit stop.bat.
timeout /t 5 >nul
