; Installeur Windows du compagnon (NSIS 3), compilé par scripts/package.ts sur le Mac ou sous Linux (CI) :
;   makensis -DSOURCE=<dossier préparé> -DOUTFILE=<exe> -DVERSION=X.Y.Z compagnon.nsi
; Par utilisateur, sans droits d'administrateur : %LOCALAPPDATA%\Programs\Compagnon Unowhy. Relancer une version plus
; récente = mise à jour (données gardées hors du dossier, voir src/paths.ts).
;   /S            silencieux (CI) : ni fenêtre, ni pare-feu, ni demande d'administrateur ; code de sortie non nul si échec
;   /D=<dossier>  autre dossier, en dernier, sans guillemets
; COMPANION_LABEL (instance de test, job install-windows) : nom de l'entrée de Programmes et fonctionnalités, comme
; celui de la clé Run (scripts/service.ts) : celles du vrai compagnon ne sont pas touchées.

Unicode true
ManifestDPIAware true
SetCompressor /SOLID lzma
RequestExecutionLevel user

!include MUI2.nsh
!include LogicLib.nsh

!define PRODUCT "Compagnon Unowhy"
!define UNINSTALLER "Desinstaller.exe"
!define UNINSTALL_KEY "Software\Microsoft\Windows\CurrentVersion\Uninstall"

Name "${PRODUCT}"
OutFile "${OUTFILE}"
InstallDir "$LOCALAPPDATA\Programs\${PRODUCT}"
ShowInstDetails hide
ShowUninstDetails hide
BrandingText "Unowhy"

VIProductVersion "${VERSION}.0"
VIAddVersionKey /LANG=1036 "ProductName" "${PRODUCT}"
VIAddVersionKey /LANG=1036 "ProductVersion" "${VERSION}"
VIAddVersionKey /LANG=1036 "FileVersion" "${VERSION}"
VIAddVersionKey /LANG=1036 "FileDescription" "Installation du ${PRODUCT}"
VIAddVersionKey /LANG=1036 "CompanyName" "Unowhy"
VIAddVersionKey /LANG=1036 "LegalCopyright" "Unowhy"

!define MUI_ICON "${SOURCE}/backend/assets/compagnon.ico"
!define MUI_UNICON "${SOURCE}/backend/assets/compagnon.ico"
!define MUI_INSTFILESPAGE_FINISHHEADER_TEXT "Compagnon installé"
!define MUI_INSTFILESPAGE_FINISHHEADER_SUBTEXT "Sur la tablette : Réglages > Ordinateurs > Ajouter, puis le code affiché dans le navigateur."
!define MUI_INSTFILESPAGE_ABORTHEADER_TEXT "Installation interrompue"
!define MUI_INSTFILESPAGE_ABORTHEADER_SUBTEXT "Le compagnon n'a pas démarré : voir les détails."
!insertmacro MUI_PAGE_INSTFILES
!insertmacro MUI_UNPAGE_CONFIRM
!insertmacro MUI_UNPAGE_INSTFILES
!insertmacro MUI_LANGUAGE "French"

Var key

!macro ReadKey
  ReadEnvStr $key COMPANION_LABEL
  ${If} $key == ""
    StrCpy $key "UnowhyCompanion"
  ${EndIf}
!macroend

Function .onInit
  !insertmacro ReadKey
FunctionEnd

Function un.onInit
  !insertmacro ReadKey
FunctionEnd

Section
  SetDetailsPrint both
  ; Mise à jour : le compagnon de ce dossier verrouille node.exe et le lanceur, on l'arrête avant de copier.
  ${If} ${FileExists} "$INSTDIR\backend\dist\service.mjs"
    DetailPrint "Arrêt du compagnon en place..."
    SetOutPath "$INSTDIR\backend"
    nsExec::Exec '"$INSTDIR\backend\node\node.exe" dist\service.mjs stop'
    Pop $0
    Sleep 1000
  ${EndIf}

  SetOutPath "$INSTDIR"
  File /r "${SOURCE}/*"
  WriteUninstaller "$INSTDIR\${UNINSTALLER}"

  WriteRegStr HKCU "${UNINSTALL_KEY}\$key" "DisplayName" "${PRODUCT}"
  WriteRegStr HKCU "${UNINSTALL_KEY}\$key" "DisplayVersion" "${VERSION}"
  WriteRegStr HKCU "${UNINSTALL_KEY}\$key" "Publisher" "Unowhy"
  WriteRegStr HKCU "${UNINSTALL_KEY}\$key" "DisplayIcon" "$INSTDIR\backend\assets\compagnon.ico"
  WriteRegStr HKCU "${UNINSTALL_KEY}\$key" "InstallLocation" "$INSTDIR"
  WriteRegStr HKCU "${UNINSTALL_KEY}\$key" "UninstallString" '"$INSTDIR\${UNINSTALLER}"'
  WriteRegStr HKCU "${UNINSTALL_KEY}\$key" "QuietUninstallString" '"$INSTDIR\${UNINSTALLER}" /S'
  WriteRegDWORD HKCU "${UNINSTALL_KEY}\$key" "NoModify" 1
  WriteRegDWORD HKCU "${UNINSTALL_KEY}\$key" "NoRepair" 1

  ; Pare-feu : seule étape qui demande l'accord d'un administrateur, et seulement si la règle manque.
  ${IfNot} ${Silent}
    DetailPrint "Autorisation dans le pare-feu de Windows (accepte la demande d'administrateur)..."
    nsExec::Exec 'powershell.exe -NoProfile -ExecutionPolicy Bypass -File "$INSTDIR\backend\scripts\firewall-windows.ps1"'
    Pop $0
    ${If} $0 != 0
      DetailPrint "Pare-feu non réglé : si la tablette ne trouve pas ce PC, relance l'installation et accepte la demande."
    ${EndIf}
  ${EndIf}

  DetailPrint "Démarrage du compagnon..."
  SetOutPath "$INSTDIR\backend"
  nsExec::ExecToLog '"$INSTDIR\backend\node\node.exe" --env-file-if-exists=.env dist\service.mjs install'
  Pop $0
  ${If} $0 != 0
    SetDetailsView show
    MessageBox MB_ICONSTOP "Le compagnon n'a pas démarré (voir les détails)." /SD IDOK
    SetErrorLevel 2
    Abort
  ${EndIf}
SectionEnd

Section "Uninstall"
  SetDetailsPrint both
  ${If} ${FileExists} "$INSTDIR\backend\dist\service.mjs"
    SetOutPath "$INSTDIR\backend"
    nsExec::ExecToLog '"$INSTDIR\backend\node\node.exe" dist\service.mjs uninstall'
    Pop $0
    Sleep 1000
  ${EndIf}
  DeleteRegKey HKCU "${UNINSTALL_KEY}\$key"
  ; Seulement ce que l'installeur a posé : $INSTDIR peut être un dossier choisi par /D. Les données (identité,
  ; agenda, tâches) restent dans %LOCALAPPDATA%\UnowhyCompanion : une réinstallation retrouve la tablette.
  SetOutPath "$TEMP"
  RMDir /r "$INSTDIR\backend"
  RMDir /r "$INSTDIR\Extension navigateur"
  Delete "$INSTDIR\${UNINSTALLER}"
  RMDir "$INSTDIR"
SectionEnd
