; Inno Setup script for Origin Launcher.
;
; Produces OriginLauncher-Setup.exe — a per-user installer (no admin needed)
; that installs the self-contained launcher to %LocalAppData%\Programs\
; OriginLauncher, adds Start-Menu + optional Desktop shortcuts, and registers
; an uninstaller in Add/Remove Programs.
;
; A per-user install location is deliberate: the launcher self-updates in
; place (Core/Updates/UpdateService.cs swaps its own exe), which must work
; WITHOUT admin — so it can never live in Program Files. Fresh installs come
; from this setup.exe; existing installs keep updating themselves from the
; OriginLauncher-win-x64.zip asset the same release publishes.
;
; Version is passed by CI: ISCC /DMyAppVersion=1.0.<run>. Defaults for local
; builds.

#ifndef MyAppVersion
  #define MyAppVersion "1.0.0"
#endif

#define MyAppName "Origin Launcher"
#define MyAppExe "OriginLauncher.App.exe"
#define MyAppPublisher "Will Henry"

[Setup]
; A stable AppId ties upgrades + uninstall together across versions — never change it.
AppId={{7E9C1A54-2C3B-4E7A-9C1D-0A1B2C3D4E5F}
AppName={#MyAppName}
AppVersion={#MyAppVersion}
AppPublisher={#MyAppPublisher}
DefaultDirName={localappdata}\Programs\OriginLauncher
DefaultGroupName={#MyAppName}
DisableProgramGroupPage=yes
DisableDirPage=yes
PrivilegesRequired=lowest
OutputDir=Output
OutputBaseFilename=OriginLauncher-Setup
SetupIconFile=..\src\OriginLauncher.App\Assets\origin.ico
UninstallDisplayIcon={app}\{#MyAppExe}
Compression=lzma2
SolidCompression=yes
WizardStyle=modern
ArchitecturesAllowed=x64compatible
ArchitecturesInstallIn64BitMode=x64compatible

[Tasks]
Name: "desktopicon"; Description: "Create a &desktop shortcut"; GroupDescription: "Additional icons:"

[Files]
; The single-file, self-contained publish output (dotnet publish -o publish).
Source: "..\publish\{#MyAppExe}"; DestDir: "{app}"; Flags: ignoreversion
; The bundled Origin Client mod jar, kept LOOSE next to the exe (see the
; ExcludeFromSingleFile note in the csproj) so the launcher finds it at
; {app}\Bundled\OriginClient\originclient.jar via AppContext.BaseDirectory.
Source: "..\publish\Bundled\*"; DestDir: "{app}\Bundled"; Flags: ignoreversion recursesubdirs createallsubdirs

[Icons]
Name: "{group}\{#MyAppName}"; Filename: "{app}\{#MyAppExe}"
Name: "{group}\Uninstall {#MyAppName}"; Filename: "{uninstallexe}"
Name: "{autodesktop}\{#MyAppName}"; Filename: "{app}\{#MyAppExe}"; Tasks: desktopicon

[Run]
Filename: "{app}\{#MyAppExe}"; Description: "Launch {#MyAppName} now"; Flags: nowait postinstall skipifsilent
