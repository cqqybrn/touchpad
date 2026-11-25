@echo off
chcp 65001 >nul
set PYTHONIOENCODING=utf-8
title TouchPad PC agent
cd /d "%~dp0"
python agent.py
pause
