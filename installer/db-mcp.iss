; DB MCP Helper — Inno Setup 安装脚本
; 由 package-windows.ps1 通过 /D 宏注入：
;   AppVersion  — 应用版本（如 1.0.0）
;   AppDir      — jpackage --type app-image 产出的应用目录
;   AppName     — 应用显示名（默认 DB MCP Helper）
;
; 设计要点：
;   * DefaultDirName={autopf}\DB MCP Helper  → 允许最终用户选择安装目录
;   * 内置正规卸载器（unins000.exe）+ 控制面板/开始菜单卸载入口
;   * 卸载时通过 install-info.json 中记录的 UninstallString 由向导自清理，
;     并调用 [Code] 删除用户数据目录与 mcp.json 中的 DB MCP 连接器条目
;
; 注意：AppId 必须长期稳定，否则升级会生成重复卸载项。

#define MyAppName "DB MCP Helper"
#define MyAppVersion "1.0.0"
#ifndef AppVersion
  #define AppVersion MyAppVersion
#endif
#ifndef AppName
  #define AppName MyAppName
#endif

[Setup]
; 安装包身份（升级依据，禁止随意改动）
AppId={{7B1C4E3A-2F5D-4A8B-9C6E-1D3F5A7B9C2D}
AppName={#AppName}
AppVersion={#AppVersion}
AppPublisher=dbmcp
AppCopyright=Copyright (C) dbmcp
; 默认安装目录，用户可在向导中修改
DefaultDirName={autopf}\{#AppName}
; 不允许装到 Program Files 之外的同盘旧路径警告
DirExistsWarning=auto
; 固定为当前用户安装（per-user），安装到 Local\Programs，保证向导对安装目录有写权限
; （运行时释放到安装目录内，若装到 Program Files 会因 UAC 无写权限而失败）
PrivilegesRequired=lowest
; 控制面板/开始菜单卸载入口
UninstallDisplayName={#AppName}
Uninstallable=yes
CreateUninstallRegKey=yes
; 64 位机器装到 Program Files，而非 x86
ArchitecturesInstallIn64BitMode=x64os
; 安装信息落地，供卸载时清理用户数据
; （实际路径由 [Code] 在安装末尾写入 AppDir 同级 install-info.json）
OutputDir=dist\pkg
OutputBaseFilename={#AppName}-{#AppVersion}
SetupIconFile=..\design\icon.ico
UninstallDisplayIcon={app}\{#AppName}.exe
Compression=lzma2
SolidCompression=yes
WizardStyle=modern
DisableDirPage=no
DisableProgramGroupPage=no
; 中文（简体）语言选择
LanguageDetectionMethod=uilanguage
ShowLanguageDialog=auto

[Languages]
Name: "chinesesimplified"; MessagesFile: "compiler:Default.isl"

[Files]
; 整个 app-image 目录打进 {app}
Source: "{#AppDir}\*"; DestDir: "{app}"; Flags: ignoreversion recursesubdirs createallsubdirs

[Icons]
Name: "{autoprograms}\{#AppName}\{#AppName}"; Filename: "{app}\db-mcp-helper.cmd"; WorkingDir: "{app}"
Name: "{autoprograms}\{#AppName}\卸载 {#AppName}"; Filename: "{uninstallexe}"
Name: "{autodesktop}\{#AppName}"; Filename: "{app}\db-mcp-helper.cmd"; Tasks: desktopicon

[Tasks]
Name: "desktopicon"; Description: "{cm:CreateDesktopIcon}"; GroupDescription: "{cm:AdditionalIcons}"; Flags: unchecked

[Run]
Filename: "{app}\db-mcp-helper.cmd"; Description: "{cm:LaunchProgram,{#AppName}}"; Flags: nowait postinstall skipifsilent

[Code]
// ── 安装前自动关闭运行中的进程 ──────────────────────────────────────
// PrepareToInstall 在文件复制前调用，返回非空字符串则中止安装。
function PrepareToInstall(var NeedsRestart: Boolean): String;
var
  ResultCode: Integer;
  AppExe, CmdLine: String;
begin
  Result := '';
  AppExe := 'db-mcp-helper.cmd';

  // 1) 强制关闭主进程及其子进程树（/T = process tree）
  CmdLine := ExpandConstant('taskkill /F /IM "' + AppExe + '" /T');
  Exec('cmd.exe', '/C ' + CmdLine, '', SW_HIDE, ewWaitUntilTerminated, ResultCode);

  // 2) 关闭从安装目录启动的 javaw.exe（MCP 服务子进程）
  //    用 WMIC 按 CommandLine 过滤，避免误杀其他 Java 程序
  CmdLine := 'wmic process where "name=''javaw.exe'' and CommandLine like ''%{#AppName}%''" call terminate >nul 2>&1';
  Exec('cmd.exe', '/C ' + CmdLine, '', SW_HIDE, ewWaitUntilTerminated, ResultCode);

  // 3) 等待短暂时间让文件锁释放
  Sleep(500);
end;

// 安装信息落地：把卸载命令等信息写到 app 目录，供向导自卸载调用
procedure CurStepChanged(CurStep: TSetupStep);
var
  InfoPath, Json, UninstallStr: string;
begin
  if CurStep = ssPostInstall then
  begin
    InfoPath := ExpandConstant('{app}\install-info.json');
    UninstallStr := ExpandConstant('{uninstallexe}');
    Json := '{"appName":"' + '{#AppName}' + '",' + #13#10 +
            ' "installDir":"' + ExpandConstant('{app}') + '",' + #13#10 +
            ' "uninstallString":"' + UninstallStr + '"}';
    SaveStringToFile(InfoPath, Json, False);
  end;
end;

// 卸载时清理用户数据目录（MCP 部署目录）；mcp.json 中的 DB MCP 连接器条目由向导侧处理
function UninstallCleanup(): Boolean;
var
  DataDir, Home: string;
begin
  Result := True;
  Home := GetEnv('USERPROFILE');
  if Home = '' then
    Home := GetEnv('HOMEDRIVE') + GetEnv('HOMEPATH');
  DataDir := Home + '\.agent\mcp';
  if DirExists(DataDir) then
    DelTree(DataDir, True, True, True);
end;

procedure CurUninstallStepChanged(CurUninstallStep: TUninstallStep);
begin
  if CurUninstallStep = usPostUninstall then
    UninstallCleanup();
end;
