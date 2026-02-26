# ZenViewer Windows Build Guide

## Prerequisites

Install these on the **Windows** machine before building:

### 1. JDK 21 or later (JDK 25 recommended)

Download from [Adoptium](https://adoptium.net/) or [Oracle](https://www.oracle.com/java/technologies/downloads/).

After installing, verify:
```cmd
java --version
jpackage --version
```

Update the `JDK` path in `build-windows.bat` if your JDK is not at `C:\Program Files\Java\jdk-25`.

### 2. Maven 3.9+

Download from [maven.apache.org](https://maven.apache.org/download.cgi).

Extract to `C:\Program Files\Maven` and add to PATH:
```cmd
set PATH=%PATH%;C:\Program Files\Maven\apache-maven-3.9.9\bin
mvn --version
```

### 3. Git

Download from [git-scm.com](https://git-scm.com/download/win).

### 4. WiX Toolset 3.x (for MSI installer)

Download from [WiX 3 Releases](https://github.com/wixtoolset/wix3/releases).

Install and verify `candle.exe` is in PATH:
```cmd
candle -help
```

> **Note**: If you skip WiX, `build-windows.bat` will still create a working app-image (portable). Only the MSI step will fail.

---

## Quick Build

```cmd
git clone https://github.com/hasanerken/Weasis.git
cd Weasis
git checkout zenpacs-4.6.6

REM Edit build-windows.bat to set your JDK path if needed
notepad build-windows.bat

build-windows.bat
```

Output: `target\ZenViewer-1.0.0-x86-64.msi`

---

## What the Build Script Does

| Step | Description |
|------|-------------|
| 1 | `mvn clean install -DskipTests` - Builds entire Weasis project |
| 2 | Builds native distribution ZIP and extracts it |
| 3 | `jpackage --type app-image` - Creates Windows app with bundled JRE |
| 4 | **Fixes .cfg** - Removes bundle JARs from classpath (OSGi requirement) |
| 5 | `jpackage --type msi` - Creates MSI installer with WiX |
| 6 | Renames MSI to include architecture |

### Critical: The .cfg Fix (Step 4)

jpackage auto-adds ALL JARs from the input directory to the classpath. For OSGi apps like ZenViewer, this causes a **class space mismatch** where classes are loaded by two different classloaders. The script removes all `bundle/*.jar` entries, keeping only:
```
app.classpath=$APPDIR/weasis-launcher.jar
app.classpath=$APPDIR/felix.jar
```

### URL Scheme Registration

The MSI installer automatically registers `zenviewer://` in the Windows Registry:
```
HKEY_CLASSES_ROOT\zenviewer\
  (Default) = "ZenViewer URI handler"
  URL Protocol = ""
  shell\open\command\
    (Default) = "C:\Program Files\ZenViewer\ZenViewer.exe" "%1"
```

This enables web-to-app launching: clicking `zenviewer://...` in a browser opens ZenViewer.

---

## Manual Build (Step by Step)

If you prefer to run each step manually:

### Step 1: Clone and build

```cmd
git clone https://github.com/hasanerken/Weasis.git
cd Weasis
git checkout zenpacs-4.6.6

mvn clean install -DskipTests
cd weasis-distributions
mvn clean install -DskipTests
```

### Step 2: Extract native distribution

```cmd
cd target\native-dist
powershell -Command "Expand-Archive -Force weasis-native.zip -DestinationPath bin-dist"
cd ..\..\..\
```

### Step 3: Create app-image

```cmd
set JDK=C:\Program Files\Java\jdk-25
set INPUT_DIR=weasis-distributions\target\native-dist\bin-dist\bin-dist\weasis

"%JDK%\bin\jpackage" ^
  --type app-image ^
  --name ZenViewer ^
  --input "%INPUT_DIR%" ^
  --main-jar weasis-launcher.jar ^
  --main-class org.weasis.launcher.AppLauncher ^
  --add-modules java.base,java.compiler,java.datatransfer,java.net.http,java.desktop,java.logging,java.management,java.prefs,java.xml,jdk.localedata,jdk.charsets,jdk.crypto.ec,jdk.crypto.cryptoki,jdk.jdwp.agent,java.sql,jdk.crypto.mscapi ^
  --java-options "-Xms64m" ^
  --java-options "-Xmx768m" ^
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
  --icon weasis-distributions\script\resources\windows\Weasis.ico ^
  --app-version 1.0.0 ^
  --dest target
```

### Step 4: Fix the .cfg file

Open `target\ZenViewer\app\ZenViewer.cfg` in a text editor.

**Delete** every line that looks like:
```
app.classpath=$APPDIR\bundle\weasis-*.jar
app.classpath=$APPDIR\bundle\jogamp-*.jar
app.classpath=$APPDIR\bundle-i18n\*.jar
...
```

**Keep** only these classpath lines:
```
app.classpath=$APPDIR\weasis-launcher.jar
app.classpath=$APPDIR\felix.jar
```

The `[JavaOptions]` section stays untouched.

### Step 5: Create MSI

```cmd
"%JDK%\bin\jpackage" ^
  --type msi ^
  --app-image target\ZenViewer ^
  --dest target ^
  --name ZenViewer ^
  --resource-dir build-resources\windows\msi\x86-64 ^
  --description "ZenViewer DICOM viewer" ^
  --win-upgrade-uuid "b2c3d4e5-f6a7-8901-bcde-f12345678901" ^
  --win-menu ^
  --win-menu-group ZenViewer ^
  --win-shortcut-prompt ^
  --copyright "Copyright (C) 2026 ZenPACS" ^
  --app-version 1.0.0 ^
  --vendor ZenPACS ^
  --file-associations weasis-distributions\script\file-associations.properties ^
  --verbose
```

### Step 6: Verify

```cmd
dir target\ZenViewer-1.0.0.msi
```

---

## Portable Mode (No MSI)

If WiX is not available, you can distribute the app-image directly:

1. Build through Step 4 (skip Step 5)
2. The `target\ZenViewer\` folder is a portable app
3. Zip it: `powershell Compress-Archive -Path target\ZenViewer -DestinationPath target\ZenViewer-1.0.0-portable.zip`
4. Users extract and run `ZenViewer.exe`

For `zenviewer://` URL scheme to work in portable mode, users must manually add a registry entry:
```reg
Windows Registry Editor Version 5.00

[HKEY_CLASSES_ROOT\zenviewer]
@="ZenViewer URI handler"
"URL Protocol"=""

[HKEY_CLASSES_ROOT\zenviewer\shell\open\command]
@="\"C:\\ZenViewer\\ZenViewer.exe\" \"%1\""
```

Save as `register-zenviewer.reg` and double-click to import. Adjust the path to match where ZenViewer is installed.

---

## Troubleshooting

### Maven build fails with "Failed to delete target/classes"
```cmd
rmdir /s /q weasis-core\target
mvn clean install -DskipTests
```

### jpackage: "Error: Invalid or unsupported type: [msi]"
WiX Toolset is not installed or not in PATH. Install WiX 3.x and restart the terminal.

### App launches but DICOM viewer is broken / services not found
The .cfg file was not fixed. Check `target\ZenViewer\app\ZenViewer.cfg` - it must only have `weasis-launcher.jar` and `felix.jar` in the classpath.

### OSGi cache stale after rebuild
Delete the cache directory:
```cmd
rmdir /s /q %USERPROFILE%\.weasis\cache-*
```
Then relaunch ZenViewer.

### zenviewer:// links don't open the app
- If MSI installed: check Registry at `HKEY_CLASSES_ROOT\zenviewer`
- If portable: run the `.reg` file described above
- Restart the browser after registry changes

---

## File Structure

```
Weasis/
  build-windows.bat                           # <-- Run this
  build-dmg.sh                                # macOS builder
  build-resources/
    windows/
      msi/
        x86-64/
          main.wxs                            # ZenViewer WiX template (zenviewer:// URL)
  weasis-distributions/
    script/
      resources/
        windows/
          Weasis.ico                          # App icon (used for both)
          msi/x86-64/main.wxs                # Original Weasis WiX (weasis:// URL)
```
