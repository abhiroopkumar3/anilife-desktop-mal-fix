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
AppVerName={#AppName}
AppPublisher={#AppName}
AppPublisherURL={#RepoUrl}
AppSupportURL={#RepoUrl}
AppUpdatesURL={#RepoUrl}/releases
DefaultDirName={autopf}\{#AppName}
DefaultGroupName={#AppName}
; The wizard pages: install folder, Start Menu folder, and (from [Tasks]) the desktop shortcut.
DisableDirPage=no
DisableProgramGroupPage=no
AllowNoIcons=yes
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
Filename: "{#RepoUrl}"; Description: "Open the {#AppName} page on GitHub"; Flags: shellexec postinstall skipifsilent

[Code]
// Earlier versions were installed with an .msi. Two copies of AniLife side by side would fight over
// the same folder and shortcuts, so take the .msi one off first. Settings and the .env file are kept.
procedure RemoveOldMsiInstall();
var
  Names: TArrayOfString;
  I, ResultCode: Integer;
  WindowsInstaller: Cardinal;
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

// ---- Uninstall: offer to remove the settings and data folder ----

var
  RemoveUserData: Boolean;
  RemoveComponents: Boolean;

// Asked before anything is removed. Ticked by default, so a full uninstall leaves nothing behind;
// untick it to keep the settings, account sign-ins and downloads list for a later install. The second
// box does the same for the Java runtime, mpv, ffmpeg and upscaler that the first run downloaded. A silent
// uninstall never deletes either.
function InitializeUninstall(): Boolean;
var
  Form: TForm;
  Prompt: TLabel;
  Box, ComponentsBox: TCheckBox;
  Ok, Cancel: TButton;
begin
  Result := True;
  RemoveUserData := False;
  RemoveComponents := False;
  if UninstallSilent then
    Exit;
  Form := TForm.Create(nil);
  try
    Form.BorderStyle := bsDialog;
    Form.Position := poScreenCenter;
    Form.Caption := 'Uninstall {#AppName}';
    Form.ClientWidth := ScaleX(420);
    Form.ClientHeight := ScaleY(200);

    Prompt := TLabel.Create(Form);
    Prompt.Parent := Form;
    Prompt.AutoSize := False;
    Prompt.WordWrap := True;
    Prompt.SetBounds(ScaleX(16), ScaleY(16), ScaleX(388), ScaleY(48));
    Prompt.Caption := 'This removes {#AppName} from this PC.';

    Box := TCheckBox.Create(Form);
    Box.Parent := Form;
    Box.SetBounds(ScaleX(16), ScaleY(60), ScaleX(388), ScaleY(36));
    Box.Caption := 'Also delete all settings and data (%LOCALAPPDATA%\AniLife)';
    Box.Checked := True;

    ComponentsBox := TCheckBox.Create(Form);
    ComponentsBox.Parent := Form;
    ComponentsBox.SetBounds(ScaleX(16), ScaleY(100), ScaleX(388), ScaleY(36));
    ComponentsBox.Caption := 'Also delete the downloaded Java, mpv, ffmpeg and upscaler (%APPDATA%\bin)';
    ComponentsBox.Checked := True;

    Ok := TButton.Create(Form);
    Ok.Parent := Form;
    Ok.Caption := 'Uninstall';
    Ok.ModalResult := mrOk;
    Ok.Default := True;
    Ok.SetBounds(ScaleX(236), ScaleY(160), ScaleX(80), ScaleY(26));

    Cancel := TButton.Create(Form);
    Cancel.Parent := Form;
    Cancel.Caption := 'Cancel';
    Cancel.ModalResult := mrCancel;
    Cancel.Cancel := True;
    Cancel.SetBounds(ScaleX(324), ScaleY(160), ScaleX(80), ScaleY(26));

    if Form.ShowModal() = mrOk then
    begin
      RemoveUserData := Box.Checked;
      RemoveComponents := ComponentsBox.Checked;
    end
    else
      Result := False;
  finally
    Form.Free();
  end;
end;

// Only what the app downloaded is removed, never the whole folder: "bin" is a common name and may hold
// other programs' files. The folder itself goes only if it is left empty.
procedure RemoveDownloadedComponents();
var
  Bin, Name: String;
  Names: array of String;
  I: Integer;
begin
  Bin := ExpandConstant('{userappdata}\bin');
  SetArrayLength(Names, 4);
  Names[0] := 'jre';
  Names[1] := 'mpv';
  Names[2] := 'ffmpeg';
  Names[3] := 'upscaler';
  for I := 0 to 3 do
  begin
    Name := Names[I];
    DelTree(Bin + '\' + Name, True, True, True);
    DeleteFile(Bin + '\' + Name + '.installed');
  end;
  DeleteFile(Bin + '\use-jre');
  RemoveDir(Bin);
end;

procedure CurUninstallStepChanged(CurUninstallStep: TUninstallStep);
begin
  if CurUninstallStep <> usPostUninstall then
    Exit;
  if RemoveUserData then
    DelTree(ExpandConstant('{localappdata}\AniLife'), True, True, True);
  if RemoveComponents then
    RemoveDownloadedComponents();
end;
