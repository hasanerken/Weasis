@echo off
:: =============================================================================
:: ZenViewer / Weasis - XML Entity Limit Fix
:: Removes the JDK 100,000-char XML entity size limit that causes large
:: DICOM manifests (50+ patients) to fail loading in ZenViewer.
::
:: Safe to run multiple times (idempotent - won't add duplicate entries).
:: No administrator rights required.
:: =============================================================================

setlocal enabledelayedexpansion

echo.
echo ZenViewer / Weasis - XML Entity Limit Fix
echo ==========================================
echo.

set PATCHED=0
set FOUND=0

:: ----- ZenViewer -----
:: Try common install locations
for %%P in (
    "%LOCALAPPDATA%\ZenViewer\app\ZenViewer.cfg"
    "%ProgramFiles%\ZenViewer\app\ZenViewer.cfg"
    "%ProgramFiles(x86)%\ZenViewer\app\ZenViewer.cfg"
) do (
    if exist %%P (
        set FOUND=1
        call :PatchFile %%P "ZenViewer"
    )
)

:: ----- Weasis -----
for %%P in (
    "%APPDATA%\Weasis\weasis.cfg"
    "%LOCALAPPDATA%\Weasis\app\Weasis.cfg"
    "%ProgramFiles%\Weasis\app\Weasis.cfg"
    "%ProgramFiles(x86)%\Weasis\app\Weasis.cfg"
) do (
    if exist %%P (
        set FOUND=1
        call :PatchFile %%P "Weasis"
    )
)

if %FOUND%==0 (
    echo ERROR: Could not find ZenViewer.cfg or weasis.cfg in any known location.
    echo.
    echo Please locate the file manually and add these two lines at the end:
    echo   java-options=-Djdk.xml.maxGeneralEntitySizeLimit=0
    echo   java-options=-Djdk.xml.totalEntitySizeLimit=0
    echo.
    goto :END
)

if %PATCHED%==1 (
    echo.
    echo Done! Please RESTART ZenViewer / Weasis for the fix to take effect.
) else (
    echo.
    echo All config files were already patched. No changes needed.
)

:END
echo.
pause
endlocal
exit /b 0

:: =============================================================================
:PatchFile
:: %1 = quoted file path, %2 = app name
:: =============================================================================
set FILE=%~1
set APPNAME=%~2

echo Checking %APPNAME% config: %FILE%

:: Check if already patched
findstr /c:"maxGeneralEntitySizeLimit" "%FILE%" >nul 2>&1
if %errorlevel%==0 (
    echo   Already patched - skipping.
    echo.
    exit /b 0
)

:: Append the two JVM options
echo java-options=-Djdk.xml.maxGeneralEntitySizeLimit=0 >> "%FILE%"
echo java-options=-Djdk.xml.totalEntitySizeLimit=0 >> "%FILE%"

echo   Patched successfully.
echo.
set PATCHED=1
exit /b 0
