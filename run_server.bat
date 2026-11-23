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

if not exist out\com\studyhub\server\StudyHubServerMain.class (
    call build.bat
    if errorlevel 1 exit /b 1
)

echo Starting StudyHub workspace server...
"%JAVA_CMD%" -cp out com.studyhub.server.StudyHubServerMain %*
exit /b %ERRORLEVEL%
