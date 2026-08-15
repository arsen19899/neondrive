<#
    NeonDrive — сборка офлайн-графа маршрутов и базы камер.

    Делает три вещи, каждую можно запускать отдельно (см. параметры внизу):
      1. собирает граф GraphHopper из карты OSM — маршруты без интернета;
      2. выгружает камеры контроля скорости из OpenStreetMap в CSV;
      3. раскладывает результат в папку device-files\ ровно той структурой,
         которую нужно скопировать на магнитолу.

    Почему граф собирается на компьютере, а не на ГУ: импорт .osm.pbf требует
    несколько гигабайт оперативной памяти и десятков минут работы процессора.
    Ни того, ни другого на магнитоле нет — там граф только читается.

    Всё скачивается в .buildtools и .graphbuild внутри папки проекта.
    В систему ничего не устанавливается и в PATH ничего не прописывается.

    Запуск:  двойной клик по build-graph.bat
    Итог:    device-files\graph\  и  device-files\poi\
#>

param(
    # Регион. Путь внутри download.geofabrik.de — можно поменять на соседнюю
    # страну, например 'europe/poland' или 'europe/lithuania'.
    [string]$Region = 'europe/belarus',

    # Код страны ISO 3166-1 для выгрузки камер. Должен соответствовать региону.
    [string]$CountryCode = 'BY',

    # Пропустить долгий шаг сборки графа и обновить только камеры.
    [switch]$CamerasOnly,

    # Пропустить камеры и собрать только граф.
    [switch]$GraphOnly
)

