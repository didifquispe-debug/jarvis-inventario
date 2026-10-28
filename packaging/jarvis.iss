; Proyecto DYSA - JARVIS Inventario
; Autores: D: Didier · Y: Yoel · S: Sumi · A: Apaza
;
; Instalador de JARVIS con Inno Setup 6. Lo compila crear-exe.bat después de armar dist\JARVIS.
; La voz (Vosk y Piper) va siempre; la IA avanzada (Ollama + modelo) es un componente opcional
; y solo aparece si packaging\preparar-componentes.bat la dejó en dist\JARVIS\ia.

#define AppVersion "0.1.0"
#define Origen "..\dist\JARVIS"
#define ConIA DirExists(AddBackslash(SourcePath) + Origen + "\ia\ollama")

[Setup]
AppId={{E91D0334-28A5-4326-8CF4-49C77B7FA1B3}
AppName=JARVIS Inventario
AppVersion={#AppVersion}
AppPublisher=DYSA (Didier, Yoel, Sumi, Apaza)
DefaultDirName={localappdata}\Programs\JARVIS
DefaultGroupName=JARVIS
DisableProgramGroupPage=yes
PrivilegesRequired=lowest
ArchitecturesAllowed=x64compatible
ArchitecturesInstallIn64BitMode=x64compatible
OutputDir=..\dist\instalador
OutputBaseFilename=JARVIS-Setup-{#AppVersion}
SetupIconFile=jarvis.ico
UninstallDisplayIcon={app}\JARVIS.exe
UninstallDisplayName=JARVIS Inventario
WizardStyle=modern
Compression=lzma2
SolidCompression=no
CloseApplications=yes
#if ConIA
; Con el modelo de IA el instalador pasa de 2 GB: va en JARVIS-Setup.exe + archivos .bin.
DiskSpanning=yes
DiskSliceSize=max
#endif

[Languages]
Name: "es"; MessagesFile: "compiler:Languages\Spanish.isl"

#if ConIA
[Types]
Name: "completa"; Description: "Completa (con IA avanzada)"
Name: "basica"; Description: "Básica (sin IA avanzada, ocupa menos)"
Name: "personalizada"; Description: "Personalizada"; Flags: iscustom
#endif

[Components]
Name: "principal"; Description: "JARVIS con voz sin Internet (Vosk y Piper)"; Flags: fixed; Types: {#ConIA ? "completa basica personalizada" : "full compact custom"}
#if ConIA
Name: "ia"; Description: "IA avanzada: Ollama + Llama 3.2 (unos 2 GB, recomendado con 8 GB de RAM)"; Types: completa
#endif

[Tasks]
Name: "escritorio"; Description: "Crear acceso directo en el escritorio"

[Files]
Source: "{#Origen}\*"; DestDir: "{app}"; Excludes: "\ia"; Components: principal; Flags: ignoreversion recursesubdirs createallsubdirs
#if ConIA
Source: "{#Origen}\ia\*"; DestDir: "{app}\ia"; Components: ia; Flags: ignoreversion recursesubdirs createallsubdirs
#endif

[Icons]
Name: "{group}\JARVIS"; Filename: "{app}\JARVIS.exe"
Name: "{userdesktop}\JARVIS"; Filename: "{app}\JARVIS.exe"; Tasks: escritorio

[Run]
Filename: "{app}\JARVIS.exe"; Description: "Abrir JARVIS"; Flags: nowait postinstall skipifsilent
