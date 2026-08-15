@echo off
chcp 65001 >nul
title NeonDrive - граф маршрутов и камеры
echo.
echo   NeonDrive: сборка офлайн-графа маршрутов и выгрузка камер из OSM.
echo.
echo   Скачается около 500 МБ (карта OSM, GraphHopper, Java 11),
echo   сборка графа займёт от 10 минут до часа.
echo   Всё складывается в .buildtools и .graphbuild внутри проекта,
echo   в систему ничего не устанавливается.
echo.
echo   Результат — папка device-files, её содержимое копируется на магнитолу.
echo.
pause
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0build-graph.ps1" %*
if errorlevel 1 (
  echo.
  echo   Завершилось с ошибкой. Текст ошибки выше, полный лог импорта —
  echo   в .graphbuild\import.log
  pause
) else (
  echo.
  pause
)
