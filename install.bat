@echo off
echo Stopping any running DeezerScrobbler and MediaBridge processes...
taskkill /f /im DeezerScrobbler.exe 2>nul
taskkill /f /im MediaBridge.exe 2>nul

echo Starting installation of new version...
for /R "build\compose\binaries" %%f in (*.msi *.exe) do (
    start /wait "" "%%f"
    goto :done
)

echo Installer not found in build\compose\binaries. Please run gradlew packageReleaseMsi or packageReleaseExe first.
pause
:done
