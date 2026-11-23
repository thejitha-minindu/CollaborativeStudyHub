@echo off
setlocal
rem Run once as Administrator on the host laptop. Rules apply only to the local subnet.
echo StudyHub - Allow collaboration on Private / Domain Wi-Fi networks
powershell -NoProfile -Command "if (-not ([Security.Principal.WindowsPrincipal][Security.Principal.WindowsIdentity]::GetCurrent()).IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) { exit 1 }"
if errorlevel 1 (
    echo Right-click allow_wifi_ports.bat and choose Run as administrator.
    pause
    exit /b 1
)
netsh advfirewall firewall show rule name="StudyHub WiFi TCP" >nul 2>&1
if errorlevel 1 (
    netsh advfirewall firewall add rule name="StudyHub WiFi TCP" dir=in action=allow protocol=TCP localport=9000,9001,9443 profile=private,domain remoteip=localsubnet
    if errorlevel 1 goto failed
)
netsh advfirewall firewall show rule name="StudyHub WiFi UDP" >nul 2>&1
if errorlevel 1 (
    netsh advfirewall firewall add rule name="StudyHub WiFi UDP" dir=in action=allow protocol=UDP localport=9876,9877 profile=private,domain remoteip=localsubnet
    if errorlevel 1 goto failed
)
echo.
echo StudyHub firewall rules are ready. Mark trusted home Wi-Fi as Private in Windows Settings.
echo If discovery is blocked on the joining laptop, run this helper there too.
echo No Public-network firewall rules were added.
pause
exit /b 0
:failed
echo Unable to add a rule. Check administrator privileges and organization firewall policy.
pause
exit /b 1
