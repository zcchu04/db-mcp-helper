#Requires -Version 5.1
<#
.SYNOPSIS
  Build Oracle MCP Helper Windows installer (.exe / .msi / app-image).
.DESCRIPTION
  1. Verify JDK 17
  2. Verify Inno Setup (for .exe) or WiX Toolset (for .msi)
  3. Build mcp-tap and locate oracle-db-mcp-toolkit
  4. jlink MCP runtime and installer runtime
  5. Stage embedded resources and build setup-app fat jar
  6. Package with jpackage
  Default output is .exe (supports custom install directory); use -Type to switch.
.EXAMPLE
  .\package-windows.ps1
  .\package-windows.ps1 -Type msi
  .\package-windows.ps1 -Type app-image
#>
param(
    [ValidateSet("exe", "msi", "app-image")]
    [string]$Type = "exe"
)

$ErrorActionPreference = "Stop"
$ProgressPreference = "SilentlyContinue"

$UpgradeUuid = "6a8b5c2d-9e4f-4a1b-8c7d-3e5f6a9b0c1d"

function Test-Java17 {
    $ver = (cmd /c "java -version 2>&1") | Select-String -Pattern 'version "(.+)"' | ForEach-Object { $_.Matches.Groups[1].Value }
    if (-not $ver) {
        throw "Unable to detect Java version; ensure java is on PATH"
    }
    if ($ver -notmatch "^17\." -and $ver -notmatch "^1\.17") {
        throw "JDK 17 required, found: $ver"
    }
    Write-Host "[OK] Java $ver"
}

function Get-InnoSetupPath {
    $candidates = @(
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
    throw "Inno Setup not found; download from https://jrsoftware.org/isdl.php and install"
}

function Get-WixPath {
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
        throw "WiX download appears incomplete"
    }
    Expand-Archive -Path $zip -DestinationPath $local -Force
    if (-not (Test-Path "$local\candle.exe")) {
        throw "candle.exe not found after extracting WiX"
    }
    Write-Host "[OK] WiX deployed to: $local"
    return $local
}

function Resolve-Toolkit {
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
    throw "oracle-db-mcp-toolkit-1.0.0.jar not found. Build oracle/mcp or place jar at $cached"
}

function Invoke-McpTapBuild {
    Write-Host "[INFO] Building mcp-tap ..."
    & mvn package -DskipTests -q -pl mcp-tap
    if ($LASTEXITCODE -ne 0) { throw "mcp-tap build failed" }
}

