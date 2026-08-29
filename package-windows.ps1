#Requires -Version 5.1
<#
.SYNOPSIS
  Build DB MCP Helper Windows installer (.exe / .msi / app-image).
.DESCRIPTION
  1. Verify JDK 17 (jpackage/jlink/jdeps present)
  2. Locate Inno Setup (for final .exe) or WiX Toolset (for .msi)
  3. Build mcp-tap and locate per-DB toolkits (Oracle toolkit JAR, optional MySQL toolkit + Node runtime)
  4. jlink unified runtime (for both installer GUI and MCP server)
  5. Stage embedded resources and build setup-app fat jar
  6. jpackage --type app-image, then wrap with Inno Setup (default .exe)
     - Inno gives a real directory chooser + an uninstaller (unins000.exe)
       plus a Start Menu uninstall entry; WiX is no longer required for .exe.
  Default output is .exe (custom install dir + uninstaller via Inno Setup);
  use -Type msi to keep the pure jpackage MSI (WiX required), or app-image
  for the unpackaged app directory.
.EXAMPLE
  .\package-windows.ps1
  .\package-windows.ps1 -Type msi
  .\package-windows.ps1 -Type app-image
#>
param(
    [ValidateSet("exe", "msi", "app-image")]
    [string]$Type = "exe",

    # App metadata (override to bump versions without editing the script)
    [string]$AppName = "DB MCP Helper",
    [string]$AppVersion = "1.0.0",
    [string]$UpgradeUuid = "7b1c4e3a-2f5d-4a8b-9c6e-1d3f5a7b9c2d",

    # External toolkit JAR (CI / other machines). Falls back to a local build,
    # then to the ~/.qoderwork cache (last resort — machine-specific).
    [string]$ToolkitJar = "",

    # Tool locations (override if auto-detection fails). Empty = auto-detect.
    [string]$InnoHome = "",
    [string]$WixHome = "",

    # Override the resource sources directly (advanced). Empty = auto-resolve.
    [string]$TapJar = "",
    [string]$RuntimeZip = "",

    # Optional MySQL toolkit (dir or file). Falls back to a local build, then to a
    # ~/.qoderwork cache. Empty = MySQL support staged as unavailable until supplied.
    [string]$MysqlToolkit = "",

    # Optional bundled Node runtime for MySQL (zip or directory). Empty = skip.
    [string]$NodeRuntimeZip = "",

    # Desktop shell: "inno" wraps the jpackage app-image with Inno Setup (legacy
    # browser-based wizard). "tauri" builds a real desktop app via Tauri's WebView
    # shell that embeds the Java backend (no system browser popup).
    [ValidateSet("inno", "tauri")]
    [string]$Shell = "inno"
)

$ErrorActionPreference = "Stop"
$ProgressPreference = "SilentlyContinue"

function Assert-Command {
    param([string[]]$Names)
    foreach ($n in $Names) {
        if (-not (Get-Command $n -ErrorAction SilentlyContinue)) {
            throw "Required command not found on PATH: $n. Install it or add it to PATH."
        }
    }
}

function Test-Java17 {
    $ver = (cmd /c "java -version 2>&1") | Select-String -Pattern 'version "(.+)"' | ForEach-Object { $_.Matches.Groups[1].Value }
    if (-not $ver) {
        throw "Unable to detect Java version; ensure java is on PATH"
    }
    if ($ver -notmatch "^17\." -and $ver -notmatch "^1\.17") {
        throw "JDK 17 required, found: $ver"
    }
    # jpackage/jlink/jdeps must exist (a JRE would pass java -version but lack them)
    Assert-Command -Names @("jpackage", "jlink", "jdeps")
    Write-Host "[OK] Java $ver (jpackage/jlink/jdeps present)"
}

