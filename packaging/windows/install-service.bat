@echo off
REM ============================================================
REM  Installation du service Windows SIGDEP-3 sigdep-sync.
REM
REM  A executer en tant qu'administrateur (clic droit > Executer
REM  en tant qu'administrateur). Sans droits admin, la commande
REM  "winsw install" echoue avec "Access denied".
REM
REM  Le script :
REM    1. Verifie que sigdep-sync-service.exe est dans le dossier.
REM    2. Verifie que .env existe (sinon arrete et demande de le creer).
REM    3. Installe le service Windows.
REM    4. Le demarre.
REM    5. Affiche son statut.
REM ============================================================

setlocal
cd /d "%~dp0"

if not exist sigdep-sync-service.exe (
    echo [ERREUR] sigdep-sync-service.exe introuvable dans ce dossier.
    echo Verifiez que l'archive a ete completement extraite.
    pause
    exit /b 1
)

if not exist .env (
    echo [ERREUR] Fichier .env manquant.
    echo.
    echo Copiez sigdep-sync.env.example en .env et completez les
    echo valeurs (SIGDEP_SITE_CODE, SIGDEP_LOCAL_DB_PASSWORD,
    echo SIGDEP_CENTRAL_API_URL, SIGDEP_API_KEY).
    echo.
    pause
    exit /b 1
)

echo === Installation du service sigdep-sync ===
sigdep-sync-service.exe install
if errorlevel 1 (
    echo [ERREUR] L'installation a echoue. Verifiez que vous avez
    echo execute ce script en tant qu'administrateur.
    pause
    exit /b 1
)

echo === Demarrage du service ===
sigdep-sync-service.exe start
if errorlevel 1 (
    echo [ATTENTION] Le service est installe mais n'a pas demarre.
    echo Consultez logs\sigdep-sync.err.log pour le motif.
    pause
    exit /b 1
)

echo.
echo === Statut ===
sigdep-sync-service.exe status

echo.
echo Service installe et demarre. Pour suivre les logs :
echo    type logs\sigdep-sync.out.log
echo Pour l'arreter :
echo    sigdep-sync-service.exe stop
echo Pour le desinstaller :
echo    uninstall-service.bat
echo.
pause
