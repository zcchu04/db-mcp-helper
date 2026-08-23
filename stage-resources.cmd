@echo off
rem Stage embedded resources into setup-app resources (run before mvn package of setup-app).
rem Pure copier: all three source paths are supplied by package-windows.ps1 via env vars.
rem TOOLKIT_SRC / TAP_JAR / RUNTIME_ZIP — if empty, fall back to conventional locations.
setlocal
set BASE=%~dp0
set RES=%BASE%setup-app\src\main\resources

if "%TOOLKIT_SRC%"=="" set TOOLKIT_SRC=%BASE%oracle-mcp-src\src\oracle-db-mcp-java-toolkit\target\oracle-db-mcp-toolkit-1.0.0.jar
if "%TAP_JAR%"=="" set TAP_JAR=%BASE%mcp-tap\target\mcp-tap.jar
if "%RUNTIME_ZIP%"=="" set RUNTIME_ZIP=%BASE%dist\mcp-runtime.zip

if not exist "%TOOLKIT_SRC%" (echo [ERROR] toolkit JAR not found: %TOOLKIT_SRC% & exit /b 1)
if not exist "%TAP_JAR%" (echo [ERROR] build mcp-tap first: mvn package -pl mcp-tap & exit /b 1)
if not exist "%RUNTIME_ZIP%" (echo [ERROR] missing dist\mcp-runtime.zip (jlink + zip) & exit /b 1)

if not exist "%RES%\toolkit" mkdir "%RES%\toolkit"
if not exist "%RES%\tap" mkdir "%RES%\tap"
if not exist "%RES%\runtime" mkdir "%RES%\runtime"

copy /Y "%TOOLKIT_SRC%" "%RES%\toolkit\oracle-db-mcp-toolkit-1.0.0.jar" >nul
copy /Y "%TAP_JAR%" "%RES%\tap\mcp-tap.jar" >nul
copy /Y "%RUNTIME_ZIP%" "%RES%\runtime\mcp-runtime.zip" >nul
echo [OK] resources staged: toolkit + tap + runtime (skill and platforms.json already in resources)
endlocal
