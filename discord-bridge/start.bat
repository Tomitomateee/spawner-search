@echo off
chcp 65001 >nul
cd /d "%~dp0"
title Spawner-Markt Discord
java -jar spawner-market-discord.jar
pause
