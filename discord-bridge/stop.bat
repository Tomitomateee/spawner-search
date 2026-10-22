@echo off
rem Asks the bridge to end; it checks every 5 seconds.
cd /d "%~dp0"
echo stop> stop.flag
echo Spawner-Markt Discord wird in ein paar Sekunden beendet.
timeout /t 5 >nul
