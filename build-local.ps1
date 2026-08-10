<#
    NeonDrive — локальная сборка APK без git и без установки Android Studio.

    Скрипт скачивает во вложенную папку .buildtools три вещи:
      • портативный JDK 17 (если в системе нет подходящей Java),
      • Android SDK command-line tools + платформу 34,
      • Gradle 8.9,
    после чего собирает APK. В систему ничего не устанавливается и в PATH
    ничего не прописывается — всё живёт внутри папки проекта.

    Запуск:  правой кнопкой по build-local.bat → «Запуск от имени…» не нужен,
             обычного двойного клика достаточно.

    Итог:    NeonDrive-release.apk в корне проекта.
#>

$ErrorActionPreference = 'Stop'
[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12

$Root  = if ($PSScriptRoot) { $PSScriptRoot } else { (Get-Location).Path }
$Tools = Join-Path $Root '.buildtools'
New-Item -ItemType Directory -Force -Path $Tools | Out-Null

function Say([string]$text, [string]$color = 'Cyan') {
    Write-Host ""
    Write-Host "  $text" -ForegroundColor $color
}

function Get-File([string]$url, [string]$dest, [string]$title) {
    if (Test-Path $dest) {
        Write-Host "    уже скачано: $(Split-Path $dest -Leaf)" -ForegroundColor DarkGray
        return
    }
    Say "Скачиваю $title …"
    Write-Host "    $url" -ForegroundColor DarkGray
    $wc = New-Object Net.WebClient
    try   { $wc.DownloadFile($url, $dest) }
    catch { throw "Не удалось скачать $title.`n$($_.Exception.Message)" }
}

function Expand([string]$zip, [string]$target) {
    if (-not (Test-Path $target)) {
        Say "Распаковываю $(Split-Path $zip -Leaf) …"
        Expand-Archive -Path $zip -DestinationPath $target -Force
    }
}

# ─────────────────────────  1. JAVA 17  ─────────────────────────
# Android Gradle Plugin 8.x требует ровно JDK 17 или новее.

function Test-Java([string]$javaExe) {
    if (-not (Test-Path $javaExe)) { return $false }
    try {
        $out = & $javaExe -version 2>&1 | Out-String
        if ($out -match 'version "(\d+)') { return [int]$Matches[1] -ge 17 }
    } catch { }
    return $false
}

$JavaHome = $null

foreach ($candidate in @($env:JAVA_HOME, "$env:ProgramFiles\Eclipse Adoptium\jdk-17*",
                         "$env:ProgramFiles\Java\jdk-17*", "$env:LOCALAPPDATA\Programs\Android Studio\jbr")) {
    if (-not $candidate) { continue }
    foreach ($dir in @(Resolve-Path -Path $candidate -ErrorAction SilentlyContinue)) {
        if (Test-Java (Join-Path $dir.Path 'bin\java.exe')) { $JavaHome = $dir.Path; break }
    }
    if ($JavaHome) { break }
}

if (-not $JavaHome) {
    $jdkZip = Join-Path $Tools 'jdk17.zip'
    $jdkDir = Join-Path $Tools 'jdk'
    Get-File 'https://api.adoptium.net/v3/binary/latest/17/ga/windows/x64/jdk/hotspot/normal/eclipse?project=jdk' `
             $jdkZip 'портативный JDK 17 (~190 МБ)'
    Expand $jdkZip $jdkDir
    $JavaHome = (Get-ChildItem $jdkDir -Directory | Select-Object -First 1).FullName
}

$env:JAVA_HOME = $JavaHome
$env:Path = "$JavaHome\bin;$env:Path"
Say "Java: $JavaHome" 'Green'

# ─────────────────────────  2. ANDROID SDK  ─────────────────────────

$SdkRoot = Join-Path $Tools 'android-sdk'
$CmdLine = Join-Path $SdkRoot 'cmdline-tools\latest'

if (-not (Test-Path (Join-Path $CmdLine 'bin\sdkmanager.bat'))) {
    $clZip = Join-Path $Tools 'cmdline-tools.zip'
    Get-File 'https://dl.google.com/android/repository/commandlinetools-win-11076708_latest.zip' `
             $clZip 'Android command-line tools (~130 МБ)'

    $tmp = Join-Path $Tools 'cl-tmp'
    Expand $clZip $tmp
    New-Item -ItemType Directory -Force -Path (Split-Path $CmdLine) | Out-Null
    Move-Item (Join-Path $tmp 'cmdline-tools') $CmdLine -Force
    Remove-Item $tmp -Recurse -Force -ErrorAction SilentlyContinue
}

$SdkManager = Join-Path $CmdLine 'bin\sdkmanager.bat'
$env:ANDROID_HOME     = $SdkRoot
$env:ANDROID_SDK_ROOT = $SdkRoot

Say "Принимаю лицензии Android SDK …"
$yes = ("y`n" * 30)
$yes | & $SdkManager --sdk_root="$SdkRoot" --licenses 2>&1 | Out-Null

Say "Ставлю платформу и build-tools (~600 МБ при первом запуске) …"
& $SdkManager --sdk_root="$SdkRoot" "platform-tools" "platforms;android-34" "build-tools;34.0.0" 2>&1 |
    Where-Object { $_ -notmatch '^\s*$' } | Select-Object -Last 5

Say "Android SDK: $SdkRoot" 'Green'

# local.properties — Gradle ищет SDK именно там
$sdkEscaped = $SdkRoot -replace '\\', '\\\\' -replace ':', '\:'
"sdk.dir=$sdkEscaped" | Set-Content -Path (Join-Path $Root 'local.properties') -Encoding ASCII

# ─────────────────────────  3. GRADLE  ─────────────────────────

$GradleDir = Join-Path $Tools 'gradle-8.9'
if (-not (Test-Path (Join-Path $GradleDir 'bin\gradle.bat'))) {
    $gZip = Join-Path $Tools 'gradle-8.9-bin.zip'
    Get-File 'https://services.gradle.org/distributions/gradle-8.9-bin.zip' $gZip 'Gradle 8.9 (~130 МБ)'
    Expand $gZip $Tools
}
$Gradle = Join-Path $GradleDir 'bin\gradle.bat'
Say "Gradle: $GradleDir" 'Green'

# ─────────────────────────  4. СБОРКА  ─────────────────────────

Say "Собираю APK. Первый раз это 5–10 минут — качаются зависимости." 'Yellow'
Push-Location $Root
try {
    & $Gradle assembleRelease assembleDebug --no-daemon --stacktrace
    if ($LASTEXITCODE -ne 0) { throw "Gradle завершился с ошибкой $LASTEXITCODE" }
} finally {
    Pop-Location
}

# ─────────────────────────  5. РЕЗУЛЬТАТ  ─────────────────────────

$release = Join-Path $Root 'app\build\outputs\apk\release\app-release.apk'
$debug   = Join-Path $Root 'app\build\outputs\apk\debug\app-debug.apk'

if (Test-Path $release) { Copy-Item $release (Join-Path $Root 'NeonDrive-release.apk') -Force }
if (Test-Path $debug)   { Copy-Item $debug   (Join-Path $Root 'NeonDrive-debug.apk')   -Force }

Say "Готово." 'Green'
Get-ChildItem $Root -Filter 'NeonDrive-*.apk' | ForEach-Object {
    Write-Host ("    {0}  —  {1:N1} МБ" -f $_.Name, ($_.Length / 1MB)) -ForegroundColor Green
}

Write-Host ""
Write-Host "  Скопируйте NeonDrive-release.apk на магнитолу (флешка, USB или adb install -r)" -ForegroundColor White
Write-Host "  и откройте файловым менеджером. Затем:" -ForegroundColor White
Write-Host "    Настройки → Приложения по умолчанию → Главный экран → NeonDrive" -ForegroundColor White
Write-Host "    Настройки → Уведомления → Доступ к уведомлениям → включить NeonDrive" -ForegroundColor White
Write-Host ""
Read-Host "  Нажмите Enter, чтобы закрыть"