$ErrorActionPreference = 'Stop'
[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12

$Root  = if ($PSScriptRoot) { $PSScriptRoot } else { (Get-Location).Path }
$Tools = Join-Path $Root '.buildtools'
$Work  = Join-Path $Root '.graphbuild'
$Out   = Join-Path $Root 'device-files'

New-Item -ItemType Directory -Force -Path $Tools, $Work | Out-Null

function Say([string]$text, [string]$color = 'Cyan') {
    Write-Host ""
    Write-Host "  $text" -ForegroundColor $color
}

function Note([string]$text) {
    Write-Host "    $text" -ForegroundColor DarkGray
}

<#
    Запись текста в UTF-8 БЕЗ метки порядка байтов.

    Set-Content -Encoding UTF8 в Windows PowerShell 5.1 ставит BOM, и это не
    придирка: в CSV три байта метки приклеиваются к первому числу, оболочка не
    может разобрать координату, и первая камера в файле молча теряется. YAML с
    BOM тоже принимают не все разборщики.
#>
function Set-Utf8NoBom([string]$path, [string]$text) {
    [IO.File]::WriteAllText($path, $text, (New-Object Text.UTF8Encoding $false))
}

<#
    Скачивание с показом прогресса.

    Карта Беларуси весит около 250 МБ, и Net.WebClient качает её молча — на
    медленном канале это выглядит как зависший скрипт. BITS показывает
    проценты и умеет продолжать оборванную закачку, поэтому он основной, а
    WebClient оставлен запасным: BITS отключён в некоторых сборках Windows.
#>
function Get-File([string]$url, [string]$dest, [string]$title, [int]$minBytes = 0) {
    if ((Test-Path $dest) -and ((Get-Item $dest).Length -gt $minBytes)) {
        Note "уже скачано: $(Split-Path $dest -Leaf)"
        return
    }
    Say "Скачиваю $title …"
    Note $url

    $ok = $false
    if (Get-Command Start-BitsTransfer -ErrorAction SilentlyContinue) {
        try {
            Start-BitsTransfer -Source $url -Destination $dest -Description $title
            $ok = $true
        } catch {
            Note "BITS не справился ($($_.Exception.Message)), пробую обычной загрузкой…"
        }
    }
    if (-not $ok) {
        $wc = New-Object Net.WebClient
        try   { $wc.DownloadFile($url, $dest) }
        catch { throw "Не удалось скачать $title.`n$($_.Exception.Message)" }
    }

    # Geofabrik и GitHub на ошибку отдают HTML-страницу с кодом 200. Без этой
    # проверки скрипт пошёл бы дальше и упал уже внутри GraphHopper с
    # невнятным сообщением про повреждённый формат.
    if ($minBytes -gt 0 -and (Get-Item $dest).Length -lt $minBytes) {
        Remove-Item $dest -Force
        throw "$title скачался неправдоподобно маленьким — вероятно, вместо файла пришла страница с ошибкой. Проверьте адрес и повторите."
    }
}

# ═════════════════════  1. JAVA 11  ═════════════════════
#
# Именно 11, а не 17 из build-local.ps1. GraphHopper 1.0 вышел в эпоху Java 8/11
# и обращается к внутренностям java.nio напрямую, чтобы освобождать
# memory-mapped буферы. С Java 17+ модульная система это запрещает, и импорт
# падает на InaccessibleObjectException. Чинится ключами --add-opens, но
# подбирать их вслепую хуже, чем взять ту версию, под которую всё писалось.
#
# Версия GraphHopper здесь тоже не случайная: формат графа между версиями
# несовместим, а в app/build.gradle.kts зависимость прибита к 1.0.

function Test-Java11([string]$javaExe) {
    if (-not (Test-Path $javaExe)) { return $false }
    try {
        # java -version печатает в stderr, а не в stdout. При
        # $ErrorActionPreference = 'Stop' перенаправленный stderr нативной
        # программы PowerShell превращает в исключение, поэтому на время вызова
        # правило приходится ослаблять — иначе проверка всегда падала бы в
        # catch и подходящая Java в системе никогда бы не находилась.
        $old = $ErrorActionPreference
        $ErrorActionPreference = 'Continue'
        $out = & $javaExe -version 2>&1 | Out-String
        $ErrorActionPreference = $old
        if ($out -match 'version "(\d+)') {
            $major = [int]$Matches[1]
            return ($major -ge 11 -and $major -le 16)
        }
    } catch { }
    return $false
}

$JavaExe = $null

# Сначала смотрим, нет ли уже подходящей Java в системе или в .buildtools.
# JAVA_HOME проверяется на пустоту до Join-Path: на машине без установленной
# Java переменной нет, и Join-Path с $null роняет скрипт на первой же строке.
$candidates = @()
if (-not [string]::IsNullOrWhiteSpace($env:JAVA_HOME)) {
    $candidates += (Join-Path $env:JAVA_HOME 'bin\java.exe')
}
$fromPath = (Get-Command java -ErrorAction SilentlyContinue).Source
if ($fromPath) { $candidates += $fromPath }

foreach ($candidate in $candidates) {
    if (Test-Java11 $candidate) { $JavaExe = $candidate; break }
}

if (-not $JavaExe) {
    $existing = Get-ChildItem -Path $Tools -Filter 'java.exe' -Recurse -ErrorAction SilentlyContinue |
                Where-Object { Test-Java11 $_.FullName } | Select-Object -First 1
    if ($existing) { $JavaExe = $existing.FullName }
}

if (-not $JavaExe) {
    $jdkZip = Join-Path $Tools 'jdk11.zip'
    $jdkDir = Join-Path $Tools 'jdk11'
    Get-File 'https://api.adoptium.net/v3/binary/latest/11/ga/windows/x64/jdk/hotspot/normal/eclipse?project=jdk' `
             $jdkZip 'портативный JDK 11 (~180 МБ)' 50000000
    if (-not (Test-Path $jdkDir)) {
        Say "Распаковываю JDK 11 …"
        Expand-Archive -Path $jdkZip -DestinationPath $jdkDir -Force
    }
    $found = Get-ChildItem -Path $jdkDir -Filter 'java.exe' -Recurse | Select-Object -First 1
    if (-not $found) { throw "JDK 11 распаковался, но java.exe в нём не нашёлся." }
    $JavaExe = $found.FullName
}

Say "Java: $JavaExe" 'Green'

# ═════════════════════  2. СКОЛЬКО ПАМЯТИ ДАТЬ  ═════════════════════
#
# Импорт страны — самая прожорливая часть. Мало памяти — падение с
# OutOfMemoryError через двадцать минут работы; слишком много — Windows уйдёт
# в своп и станет только хуже. Берём около 60 % физической памяти.

$RamGb = [math]::Round((Get-CimInstance Win32_ComputerSystem).TotalPhysicalMemory / 1GB, 1)
$XmxGb = [math]::Max(2, [math]::Min(8, [int][math]::Floor($RamGb * 0.6)))
Say "Оперативной памяти в системе: $RamGb ГБ, импорту отдаём ${XmxGb} ГБ" 'Green'
if ($XmxGb -lt 4) {
    Write-Host "    Меньше 4 ГБ под импорт — для страны размера Беларуси это впритык." -ForegroundColor Yellow
    Write-Host "    Если упадёт с OutOfMemoryError, закройте браузер и запустите снова." -ForegroundColor Yellow
}

# ═════════════════════  3. ГРАФ МАРШРУТОВ  ═════════════════════

$RegionName = ($Region -split '/')[-1]
$Pbf        = Join-Path $Work "$RegionName-latest.osm.pbf"
$GhJar      = Join-Path $Tools 'graphhopper-web-1.0.jar'
$GraphDir   = Join-Path $Work "$RegionName-gh"

if (-not $CamerasOnly) {

    Get-File "https://download.geofabrik.de/$Region-latest.osm.pbf" `
             $Pbf "карту $RegionName (~250 МБ)" 20000000

    Get-File 'https://github.com/graphhopper/graphhopper/releases/download/1.0/graphhopper-web-1.0.jar' `
             $GhJar 'GraphHopper 1.0 (~50 МБ)' 10000000

    <#
        Конфигурация импорта.

        Профиль обязан называться ровно 'car': именно это имя оболочка
        передаёт в GHRequest.setProfile (см. nav/OfflineRouter.kt). Назовёте
        иначе — граф соберётся, но на устройстве каждый маршрут будет
        отвечать «профиль не найден».

        profiles_ch — предрасчёт contraction hierarchies. Он удлиняет импорт и
        увеличивает граф, но без него маршрут через полстраны считается на
        Cortex-A53 недопустимо долго: CH переводит поиск из секунд в
        миллисекунды. Ради этого он и нужен.

        Блок server Dropwizard требует даже для команды import — она не
        поднимает сервер, но конфиг проверяет целиком.
    #>
    $ConfigPath = Join-Path $Work 'import-config.yml'
    $pbfYaml   = $Pbf.Replace('\', '/')
    $graphYaml = $GraphDir.Replace('\', '/')
    $configText = @"
graphhopper:
  datareader.file: "$pbfYaml"
  graph.location: "$graphYaml"

  graph.flag_encoders: car

  profiles:
    - name: car
      vehicle: car
      weighting: fastest

  profiles_ch:
    - profile: car

  prepare.min_network_size: 200
  prepare.min_one_way_network_size: 200

server:
  application_connectors:
    - type: http
      port: 8989
  admin_connectors:
    - type: http
      port: 8990
"@
    Set-Utf8NoBom $ConfigPath $configText

    if (Test-Path (Join-Path $GraphDir 'properties')) {
        Say "Граф уже собран: $GraphDir" 'Green'
        Note "чтобы пересобрать заново, удалите эту папку и запустите скрипт снова"
    } else {
        Say "Собираю граф. Это надолго — от 10 минут до часа, смотря какой процессор."
        Note "окно можно свернуть, но не закрывать"

        $log = Join-Path $Work 'import.log'
        # Не $args: это автоматическая переменная PowerShell, содержащая
        # аргументы самого скрипта, и перезапись её тихо ломает param().
        $ghArgs = @(
            "-Xmx${XmxGb}g",
            '-jar', $GhJar,
            'import', $ConfigPath
        )
        # Logback пишет ход импорта в stderr. С 'Stop' первая же строка лога
        # прилетела бы как ErrorRecord и оборвала сборку через пару секунд
        # после старта — с виду «упало сразу и молча». Поэтому на время
        # импорта правило ослабляем, а результат проверяем по факту: есть ли
        # на диске файл properties.
        $prevEap = $ErrorActionPreference
        $ErrorActionPreference = 'Continue'
        & $JavaExe @ghArgs 2>&1 | Tee-Object -FilePath $log
        $ErrorActionPreference = $prevEap

        if (-not (Test-Path (Join-Path $GraphDir 'properties'))) {
            Write-Host ""
            Write-Host "  Импорт не создал граф. Последние строки лога:" -ForegroundColor Red
            Get-Content $log -Tail 25 | ForEach-Object { Write-Host "    $_" -ForegroundColor DarkGray }
            Write-Host ""
            Write-Host "  Полный лог: $log" -ForegroundColor Yellow
            throw "Сборка графа не удалась."
        }
        Say "Граф собран: $GraphDir" 'Green'
    }
}

# ═════════════════════  4. КАМЕРЫ ИЗ OpenStreetMap  ═════════════════════
#
# Не база от радар-детектора, а выгрузка из OSM — открытые данные под ODbL,
# без ключей, регистрации и юридических вопросов. Стационарные камеры в
# Беларуси размечены в OSM неплохо.
#
# Формат ровно тот, который читает nav/HazardHub.kt: «долгота,широта,подпись».
# Третьей колонкой идёт maxspeed как есть — оболочка вытаскивает из неё цифры
# и показывает контролируемое ограничение. Если тега нет, колонка пустая, и
# камера просто останется без ограничения, что и правильно.

$CamerasCsv = Join-Path $Work 'osm-speed-cameras.csv'

if (-not $GraphOnly) {

    $query = @"
[out:csv(::lon,::lat,maxspeed;false)][timeout:900];
area["ISO3166-1"="$CountryCode"][admin_level=2]->.a;
(
  node["highway"="speed_camera"](area.a);
  node["enforcement"="maxspeed"](area.a);
);
out;
"@

    # Публичные зеркала Overpass регулярно отваливаются поодиночке — тот же
    # список и та же логика перебора, что в самой оболочке.
    $mirrors = @(
        'https://overpass-api.de/api/interpreter',
        'https://overpass.kumi.systems/api/interpreter',
        'https://overpass.private.coffee/api/interpreter'
    )

    $got = $false
    foreach ($mirror in $mirrors) {
        Say "Запрашиваю камеры OSM: $mirror"
        try {
            $resp = Invoke-WebRequest -Uri $mirror -Method Post `
                        -Body @{ data = $query } -TimeoutSec 900 -UseBasicParsing
            $text = $resp.Content
            if ($text -and $text.Trim().Length -gt 0) {
                Set-Utf8NoBom $CamerasCsv $text
                $got = $true
                break
            }
            Note "зеркало ответило пустотой, пробую следующее"
        } catch {
            Note "не ответило: $($_.Exception.Message)"
        }
    }

    if ($got) {
        $count = (Get-Content $CamerasCsv | Where-Object { $_.Trim() -ne '' }).Count
        Say "Камер получено: $count" 'Green'
        if ($count -lt 50) {
            Write-Host "    Подозрительно мало. Проверьте код страны -CountryCode." -ForegroundColor Yellow
        }
    } else {
        Write-Host ""
        Write-Host "  Ни одно зеркало Overpass не ответило. Граф это не ломает —" -ForegroundColor Yellow
        Write-Host "  запустите позже с ключом -CamerasOnly." -ForegroundColor Yellow
    }
}

