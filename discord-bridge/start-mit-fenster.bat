@echo off
rem Runs the bridge in this window, to watch it (closing the window stops it).
chcp 65001 >nul
cd /d "%~dp0"
title Spawner-Markt Discord
java -jar spawner-market-discord.jar
pause