function Get-InnoSetupPath {
    if ($InnoHome -and (Test-Path "$InnoHome\ISCC.exe")) {
        Write-Host "[OK] Inno Setup (InnoHome): $InnoHome"
        return $InnoHome
    }
    $candidates = @(
        "$env:LOCALAPPDATA\Programs\Inno Setup 6",   # winget / per-user install
        "$env:USERPROFILE\.qoderwork\tools\innosetup6",
        "C:\Program Files (x86)\Inno Setup 6",
        "C:\Program Files\Inno Setup 6",
        "C:\Inno Setup 6"
    )
    foreach ($c in $candidates) {
        if (Test-Path "$c\ISCC.exe") {
            Write-Host "[OK] Inno Setup found: $c"
            return $c
        }
    }
    $found = Get-Command ISCC.exe -ErrorAction SilentlyContinue
    if ($found) {
        Write-Host "[OK] Inno Setup found: $($found.Source)"
        return Split-Path $found.Source
    }
    throw "Inno Setup not found; download from https://jrsoftware.org/isdl.php and install (or pass -InnoHome)"
}

function Get-WixPath {
    if ($WixHome -and (Test-Path "$WixHome\candle.exe")) {
        Write-Host "[OK] WiX (WixHome): $WixHome"
        return $WixHome
    }
    $candle = Get-Command candle.exe -ErrorAction SilentlyContinue
    if ($candle) {
        Write-Host "[OK] WiX found: $($candle.Source)"
        return Split-Path $candle.Source
    }

    $local = "$env:USERPROFILE\.qoderwork\tools\wix311"
    if (Test-Path "$local\candle.exe") {
        Write-Host "[OK] Using local WiX: $local"
        return $local
    }

    Write-Host "[INFO] WiX Toolset not found, downloading to $local ..."
    New-Item -ItemType Directory -Force -Path $local | Out-Null
    $zip = "$local\wix311-binaries.zip"
    Invoke-WebRequest -Uri "https://github.com/wixtoolset/wix3/releases/download/wix3112rtm/wix311-binaries.zip" -OutFile $zip -UseBasicParsing
    if ((Get-Item $zip).Length -lt 30MB) {
        throw "WiX download appears incomplete (GitHub may be unreachable; place wix311 at '$local' or pass -WixHome)"
    }
    Expand-Archive -Path $zip -DestinationPath $local -Force
    if (-not (Test-Path "$local\candle.exe")) {
        throw "candle.exe not found after extracting WiX"
    }
    Write-Host "[OK] WiX deployed to: $local"
    return $local
}

function Resolve-Toolkit {
    if ($ToolkitJar -and (Test-Path $ToolkitJar)) {
        Write-Host "[OK] Using -ToolkitJar: $ToolkitJar"
        return (Resolve-Path $ToolkitJar).Path
    }
    $src = "$PSScriptRoot\oracle-mcp-src\src\oracle-db-mcp-java-toolkit\target\oracle-db-mcp-toolkit-1.0.0.jar"
    $cached = "$env:USERPROFILE\.qoderwork\mcp\oracle-db-mcp\oracle-db-mcp-toolkit-1.0.0.jar"
    if (Test-Path $src) {
        Write-Host "[OK] Using built toolkit: $src"
        return (Resolve-Path $src).Path
    }
    if (Test-Path $cached) {
        Write-Host "[OK] Using cached toolkit: $cached"
        return $cached
    }
    throw "oracle-db-mcp-toolkit-1.0.0.jar not found. Build oracle/mcp, pass -ToolkitJar <path>, or place jar at $cached"
}

function Resolve-MysqlToolkit {
    if ($MysqlToolkit -and (Test-Path $MysqlToolkit)) {
        Write-Host "[OK] Using -MysqlToolkit: $MysqlToolkit"
        return (Resolve-Path $MysqlToolkit).Path
    }
    $src = "$PSScriptRoot\mysql-mcp-src\target\mysql-mcp-server"
    $cached = "$env:USERPROFILE\.qoderwork\mcp\mysql-mcp-server"
    if (Test-Path $src) {
        Write-Host "[OK] Using built MySQL toolkit: $src"
        return $src
    }
    if (Test-Path $cached) {
        Write-Host "[OK] Using cached MySQL toolkit: $cached"
        return $cached
    }
    Write-Host "[WARN] MySQL toolkit not found; MySQL support will be unavailable until supplied (-MysqlToolkit)."
    return $null
}

function Resolve-NodeRuntime {
    if ($NodeRuntimeZip -and (Test-Path $NodeRuntimeZip)) {
        Write-Host "[OK] Using -NodeRuntimeZip: $NodeRuntimeZip"
        return (Resolve-Path $NodeRuntimeZip).Path
    }
    $src = "$PSScriptRoot\dist\node-runtime.zip"
    if (Test-Path $src) {
        Write-Host "[OK] Using local Node runtime: $src"
        return $src
    }
    Write-Host "[WARN] Node runtime not found; MySQL support will be unavailable until supplied (-NodeRuntimeZip)."
    return $null
}

