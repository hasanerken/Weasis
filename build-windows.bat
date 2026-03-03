@echo off
REM =============================================================================
REM ZenViewer Windows MSI Builder
REM Builds a complete Windows MSI installer for ZenViewer (Weasis fork)
REM =============================================================================
setlocal enabledelayedexpansion

set SCRIPT_DIR=%~dp0
cd /d "%SCRIPT_DIR%"

REM ---- Configuration ----
REM Auto-detect JDK or set manually
set JDK=
if defined JAVA_HOME if exist "%JAVA_HOME%\bin\jpackage.exe" set "JDK=%JAVA_HOME%"
if not defined JDK for /d %%D in ("C:\Program Files\Java\jdk-*") do if exist "%%D\bin\jpackage.exe" set "JDK=%%D"
if not defined JDK for /d %%D in ("C:\Program Files\Eclipse Adoptium\jdk-*") do if exist "%%D\bin\jpackage.exe" set "JDK=%%D"
set APP_NAME=ZenViewer
REM WARNING: When changing APP_VERSION, also update:
REM   - build-dmg.sh                    (APP_VERSION=...)
REM   - weasis-launcher/conf/base.json  (zenviewer.version)
REM   - weasis-update.json in MinIO bucket zenviewer-releases
set APP_VERSION=1.0.12
set BUNDLE_ID=com.zenpacs.zenviewer
set ICON=%SCRIPT_DIR%weasis-distributions\script\resources\windows\Weasis.ico
set OUTPUT_DIR=%SCRIPT_DIR%target
set WIX_RES=%SCRIPT_DIR%build-resources\windows\msi\x86-64

REM JDK modules (Windows needs jdk.crypto.mscapi)
set JDK_MODULES=java.base,java.compiler,java.datatransfer,java.net.http,java.desktop,java.logging,java.management,java.prefs,java.xml,jdk.localedata,jdk.charsets,jdk.crypto.ec,jdk.crypto.cryptoki,jdk.jdwp.agent,java.sql,jdk.crypto.mscapi

echo =============================================
echo   ZenViewer Windows MSI Builder v%APP_VERSION%
echo =============================================
echo.

REM Verify JDK exists
if not defined JDK (
    echo ERROR: JDK 21+ not found. Searched JAVA_HOME, Program Files\Java, Eclipse Adoptium.
    echo Download from: https://adoptium.net/
    exit /b 1
)
echo   Using JDK: %JDK%

REM ---- Step 1: Maven Build ----
echo [1/6] Building Weasis project with Maven...
call mvn clean install -DskipTests -q
if errorlevel 1 (
    echo ERROR: Maven build failed
    exit /b 1
)
echo   OK  Maven build complete

REM ---- Step 2: Build native distribution ----
echo.
echo [2/6] Building native distribution...
cd weasis-distributions
call mvn clean install -DskipTests -q
if errorlevel 1 (
    echo ERROR: Distribution build failed
    exit /b 1
)
cd target\native-dist
if not exist weasis-native.zip (
    echo ERROR: weasis-native.zip not found
    exit /b 1
)
REM Extract the zip
powershell -Command "Expand-Archive -Force -Path weasis-native.zip -DestinationPath bin-dist"
cd /d "%SCRIPT_DIR%"

REM Find the correct input directory (may be nested)
set INPUT_DIR=%SCRIPT_DIR%weasis-distributions\target\native-dist\bin-dist\weasis
if not exist "%INPUT_DIR%\weasis-launcher.jar" (
    set INPUT_DIR=%SCRIPT_DIR%weasis-distributions\target\native-dist\bin-dist\bin-dist\weasis
)
if not exist "%INPUT_DIR%\weasis-launcher.jar" (
    echo ERROR: Cannot find weasis input directory
    exit /b 1
)
echo   OK  Native distribution at %INPUT_DIR%

REM ---- Step 3: Create app-image with jpackage ----
echo.
echo [3/6] Running jpackage (app-image)...
if exist "%OUTPUT_DIR%\%APP_NAME%" rmdir /s /q "%OUTPUT_DIR%\%APP_NAME%"
if not exist "%OUTPUT_DIR%" mkdir "%OUTPUT_DIR%"

