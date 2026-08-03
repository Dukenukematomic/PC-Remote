@echo off
title PC Remote server
cd /d "%~dp0"
python remote_server.py %*
if errorlevel 1 pause
