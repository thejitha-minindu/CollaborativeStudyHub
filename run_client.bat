@echo off
setlocal enabledelayedexpansion
cd /d "%~dp0"

set JAVA_CMD=java
if exist "%JAVA_HOME%\bin\java.exe" (
    set "JAVA_CMD=%JAVA_HOME%\bin\java.exe"
) else (
    for /d %%i in ("%ProgramFiles%\Java\jdk-*") do (
        if exist "%%i\bin\java.exe" set "JAVA_CMD=%%i\bin\java.exe"
    )
)

if not exist out\com\studyhub\ui\StudyHubClientMain.class (
    call build.bat
    if errorlevel 1 exit /b 1
)

rem javaw keeps the desktop app independent of a console window.
set "JAVAW_CMD=javaw"
if exist "%JAVA_CMD%" for %%J in ("%JAVA_CMD%") do set "JAVAW_CMD=%%~dpJjavaw.exe"
start "StudyHub" "%JAVAW_CMD%" -cp out com.studyhub.ui.StudyHubClientMain
