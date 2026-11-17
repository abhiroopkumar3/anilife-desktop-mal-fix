; AniLife Windows installer wizard (Inno Setup 6).
;
; Packs the app image that Gradle's createDistributable makes, and walks through: the install folder
; (with Browse), the Start Menu folder, a desktop shortcut, then a last page that offers to launch
; AniLife and to open its GitHub page.
;
; Built by .github/workflows/build-windows.yml:
;   ISCC /DVersion=1.0.6-AK4 /DSourceDir=<app image> /O<out dir> /F<file name> anilife.iss

#ifndef Version
  #define Version "0.0.0"
#endif
#ifndef SourceDir
  #define SourceDir "..\..\app\build\compose\binaries\main\app\AniLife"
#endif

#define AppName "AniLife"
#define AppExe "AniLife.exe"
#define RepoUrl "https://github.com/abhiroopkumar3/anilife-desktop-mal-fix"

[Setup]
AppId={{C2AB5B89-6FF4-4928-9217-B0DB70EAA3BE}
AppName={#AppName}
AppVersion={#Version}
AppVerName={#AppName} {#Version}
AppPublisher={#AppName}
AppPublisherURL={#RepoUrl}
AppSupportURL={#RepoUrl}
AppUpdatesURL={#RepoUrl}/releases
DefaultDirName={autopf}\{#AppName}
DefaultGroupName={#AppName}
; The wizard pages: install folder, Start Menu folder, and (from [Tasks]) the desktop shortcut.
DisableDirPage=no
DisableProgramGroupPage=no
DisableWelcomePage=no
UninstallDisplayIcon={app}\{#AppExe}
SetupIconFile=..\..\app\src\main\resources\anilife.ico
WizardStyle=modern
Compression=lzma2/normal
SolidCompression=yes
ArchitecturesAllowed=x64compatible
ArchitecturesInstallIn64BitMode=x64compatible
PrivilegesRequired=admin
; The app closes itself before it starts an update, and anything still holding files is closed here.
CloseApplications=yes
RestartApplications=no

[Languages]
Name: "english"; MessagesFile: "compiler:Default.isl"

[Tasks]
Name: "desktopicon"; Description: "Create a &desktop shortcut"; GroupDescription: "Shortcuts:"

[InstallDelete]
; Start clean on an upgrade so files from an older build cannot linger. Settings, the .env file and
; everything else the app keeps lives outside the install folder or is not named here, so it stays.
Type: filesandordirs; Name: "{app}\app"
Type: filesandordirs; Name: "{app}\runtime"

[Files]
Source: "{#SourceDir}\*"; DestDir: "{app}"; Flags: recursesubdirs createallsubdirs ignoreversion

[Icons]
Name: "{group}\{#AppName}"; Filename: "{app}\{#AppExe}"; WorkingDir: "{app}"
Name: "{group}\Uninstall {#AppName}"; Filename: "{uninstallexe}"
Name: "{autodesktop}\{#AppName}"; Filename: "{app}\{#AppExe}"; WorkingDir: "{app}"; Tasks: desktopicon

[Run]
Filename: "{app}\{#AppExe}"; WorkingDir: "{app}"; Description: "Launch {#AppName}"; Flags: nowait postinstall skipifsilent
Filename: "{#RepoUrl}"; Description: "Open the {#AppName} page on GitHub"; Flags: shellexec postinstall skipifsilent unchecked

[Code]
// Earlier versions were installed with an .msi. Two copies of AniLife side by side would fight over
// the same folder and shortcuts, so take the .msi one off first. Settings and the .env file are kept.
procedure RemoveOldMsiInstall();
var
  Names: TArrayOfString;
  I, ResultCode, WindowsInstaller: Integer;
  Key, DisplayName: String;
begin
  if not RegGetSubkeyNames(HKLM64, 'SOFTWARE\Microsoft\Windows\CurrentVersion\Uninstall', Names) then
    Exit;
  for I := 0 to GetArrayLength(Names) - 1 do
  begin
    Key := 'SOFTWARE\Microsoft\Windows\CurrentVersion\Uninstall\' + Names[I];
    if RegQueryStringValue(HKLM64, Key, 'DisplayName', DisplayName) and (DisplayName = '{#AppName}') and
       RegQueryDWordValue(HKLM64, Key, 'WindowsInstaller', WindowsInstaller) and (WindowsInstaller = 1) then
      Exec('msiexec.exe', '/x ' + Names[I] + ' /qn /norestart', '', SW_HIDE, ewWaitUntilTerminated, ResultCode);
  end;
end;

procedure CurStepChanged(CurStep: TSetupStep);
begin
  if CurStep = ssInstall then
    RemoveOldMsiInstall();
end;
