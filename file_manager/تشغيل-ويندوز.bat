@echo off
chcp 65001 >nul
cd /d "%~dp0"
title الما - مدير الملفات
where python >nul 2>nul || (echo يجب تثبيت بايثون من python.org & pause & exit /b)
if not exist ".deps_ok" (
  echo تثبيت المكتبات لأول مرة...
  python -m pip install -r requirements.txt && echo ok> .deps_ok
)
python app.py %*
pause
