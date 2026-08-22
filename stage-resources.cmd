@echo off
rem Stage embedded resources into setup-app resources (run before mvn package of setup-app).
rem Env var TOOLKIT_SRC can override the toolkit JAR source path.
setlocal
set BASE=%~dp0
set RES=%BASE%setup-app\src\main\resources
if "%TOOLKIT_SRC%"=="" set TOOLKIT_SRC=%USERPROFILE%\.qoderwork\mcp\oracle-db-mcp\oracle-db-mcp-toolkit-1.0.0.jar

if not exist "%TOOLKIT_SRC%" (echo [ERROR] toolkit JAR not found: %TOOLKIT_SRC% & exit /b 1)
if not exist "%BASE%mcp-tap\target\mcp-tap.jar" (echo [ERROR] build mcp-tap first: mvn package -pl mcp-tap & exit /b 1)
if not exist "%BASE%dist\mcp-runtime.zip" (echo [ERROR] missing dist\mcp-runtime.zip (jlink + zip) & exit /b 1)

if not exist "%RES%\toolkit" mkdir "%RES%\toolkit"
if not exist "%RES%\tap" mkdir "%RES%\tap"
if not exist "%RES%\runtime" mkdir "%RES%\runtime"

copy /Y "%TOOLKIT_SRC%" "%RES%\toolkit\oracle-db-mcp-toolkit-1.0.0.jar" >nul
copy /Y "%BASE%mcp-tap\target\mcp-tap.jar" "%RES%\tap\mcp-tap.jar" >nul
copy /Y "%BASE%dist\mcp-runtime.zip" "%RES%\runtime\mcp-runtime.zip" >nul
echo [OK] resources staged: toolkit + tap + runtime (skill and platforms.json already in resources)
endlocal
