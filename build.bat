@echo off
setlocal enabledelayedexpansion

echo ===================================================
echo   Compiling Collaborative Study Hub (Java SE 17+)   
echo ===================================================

set JAVAC_CMD=javac
if exist "%JAVA_HOME%\bin\javac.exe" (
    set "JAVAC_CMD=%JAVA_HOME%\bin\javac.exe"
) else (
    for /d %%i in ("%ProgramFiles%\Java\jdk-*") do (
        if exist "%%i\bin\javac.exe" set "JAVAC_CMD=%%i\bin\javac.exe"
    )
)

if not exist out mkdir out
dir /s /b src\*.java > sources.txt
"%JAVAC_CMD%" -encoding UTF-8 -d out @sources.txt
set COMPILE_STATUS=%ERRORLEVEL%
del sources.txt

if %COMPILE_STATUS% EQU 0 (
    echo [BUILD SUCCESS] All classes compiled cleanly to out\
) else (
    echo [BUILD FAILED] Compilation errors occurred.
)
exit /b %COMPILE_STATUS%