function Invoke-JlinkMcpRuntime {
    param([string]$ToolkitJar)
    Write-Host "[INFO] jlink MCP runtime ..."
    $mods = (cmd /c "jdeps --ignore-missing-deps --print-module-deps `"$ToolkitJar`" 2>&1") | Where-Object { $_ -notmatch "^WARNING:" }
    if ($LASTEXITCODE -ne 0) { throw "jdeps failed" }
    $full = @(
        "java.base", "java.logging", "java.xml", "java.desktop",
        "java.instrument", "java.management", "java.naming", "java.net.http",
        "java.rmi", "java.sql", "jdk.net", "jdk.security.jgss"
    )
    $all = (($full + ($mods -split ",")) | Sort-Object -Unique | Where-Object { $_.Trim() } | ForEach-Object { $_.Trim() }) -join ","
    Remove-Item -Recurse -Force "$PSScriptRoot\dist\mcp-runtime" -ErrorAction SilentlyContinue
    & jlink --add-modules $all --output "$PSScriptRoot\dist\mcp-runtime" `
        --strip-debug --no-header-files --no-man-pages --compress 2
    if ($LASTEXITCODE -ne 0) { throw "jlink MCP runtime failed" }
    $zip = "$PSScriptRoot\dist\mcp-runtime.zip"
    $retries = 0
    while ($retries -lt 5) {
        try {
            Compress-Archive -Path "$PSScriptRoot\dist\mcp-runtime" -DestinationPath $zip -Force -ErrorAction Stop
            break
        } catch {
            $retries++
            if ($retries -eq 5) { throw }
            Write-Host "[WARN] zip locked, retrying in 2s ..."
            Start-Sleep -Seconds 2
        }
    }
}

function Invoke-JlinkAppRuntime {
    Write-Host "[INFO] jlink installer runtime ..."
    Remove-Item -Recurse -Force "$PSScriptRoot\dist\app-runtime" -ErrorAction SilentlyContinue
    & jlink --add-modules java.base,java.desktop,java.logging,jdk.httpserver `
        --output "$PSScriptRoot\dist\app-runtime" --strip-debug --no-header-files --no-man-pages --compress 2
    if ($LASTEXITCODE -ne 0) { throw "jlink installer runtime failed" }
}

function Get-GitBash {
    $candidates = @(
        "C:\Program Files\Git\bin\bash.exe",
        "C:\Program Files (x86)\Git\bin\bash.exe",
        "C:\Develop Program Files\Git\bin\bash.exe",
        "C:\Develop Program Files\Git\usr\bin\bash.exe"
    )
    foreach ($c in $candidates) {
        if (Test-Path $c) { return $c }
    }
    $found = Get-Command bash -ErrorAction SilentlyContinue | Where-Object { $_.Source -notmatch "WindowsApps|System32" } | Select-Object -First 1
    if ($found) { return $found.Source }
    throw "git-bash not found; install Git for Windows"
}

function Invoke-StageResources {
    param([string]$ToolkitJar)
    Write-Host "[INFO] Staging resources ..."
    $env:TOOLKIT_SRC = $ToolkitJar
    $bash = Get-GitBash
    & $bash "$PSScriptRoot\stage-resources.sh"
    if ($LASTEXITCODE -ne 0) { throw "stage-resources.sh failed" }
}

function Invoke-SetupAppBuild {
    Write-Host "[INFO] Building setup-app fat jar ..."
    & mvn package -DskipTests -q -pl setup-app
    if ($LASTEXITCODE -ne 0) { throw "setup-app build failed" }
}

function Invoke-Jpackage {
    param([string]$Type)
    Write-Host "[INFO] jpackage (type: $Type) ..."
    Remove-Item -Recurse -Force "$PSScriptRoot\dist\app-staging", "$PSScriptRoot\dist\pkg" -ErrorAction SilentlyContinue
    New-Item -ItemType Directory -Force -Path "$PSScriptRoot\dist\app-staging" | Out-Null
    Copy-Item "$PSScriptRoot\setup-app\target\oracle-mcp-setup.jar" "$PSScriptRoot\dist\app-staging\"

    $args = @(
        "--type", $Type,
        "--name", "Oracle MCP Helper",
        "--app-version", "1.0.0",
        "--vendor", "oraclemcp",
        "--input", "$PSScriptRoot\dist\app-staging",
        "--main-jar", "oracle-mcp-setup.jar",
        "--main-class", "com.oraclemcp.setup.SetupMain",
        "--runtime-image", "$PSScriptRoot\dist\app-runtime",
        "--dest", "$PSScriptRoot\dist\pkg",
        "--icon", "$PSScriptRoot\design\icon.ico",
        "--win-menu",
        "--win-shortcut",
        "--win-menu-group", "Oracle MCP Helper"
    )
    if ($Type -eq "msi") {
        $args += @("--win-upgrade-uuid", $UpgradeUuid)
    }
    & jpackage @args
    if ($LASTEXITCODE -ne 0) { throw "jpackage $Type failed" }

    Get-ChildItem "$PSScriptRoot\dist\pkg" -Recurse | ForEach-Object {
        Write-Host "[OUTPUT] $($_.FullName)"
    }
}

Set-Location $PSScriptRoot
Test-Java17

if ($Type -eq "exe") {
    $inno = Get-InnoSetupPath
    $env:PATH = "$inno;$env:PATH"
    # jpackage exe also requires WiX
    $wix = Get-WixPath
    $env:PATH = "$wix;$env:PATH"
} elseif ($Type -eq "msi") {
    $wix = Get-WixPath
    $env:PATH = "$wix;$env:PATH"
}

$toolkit = Resolve-Toolkit

Invoke-McpTapBuild
Invoke-JlinkMcpRuntime -ToolkitJar $toolkit
Invoke-JlinkAppRuntime
Invoke-StageResources -ToolkitJar $toolkit
Invoke-SetupAppBuild
Invoke-Jpackage -Type $Type

Write-Host "[DONE] Installer generated under dist/pkg/"
