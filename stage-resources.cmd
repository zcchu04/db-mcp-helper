@echo off
rem Stage embedded resources into setup-app resources (run before mvn package of setup-app).
rem Pure copier: source paths are supplied by package-windows.ps1 via env vars.
rem TOOLKIT_SRC / TAP_JAR / RUNTIME_ZIP — Oracle runtime (required).
rem MYSQL_TOOLKIT_SRC / NODE_RUNTIME_ZIP — optional, only when MySQL support is bundled.
setlocal
set BASE=%~dp0
set RES=%BASE%setup-app\src\main\resources

if "%TOOLKIT_SRC%"=="" set TOOLKIT_SRC=%BASE%oracle-mcp-src\src\oracle-db-mcp-java-toolkit\target\oracle-db-mcp-toolkit-1.0.0.jar
if "%TAP_JAR%"=="" set TAP_JAR=%BASE%mcp-tap\target\mcp-tap.jar
if "%RUNTIME_ZIP%"=="" set RUNTIME_ZIP=%BASE%dist\runtime.zip

if not exist "%TOOLKIT_SRC%" (echo [ERROR] Oracle toolkit JAR not found: %TOOLKIT_SRC% & exit /b 1)
if not exist "%TAP_JAR%" (echo [ERROR] build mcp-tap first: mvn package -pl mcp-tap & exit /b 1)
if not exist "%RUNTIME_ZIP%" (echo [ERROR] missing dist\runtime.zip (jlink + zip) & exit /b 1)

if not exist "%RES%\toolkit\oracle" mkdir "%RES%\toolkit\oracle"
if not exist "%RES%\toolkit\mysql" mkdir "%RES%\toolkit\mysql"
if not exist "%RES%\tap" mkdir "%RES%\tap"
if not exist "%RES%\runtime" mkdir "%RES%\runtime"
if not exist "%RES%\runtime\mysql" mkdir "%RES%\runtime\mysql"

rem Oracle toolkit (per-db subdir, matches backend Installer.deployToolkit)
copy /Y "%TOOLKIT_SRC%" "%RES%\toolkit\oracle\oracle-db-mcp-toolkit-1.0.0.jar" >nul
copy /Y "%TAP_JAR%" "%RES%\tap\mcp-tap.jar" >nul
copy /Y "%RUNTIME_ZIP%" "%RES%\runtime\runtime.zip" >nul

rem Optional MySQL toolkit (file or directory) -> toolkit/mysql/mysql-mcp-server
if not "%MYSQL_TOOLKIT_SRC%"=="" (
  if exist "%MYSQL_TOOLKIT_SRC%" (
    if exist "%MYSQL_TOOLKIT_SRC%\" (
      xcopy /E /I /Y "%MYSQL_TOOLKIT_SRC%" "%RES%\toolkit\mysql\mysql-mcp-server" >nul
    ) else (
      copy /Y "%MYSQL_TOOLKIT_SRC%" "%RES%\toolkit\mysql\mysql-mcp-server" >nul
    )
    echo [OK] MySQL toolkit staged
  ) else (
    echo [WARN] MYSQL_TOOLKIT_SRC set but not found: %MYSQL_TOOLKIT_SRC%
  )
)

rem Overlay version-controlled shim (Doris CONNECT_ATTRS patch + env bridging)
if exist "%RES%\toolkit\mysql\mysql-mcp-server\build" (
  copy /Y "%BASE%setup-app\src\main\shims\mysql-build-index.js" "%RES%\toolkit\mysql\mysql-mcp-server\build\index.js" >nul
  echo [OK] MySQL build/index.js shim overlaid
)

rem Optional Node runtime for MySQL -> runtime/mysql/node (zip extracted / dir copied)
if not "%NODE_RUNTIME_ZIP%"=="" (
  if exist "%NODE_RUNTIME_ZIP%" (
    if exist "%NODE_RUNTIME_ZIP%\" (
      xcopy /E /I /Y "%NODE_RUNTIME_ZIP%" "%RES%\runtime\mysql\node" >nul
    ) else (
      tar -xf "%NODE_RUNTIME_ZIP%" -C "%RES%\runtime\mysql" >nul 2>&1
    )
    echo [OK] Node runtime staged
  ) else (
    echo [WARN] NODE_RUNTIME_ZIP set but not found: %NODE_RUNTIME_ZIP%
  )
)

echo [OK] resources staged: toolkit + tap + runtime (skill and platforms.json already in resources)
endlocal
