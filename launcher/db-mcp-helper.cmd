@echo off
rem DB MCP Helper 启动器：优先使用系统 JDK 17（含 jdk.httpserver），否则回退到安装目录内捆绑运行时。
rem 由 package-windows.ps1 复制到 app-image，并由 Inno Setup 快捷方式/Run 指向本文件。
setlocal
set "APPDIR=%~dp0"
set "JAR=%APPDIR%db-mcp-setup.jar"

rem 1) 解析系统 Java：JAVA_HOME 优先，其次 PATH 上的 java
set "SYSJAVA="
if defined JAVA_HOME if exist "%JAVA_HOME%\bin\java.exe" set "SYSJAVA=%JAVA_HOME%\bin\java.exe"
if not defined SYSJAVA for %%i in (java.exe) do @if not "%%~$PATH:i"=="" set "SYSJAVA=%%~$PATH:i"

if defined SYSJAVA (
  rem 需要 JDK（含 jdk.httpserver 模块）且版本 >= 17
  "%SYSJAVA%" --list-modules 2>nul | findstr /R "^jdk.httpserver@" >nul
  if not errorlevel 1 (
    "%SYSJAVA%" -version 2>&1 | findstr /C:""17" /C:""18" /C:""19" /C:""2" >nul
    if not errorlevel 1 (
      set "SYSJAVA_W=%SYSJAVA:java.exe=javaw.exe%"
      if not exist "%SYSJAVA_W%" set "SYSJAVA_W=%SYSJAVA%"
      start "" "%SYSJAVA_W%" -jar "%JAR%" %*
      goto :eof
    )
  )
)

rem 2) 回退：安装目录内捆绑运行时
set "BUNDLED=%APPDIR%runtime\bin\java.exe"
if exist "%BUNDLED%" (
  set "BUNDLED_W=%BUNDLED:java.exe=javaw.exe%"
  if not exist "%BUNDLED_W%" set "BUNDLED_W=%BUNDLED%"
  start "" "%BUNDLED_W%" -jar "%JAR%" %*
  goto :eof
)

echo [ERROR] 未找到可用的 Java（需 JDK 17+ 且含 jdk.httpserver）。请安装 JDK 17 或重新安装本程序。
pause
