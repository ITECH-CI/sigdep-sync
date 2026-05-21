@echo off
REM ============================================================
REM  Desinstallation du service Windows SIGDEP-3.
REM
REM  A executer en tant qu'administrateur. Le buffer SQLite et le
REM  fichier .env ne sont PAS supprimes (ils restent dans le dossier
REM  d'installation pour un eventuel redeploiement).
REM ============================================================

setlocal
cd /d "%~dp0"

if not exist sigdep-sync-service.exe (
    echo [ERREUR] sigdep-sync-service.exe introuvable.
    pause
    exit /b 1
)

echo === Arret du service sigdep-sync ===
sigdep-sync-service.exe stop

echo === Desinstallation du service ===
sigdep-sync-service.exe uninstall
if errorlevel 1 (
    echo [ATTENTION] La desinstallation a echoue.
    echo Verifiez que vous avez execute ce script en tant qu'administrateur.
    pause
    exit /b 1
)

echo.
echo Service desinstalle. Le buffer SQLite (%CD%\buffer.sqlite) et
echo le fichier .env sont conserves. Pour les supprimer manuellement :
echo    del buffer.sqlite
echo    del .env
echo.
pause