# ═════════════════════  5. РАСКЛАДКА ПО ПАПКАМ  ═════════════════════
#
# Структура повторяет то, что должно оказаться в
# Android/data/com.neondrive.launcher/files/ на магнитоле — чтобы копировать
# можно было целиком, не думая, что куда.

Say "Раскладываю по папкам …"

$OutGraph = Join-Path $Out 'graph'
$OutPoi   = Join-Path $Out 'poi'
$OutMap   = Join-Path $Out 'map'
$OutVosk  = Join-Path $Out 'vosk'
New-Item -ItemType Directory -Force -Path $OutGraph, $OutPoi, $OutMap, $OutVosk | Out-Null

if (Test-Path (Join-Path $GraphDir 'properties')) {
    $dest = Join-Path $OutGraph (Split-Path $GraphDir -Leaf)
    if (Test-Path $dest) { Remove-Item $dest -Recurse -Force }
    Copy-Item $GraphDir $dest -Recurse
    # -File обязателен: у каталогов нет свойства Length, и Measure-Object по
    # смешанному списку при ErrorActionPreference='Stop' роняет скрипт на
    # последнем шаге, когда всё уже собрано.
    $size = [math]::Round((Get-ChildItem $dest -Recurse -File | Measure-Object Length -Sum).Sum / 1MB)
    Note "graph\$(Split-Path $GraphDir -Leaf)  —  $size МБ"
}

