@echo off
rem Double-clic : installe le compagnon sur ce PC Windows. Il demarrera ensuite tout seul
rem a chaque ouverture de session (voir backend\scripts\service.ts). Relancer ce fichier pour mettre a jour.
rem "Installer (Windows).cmd" /silencieux : sans pause, sans pare-feu ni demande d'administrateur (CI).
rem Le code de sortie dit si l'installation a reussi.
chcp 65001 >nul
set "SILENT="
if /i "%~1"=="/silencieux" set "SILENT=1"
set "BACKEND=%~dp0backend"
if not exist "%BACKEND%\src\win\UnowhyCompanion.exe" goto :nobackend
cd /d "%BACKEND%" || goto :fail
rem Paquet client (npm run package) : node embarque, serveur deja regroupe dans dist.
if exist "%BACKEND%\dist\server.mjs" goto :embedded

where node >nul 2>nul || goto :nonode
node -e "process.exit(+process.versions.node.split('.')[0] >= 22 ? 0 : 1)" || goto :nonode

echo Installation des dependances...
call npm install --no-audit --no-fund || goto :fail
call npm run service:install || goto :fail
echo.
if not defined SILENT pause
exit /b 0

:embedded
if not exist "%BACKEND%\node\node.exe" goto :nonodeexe
if defined SILENT goto :install
echo Autorisation dans le pare-feu de Windows (accepte la demande d'administrateur)...
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%BACKEND%\scripts\firewall-windows.ps1"
if errorlevel 1 echo Pare-feu non regle : si la tablette ne trouve pas ce PC, relance ce fichier et accepte la demande.
:install
echo Installation...
"%BACKEND%\node\node.exe" --env-file-if-exists=.env "%BACKEND%\dist\service.mjs" install || goto :fail
echo.
if not defined SILENT pause
exit /b 0

:nonode
echo Node.js 22 (ou plus recent) est necessaire : installe la version LTS depuis nodejs.org, puis relance ce fichier.
if not defined SILENT start "" "https://nodejs.org/fr/download"
if not defined SILENT pause
exit /b 1

:nonodeexe
echo Le paquet est incomplet : node.exe est introuvable dans
echo "%BACKEND%\node"
echo Extrais de nouveau le zip (ou regarde si l'antivirus l'a mis en quarantaine).
goto :fail

:nobackend
rem Lance depuis le zip sans l'extraire : Windows ne copie que ce fichier dans un dossier temporaire.
echo Le dossier "backend" est introuvable a cote de ce fichier.
echo Si tu l'as ouvert depuis le zip : clic droit sur le zip, "Extraire tout...", puis relance ce fichier
echo depuis le dossier extrait (il doit contenir "backend").
if not defined SILENT pause
exit /b 1

:fail
echo.
echo L'installation a echoue (voir le message ci-dessus).
if not defined SILENT pause
exit /b 1
