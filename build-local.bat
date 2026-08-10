@echo off
chcp 65001 >nul
title NeonDrive - локальная сборка APK
echo.
echo   NeonDrive: сборка APK без git и без Android Studio.
echo   Всё нужное скачается в папку .buildtools внутри проекта.
echo   В систему ничего не устанавливается.
echo.
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0build-local.ps1"
if errorlevel 1 (
  echo.
  echo   Сборка завершилась с ошибкой. Прокрутите вывод вверх — текст ошибки там.
  pause
)
