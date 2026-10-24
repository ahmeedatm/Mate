@echo off
rem Double-clic : installe le compagnon sur ce PC Windows. Il demarrera ensuite tout seul
rem a chaque ouverture de session (voir backend\scripts\service.ts). Relancer ce fichier pour mettre a jour.
chcp 65001 >nul
if not exist "%~dp0backend\package.json" goto :nobackend
cd /d "%~dp0backend" || goto :fail

where node >nul 2>nul || goto :nonode
node -e "process.exit(+process.versions.node.split('.')[0] >= 22 ? 0 : 1)" || goto :nonode

echo Installation des dependances...
call npm install --no-audit --no-fund || goto :fail
call npm run service:install || goto :fail
echo.
pause
exit /b 0

:nonode
echo Node.js 22 (ou plus recent) est necessaire : installe la version LTS depuis nodejs.org, puis relance ce fichier.
start "" "https://nodejs.org/fr/download"
pause
exit /b 1

:nobackend
rem Lance depuis le zip sans l'extraire : Windows ne copie que ce fichier dans un dossier temporaire.
echo Le dossier "backend" est introuvable a cote de ce fichier.
echo Si tu l'as ouvert depuis le zip : clic droit sur le zip, "Extraire tout...", puis relance ce fichier
echo depuis le dossier extrait (il doit contenir "backend").
pause
exit /b 1

:fail
echo.
echo L'installation a echoue (voir le message ci-dessus).
pause
exit /b 1
