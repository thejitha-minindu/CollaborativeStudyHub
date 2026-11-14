@echo off
setlocal enabledelayedexpansion

set JAVA_CMD=java
if exist "%JAVA_HOME%\bin\java.exe" (
    set "JAVA_CMD=%JAVA_HOME%\bin\java.exe"
) else (
    for /d %%i in ("%ProgramFiles%\Java\jdk-*") do (
        if exist "%%i\bin\java.exe" set "JAVA_CMD=%%i\bin\java.exe"
    )
)

echo Starting Collaborative Study Hub Client GUI...
"%JAVA_CMD%" -cp out com.studyhub.ui.StudyHubClientMain