if (Test-Path $CamerasCsv) {
    Copy-Item $CamerasCsv (Join-Path $OutPoi 'osm-speed-cameras.csv') -Force
    Note "poi\osm-speed-cameras.csv"
}

@"
Содержимое этой папки копируется на магнитолу целиком, в
Android/data/com.neondrive.launcher/files/

  graph\  — офлайн-маршруты. Работает только если в app/build.gradle.kts
            раскомментирована зависимость graphhopper-core той же версии,
            которой собран граф (1.0). Проверить: Настройки → Навигация.

  poi\    — камеры контроля скорости. Работают сразу, ничего включать не надо.

  map\    — офлайн-карта. Положите сюда belarus.map:
            https://download.mapsforge.org/maps/v5/europe/belarus.map
            Включается: Настройки → Навигация → Карта без интернета.

  vosk\   — модель распознавания речи для голосовых команд «Елисей».
            Скачайте vosk-model-small-ru с https://alphacephei.com/vosk/models
            и распакуйте сюда так, чтобы внутри лежала папка модели.

Папки map и vosk созданы пустыми намеренно: эти файлы скачиваются вручную
по ссылкам выше, автоматизировать их скачивание скрипт не берётся — у
mapsforge и alphacephei нет стабильных прямых ссылок на «последнюю версию».
"@ | Set-Content -Path (Join-Path $Out 'ЧИТАЙ-МЕНЯ.txt') -Encoding UTF8

Write-Host ""
Write-Host "  Готово." -ForegroundColor Green
Write-Host "  Скопируйте содержимое папки device-files в" -ForegroundColor Green
Write-Host "  Android/data/com.neondrive.launcher/files/ на магнитоле." -ForegroundColor Green
Write-Host ""
Write-Host "  Промежуточные файлы (карта OSM, jar, лог) остались в .graphbuild —" -ForegroundColor DarkGray
Write-Host "  папку можно удалить, если больше не собираете граф." -ForegroundColor DarkGray
Write-Host ""