function Invoke-McpTapBuild {
    Write-Host "[INFO] Building mcp-tap ..."
    & mvn package -DskipTests -q -pl mcp-tap
    if ($LASTEXITCODE -ne 0) { throw "mcp-tap build failed" }
}

function Invoke-JlinkRuntime {
    param([string]$ToolkitJar)
    Write-Host "[INFO] jlink unified runtime ..."
    $mods = (cmd /c "jdeps --ignore-missing-deps --print-module-deps `"$ToolkitJar`" 2>&1") | Where-Object { $_ -notmatch "^WARNING:" }
    if ($LASTEXITCODE -ne 0) { throw "jdeps failed" }
    $full = @(
        "java.base", "java.logging", "java.xml", "java.desktop",
        "java.instrument", "java.management", "java.naming", "java.net.http",
        "java.rmi", "java.sql", "jdk.net", "jdk.security.jgss",
        "jdk.httpserver"
    )
    $all = (($full + ($mods -split ",")) | Sort-Object -Unique | Where-Object { $_.Trim() } | ForEach-Object { $_.Trim() }) -join ","
    Remove-Item -Recurse -Force "$PSScriptRoot\dist\runtime" -ErrorAction SilentlyContinue
    & jlink --add-modules $all --output "$PSScriptRoot\dist\runtime" `
        --strip-debug --no-header-files --no-man-pages --compress 2
    if ($LASTEXITCODE -ne 0) { throw "jlink runtime failed" }
    $zip = "$PSScriptRoot\dist\runtime.zip"
    $retries = 0
    while ($retries -lt 5) {
        try {
            Compress-Archive -Path "$PSScriptRoot\dist\runtime" -DestinationPath $zip -Force -ErrorAction Stop
            break
        } catch {
            $retries++
            if ($retries -eq 5) { throw }
            Write-Host "[WARN] zip locked, retrying in 2s ..."
            Start-Sleep -Seconds 2
        }
    }
}

function Get-GitBash {
    $candidates = @(
        "C:\Program Files\Git\bin\bash.exe",
        "C:\Program Files (x86)\Git\bin\bash.exe"
    )
    foreach ($c in $candidates) {
        if (Test-Path $c) { return $c }
    }
    $found = Get-Command bash -ErrorAction SilentlyContinue | Where-Object { $_.Source -notmatch "WindowsApps|System32" } | Select-Object -First 1
    if ($found) { return $found.Source }
    return $null
}

function Invoke-StageResources {
    param([string]$ToolkitJar)
    Write-Host "[INFO] Staging resources ..."
    # Resolve the three resource sources (caller may override via -TapJar/-RuntimeZip)
    $tap = if ($TapJar) { $TapJar } else { "$PSScriptRoot\mcp-tap\target\mcp-tap.jar" }
    $rt  = if ($RuntimeZip) { $RuntimeZip } else { "$PSScriptRoot\dist\runtime.zip" }
    if (-not (Test-Path $tap)) { throw "mcp-tap.jar not found; run mvn package -pl mcp-tap first" }
    if (-not (Test-Path $rt)) { throw "dist\runtime.zip not found; run jlink first" }

    # Pass all three paths as env; the stage script becomes a pure copier.
    $env:TOOLKIT_SRC = $ToolkitJar
    $env:TAP_JAR     = $tap
    $env:RUNTIME_ZIP = $rt
    if ($MysqlToolkit) { $env:MYSQL_TOOLKIT_SRC = $MysqlToolkit }
    if ($NodeRuntimeZip) { $env:NODE_RUNTIME_ZIP = $NodeRuntimeZip }

    # Prefer the native cmd script (no Git for Windows dependency). Fall back to
    # git-bash + .sh only when cmd is unavailable (e.g. non-Windows).
    $cmdScript = "$PSScriptRoot\stage-resources.cmd"
    if (Test-Path $cmdScript) {
        $p = Start-Process -FilePath "cmd.exe" -ArgumentList "/c","$cmdScript" `
            -WorkingDirectory $PSScriptRoot -Wait -PassThru -NoNewWindow
        if ($p.ExitCode -ne 0) { throw "stage-resources.cmd failed (exit $($p.ExitCode))" }
    } else {
        $bash = Get-GitBash
        if (-not $bash) { throw "Neither stage-resources.cmd nor git-bash available" }
        & $bash "$PSScriptRoot\stage-resources.sh"
        if ($LASTEXITCODE -ne 0) { throw "stage-resources.sh failed" }
    }
}

function Invoke-SetupAppBuild {
    Write-Host "[INFO] Building setup-app fat jar ..."
    & mvn package -DskipTests -q -pl setup-app
    if ($LASTEXITCODE -ne 0) { throw "setup-app build failed" }
}

function Invoke-AppImage {
    Write-Host "[INFO] jpackage --type app-image ..."
    Remove-Item -Recurse -Force "$PSScriptRoot\dist\app-staging", "$PSScriptRoot\dist\app-image" -ErrorAction SilentlyContinue
    New-Item -ItemType Directory -Force -Path "$PSScriptRoot\dist\app-staging" | Out-Null
    Copy-Item "$PSScriptRoot\setup-app\target\db-mcp-setup.jar" "$PSScriptRoot\dist\app-staging\"

    $args = @(
        "--type", "app-image",
        "--name", $AppName,
        "--app-version", $AppVersion,
        "--vendor", "dbmcp",
        "--input", "$PSScriptRoot\dist\app-staging",
        "--main-jar", "db-mcp-setup.jar",
        "--main-class", "com.dbmcp.setup.SetupMain",
        "--runtime-image", "$PSScriptRoot\dist\runtime",
        "--dest", "$PSScriptRoot\dist\app-image",
        "--icon", "$PSScriptRoot\design\icon.ico"
    )
    & jpackage @args
    if ($LASTEXITCODE -ne 0) { throw "jpackage app-image failed" }

    # 复制 prefer-system-Java 启动器作为应用入口（Inno 快捷方式指向它）
    $appImage = "$PSScriptRoot\dist\app-image\$AppName"
    $launcher = "$PSScriptRoot\launcher\db-mcp-helper.cmd"
    if (Test-Path $launcher) {
        Copy-Item $launcher "$appImage\db-mcp-helper.cmd" -Force
        Write-Host "[OK] Launcher staged into app-image"
    }
}

function Invoke-InnoWrap {
    param([string]$InnoDir)
    Write-Host "[INFO] Inno Setup wrap (ISCC) ..."
    $iss = "$PSScriptRoot\installer\db-mcp.iss"
    if (-not (Test-Path $iss)) { throw "Inno script not found: $iss" }
    Remove-Item -Recurse -Force "$PSScriptRoot\dist\pkg" -ErrorAction SilentlyContinue
    New-Item -ItemType Directory -Force -Path "$PSScriptRoot\dist\pkg" | Out-Null

    $appImage = "$PSScriptRoot\dist\app-image\$AppName"
    if (-not (Test-Path $appImage)) { throw "app-image not found: $appImage" }

    & "$InnoDir\ISCC.exe" "/DAppVersion=$AppVersion" "/DAppName=`"$AppName`"" "/DAppDir=`"$appImage`"" "/O$PSScriptRoot\dist\pkg" "$iss"
    if ($LASTEXITCODE -ne 0) { throw "ISCC failed" }

    Get-ChildItem "$PSScriptRoot\dist\pkg" -Recurse | ForEach-Object {
        Write-Host "[OUTPUT] $($_.FullName)"
    }
}

function Invoke-JpackageMsi {
    Write-Host "[INFO] jpackage --type msi ..."
    Remove-Item -Recurse -Force "$PSScriptRoot\dist\app-staging", "$PSScriptRoot\dist\pkg" -ErrorAction SilentlyContinue
    New-Item -ItemType Directory -Force -Path "$PSScriptRoot\dist\app-staging" | Out-Null
    Copy-Item "$PSScriptRoot\setup-app\target\db-mcp-setup.jar" "$PSScriptRoot\dist\app-staging\"

    $args = @(
        "--type", "msi",
        "--name", $AppName,
        "--app-version", $AppVersion,
        "--vendor", "dbmcp",
        "--input", "$PSScriptRoot\dist\app-staging",
        "--main-jar", "db-mcp-setup.jar",
        "--main-class", "com.dbmcp.setup.SetupMain",
        "--runtime-image", "$PSScriptRoot\dist\runtime",
        "--dest", "$PSScriptRoot\dist\pkg",
        "--icon", "$PSScriptRoot\design\icon.ico",
        "--win-menu",
        "--win-shortcut",
        "--win-menu-group", $AppName,
        "--win-dir-chooser",
        "--win-upgrade-uuid", $UpgradeUuid
    )
    & jpackage @args
    if ($LASTEXITCODE -ne 0) { throw "jpackage msi failed" }

    Get-ChildItem "$PSScriptRoot\dist\pkg" -Recurse | ForEach-Object {
        Write-Host "[OUTPUT] $($_.FullName)"
    }
}

function Invoke-TauriShell {
    Write-Host "[INFO] Tauri desktop shell build ..."
    # npm (managed Node) drives the Tauri CLI; cargo (Rust) must be on PATH.
    if (-not (Get-Command npm -ErrorAction SilentlyContinue)) {
        throw "npm not found; install Node.js (https://nodejs.org) to build the Tauri shell"
    }
    if (-not (Get-Command cargo -ErrorAction SilentlyContinue)) {
        throw "cargo (Rust) not found; install Rust (https://www.rust-lang.org) to build the Tauri shell"
    }

    # Assemble the bundle dir Tauri ships as resources: the fat jar (already
    # contains toolkit/tap/skill on the classpath) plus the optional JRE fallback.
    $bundle = "$PSScriptRoot\shell\bundle"
    New-Item -ItemType Directory -Force -Path $bundle | Out-Null

    $jar = "$PSScriptRoot\setup-app\target\db-mcp-setup.jar"
    if (-not (Test-Path $jar)) { throw "db-mcp-setup.jar not found; run setup-app build first" }
    Copy-Item $jar "$bundle\db-mcp-setup.jar" -Force

    $rt = "$PSScriptRoot\dist\runtime"
    if (Test-Path $rt) {
        Copy-Item $rt "$bundle\runtime" -Recurse -Force
        Write-Host "[OK] Bundled JRE staged into shell\bundle\runtime (fallback when no system JDK 17)"
    } else {
        Write-Host "[WARN] dist\runtime not found; Tauri build will rely on a system JDK 17 only"
    }

    Push-Location "$PSScriptRoot\shell"
    try {
        & npm install
        if ($LASTEXITCODE -ne 0) { throw "npm install failed" }
        & npm run tauri build
        if ($LASTEXITCODE -ne 0) { throw "tauri build failed" }
    } finally {
        Pop-Location
    }

    Write-Host "[DONE] Tauri installer under shell\src-tauri\target\release\bundle\"
}

Set-Location $PSScriptRoot
Assert-Command -Names @("mvn")
Test-Java17

$toolkit = Resolve-Toolkit
$MysqlToolkit = Resolve-MysqlToolkit
$NodeRuntimeZip = Resolve-NodeRuntime

Invoke-McpTapBuild
Invoke-JlinkRuntime -ToolkitJar $toolkit
Invoke-StageResources -ToolkitJar $toolkit
Invoke-SetupAppBuild

if ($Shell -eq "tauri") {
    # Real desktop app: Tauri spawns the Java backend and shows it in a WebView
    # window (no system browser). The jlink runtime above is shipped as the JRE
    # fallback; the installer itself is produced by `tauri build`, not jpackage.
    Invoke-TauriShell
    return
}

if ($Type -eq "exe") {
    # app-image (no WiX) → Inno Setup wraps it into a real installer
    # with a directory chooser + uninstaller + Start Menu uninstall entry.
    $inno = Get-InnoSetupPath
    $env:PATH = "$inno;$env:PATH"
    Invoke-AppImage
    Invoke-InnoWrap -InnoDir $inno
} elseif ($Type -eq "msi") {
    # Pure jpackage MSI (WiX required); --win-dir-chooser works on MSI.
    $wix = Get-WixPath
    $env:PATH = "$wix;$env:PATH"
    Invoke-JpackageMsi
} else {
    # app-image only — portable directory, no installer.
    Invoke-AppImage
    Write-Host "[DONE] Portable app image under dist\app-image\$AppName"
    return
}

Write-Host "[DONE] Installer generated under dist/pkg/"
