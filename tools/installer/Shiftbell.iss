; Shiftbell installer. Built by tools\build-release.ps1, which passes the version.
;
; Per-user: PrivilegesRequired=lowest, everything under %LOCALAPPDATA% and HKCU, the same
; place tools\install.ps1 uses, so either one upgrades the other. The app registers its own
; Run key and scheduled task on first start, so the installer does not.

#define AppName    "Shiftbell"
#ifndef AppVersion
  #define AppVersion "0.0.0-local"
#endif
#define AppPublisher "JKC"
#define AppExe     "Shiftbell.exe"

[Setup]
; Stable id: upgrades replace the previous install, and the uninstaller is found by it.
AppId={{6C1F0B8E-3A52-4D7C-9E41-2B8D5A7F0C13}
AppName={#AppName}
AppVersion={#AppVersion}
AppVerName={#AppName} {#AppVersion}
AppPublisher={#AppPublisher}
AppPublisherURL=https://github.com/iamprincejkc/shiftbell
VersionInfoVersion={#AppVersion}

DefaultDirName={localappdata}\Programs\{#AppName}
DisableDirPage=yes
DisableProgramGroupPage=yes
PrivilegesRequired=lowest
ArchitecturesAllowed=x64compatible
ArchitecturesInstallIn64BitMode=x64compatible

OutputDir=..\..\dist
OutputBaseFilename={#AppName}-Setup-{#AppVersion}
SetupIconFile=..\..\src\Shiftbell\Assets\shiftbell.ico
UninstallDisplayIcon={app}\{#AppExe}
UninstallDisplayName={#AppName}
LicenseFile=..\..\LICENSE

Compression=lzma2/max
SolidCompression=yes
WizardStyle=modern

; Deliberately no AppMutex, although the app holds Local\Shiftbell-<user>. Setup checks it
; at startup and asks to close the app with OK/Cancel, and under /SILENT /SUPPRESSMSGBOXES
; that is answered Cancel, so every silent upgrade of a running tray app would fail.
; The [Code] below stops the app itself and starts it again afterwards.

[Languages]
Name: "english"; MessagesFile: "compiler:Default.isl"

[Files]
Source: "..\..\dist\{#AppExe}"; DestDir: "{app}"; Flags: ignoreversion

[Icons]
; Same place tools\install.ps1 puts its shortcut, so an upgrade from it replaces rather than duplicates.
Name: "{autoprograms}\{#AppName}"; Filename: "{app}\{#AppExe}"; Comment: "Time-in and time-out alarms that cannot be missed"

[Run]
; Starting without arguments opens Settings, which is the right first screen after an install.
Filename: "{app}\{#AppExe}"; Description: "Start {#AppName} now"; Flags: postinstall nowait skipifsilent

[UninstallRun]
; Removes the Run key and the scheduled task while the exe that knows how is still there.
Filename: "{app}\{#AppExe}"; Parameters: "--unregister"; Flags: runhidden waituntilterminated; RunOnceId: "Unregister"

[Code]
var
  WasRunning: Boolean;

function Running(): Boolean;
begin
  Result := CheckForMutexes('Local\Shiftbell-' + GetUserNameString());
end;

// Asks the running copy to exit (it releases its mutex), waits up to 10 s, then forces it.
// Returns whether it was running.
function StopShiftbell(): Boolean;
var
  ResultCode, Waited: Integer;
begin
  Result := Running();
  if not Result then
    Exit;

  if FileExists(ExpandConstant('{app}\{#AppExe}')) then
    Exec(ExpandConstant('{app}\{#AppExe}'), '--exit', '', SW_HIDE, ewWaitUntilTerminated, ResultCode);

  Waited := 0;
  while Running() and (Waited < 10000) do
  begin
    Sleep(250);
    Waited := Waited + 250;
  end;

  if Running() then
    Exec(ExpandConstant('{sys}\taskkill.exe'), '/IM {#AppExe} /F', '', SW_HIDE, ewWaitUntilTerminated, ResultCode);
end;

function PrepareToInstall(var NeedsRestart: Boolean): String;
begin
  WasRunning := StopShiftbell();
  Result := '';
end;

// A silent upgrade skips the "Start now" box, so put the app back if it was running.
// --background starts it quietly, without opening Settings.
procedure CurStepChanged(CurStep: TSetupStep);
var
  ResultCode: Integer;
begin
  if (CurStep = ssPostInstall) and WasRunning and WizardSilent then
    Exec(ExpandConstant('{app}\{#AppExe}'), '--background', ExpandConstant('{app}'), SW_SHOWNORMAL, ewNoWait, ResultCode);
end;

function InitializeUninstall(): Boolean;
begin
  StopShiftbell();
  Result := True;
end;

procedure CurUninstallStepChanged(CurUninstallStep: TUninstallStep);
var
  DataDir: String;
begin
  if CurUninstallStep = usPostUninstall then
  begin
    // Settings and punch history are kept on purpose. SuppressibleMsgBox, never MsgBox:
    // a plain one ignores /SUPPRESSMSGBOXES and blocks a scripted uninstall forever.
    DataDir := ExpandConstant('{userappdata}\Shiftbell');
    if DirExists(DataDir) then
      SuppressibleMsgBox('Your Shiftbell settings were kept at:' + #13#10 + DataDir + #13#10#13#10 +
        'Delete that folder if you want them gone.', mbInformation, MB_OK, IDOK);
  end;
end;