"%JDK%\bin\jpackage" ^
  --type app-image ^
  --name "%APP_NAME%" ^
  --input "%INPUT_DIR%" ^
  --main-jar weasis-launcher.jar ^
  --main-class org.weasis.launcher.AppLauncher ^
  --add-modules "%JDK_MODULES%" ^
  --java-options "-Xms512m" ^
  --java-options "-Xmx6g" ^
  --java-options "-XX:+UseG1GC" ^
  --java-options "-XX:MaxGCPauseMillis=200" ^
  --java-options "--add-opens=java.base/java.lang=ALL-UNNAMED" ^
  --java-options "--add-opens=java.base/java.lang.reflect=ALL-UNNAMED" ^
  --java-options "--add-opens=java.base/java.text=ALL-UNNAMED" ^
  --java-options "--add-opens=java.desktop/java.awt=ALL-UNNAMED" ^
  --java-options "--add-opens=java.desktop/java.awt.image=ALL-UNNAMED" ^
  --java-options "--add-opens=java.desktop/java.awt.color=ALL-UNNAMED" ^
  --java-options "--add-opens=java.desktop/javax.swing=ALL-UNNAMED" ^
  --java-options "--add-opens=java.desktop/javax.swing.border=ALL-UNNAMED" ^
  --java-options "--add-opens=java.desktop/javax.swing.plaf.basic=ALL-UNNAMED" ^
  --java-options "--add-opens=java.desktop/sun.awt=ALL-UNNAMED" ^
  --java-options "--add-opens=java.desktop/sun.java2d=ALL-UNNAMED" ^
  --java-options "-Djdk.xml.maxGeneralEntitySizeLimit=0" ^
  --java-options "-Djdk.xml.totalEntitySizeLimit=0" ^
  --icon "%ICON%" ^
  --app-version "%APP_VERSION%" ^
  --dest "%OUTPUT_DIR%"

if not exist "%OUTPUT_DIR%\%APP_NAME%\%APP_NAME%.exe" (
    echo ERROR: jpackage failed to create app-image
    exit /b 1
)
echo   OK  App image created

REM ---- Step 4: Fix .cfg (CRITICAL for OSGi) ----
echo.
echo [4/6] Fixing .cfg classpath (OSGi class space fix)...
set CFG_FILE=%OUTPUT_DIR%\%APP_NAME%\app\%APP_NAME%.cfg

REM Use PowerShell to filter out bundle jar classpath entries
powershell -Command "$c = Get-Content '%CFG_FILE%'; $f = $c | Where-Object { -not ($_ -match 'app\.classpath=.*bundle[/\\]') -and -not ($_ -match 'app\.classpath=.*bundle-i18n[/\\]') -and (-not ($_ -match 'app\.classpath=') -or $_ -match 'weasis-launcher\.jar' -or $_ -match 'felix\.jar') }; $f | Set-Content '%CFG_FILE%'"

echo   OK  Classpath fixed (only weasis-launcher.jar + felix.jar)

REM ---- Step 5: Create MSI installer ----
echo.
echo [5/6] Creating MSI installer...
set UPGRADE_UID=b2c3d4e5-f6a7-8901-bcde-f12345678901

REM Fix Turkish locale bug: jpackage lowercases paths using system locale,
REM producing invalid WiX IDs on Turkish systems (I -> dotless-i).
set JAVA_TOOL_OPTIONS=-Duser.language=en -Duser.country=US

"%JDK%\bin\jpackage" ^
  --type msi ^
  --app-image "%OUTPUT_DIR%\%APP_NAME%" ^
  --dest "%OUTPUT_DIR%" ^
  --name "%APP_NAME%" ^
  --resource-dir "%WIX_RES%" ^
  --description "ZenViewer DICOM viewer" ^
  --win-upgrade-uuid "%UPGRADE_UID%" ^
  --win-menu ^
  --win-menu-group "%APP_NAME%" ^
  --win-shortcut-prompt ^
  --copyright "Copyright (C) 2026 ZenPACS" ^
  --app-version "%APP_VERSION%" ^
  --vendor "ZenPACS" ^
  --file-associations "%SCRIPT_DIR%weasis-distributions\script\file-associations.properties" ^
  --verbose

if not exist "%OUTPUT_DIR%\%APP_NAME%-%APP_VERSION%.msi" (
    echo ERROR: MSI creation failed
    echo.
    echo If WiX Toolset is not installed, the MSI step will fail.
    echo Install WiX 3.x from: https://github.com/wixtoolset/wix3/releases
    echo Make sure "candle.exe" and "light.exe" are in your PATH.
    echo.
    echo Alternatively, use the app-image directly from: %OUTPUT_DIR%\%APP_NAME%\
    exit /b 1
)

REM Rename to include architecture
move "%OUTPUT_DIR%\%APP_NAME%-%APP_VERSION%.msi" "%OUTPUT_DIR%\%APP_NAME%-%APP_VERSION%-x86-64.msi"

echo   OK  MSI created

REM ---- Step 6: Done ----
echo.
echo =============================================
echo   BUILD COMPLETE
echo =============================================
echo.
echo   MSI: %OUTPUT_DIR%\%APP_NAME%-%APP_VERSION%-x86-64.msi
echo   App: %OUTPUT_DIR%\%APP_NAME%\
echo.
echo   The MSI registers the zenviewer:// URL scheme
echo   in the Windows Registry automatically on install.
echo.
echo   To install: double-click the MSI file
echo   To test:    open the app from %OUTPUT_DIR%\%APP_NAME%\%APP_NAME%.exe
echo =============================================

endlocal
