@echo off
rem Runs the bridge and starts it again if it stops (stop.bat ends this loop).
cd /d "%~dp0"
:loop
if exist stop.flag goto end
java -jar spawner-market-discord.jar >nul 2>&1
if exist stop.flag goto end
timeout /t 15 /nobreak >nul
goto loop
:end
del stop.flag >nul 2>&1
