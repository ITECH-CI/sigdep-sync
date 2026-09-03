@echo off
REM ============================================================
REM  Desinstallation du service Windows SIGDEP-3.
REM
REM  A executer en tant qu'administrateur.
REM
REM  IMPORTANT : la desinstallation EFFACE les donnees de sante et les
REM  secrets laisses sur le poste :
REM    - le buffer SQLite (buffer.sqlite + -wal / -shm) sous
REM      C:\ProgramData\sigdep-sync : donnees patients non encore
REM      confirmees / rejetees ;
REM    - le fichier .env : cle API du site + mot de passe MySQL.
REM  Le poste ne doit conserver aucune donnee apres retrait de l'agent.
REM ============================================================

setlocal
cd /d "%~dp0"

set "BUFFER_DIR=C:\ProgramData\sigdep-sync"

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

REM ---- Effacement des donnees de sante (buffer) ----
REM  Le service est arrete : les fichiers ne sont plus verrouilles. On efface
REM  le buffer et ses sidecars WAL. Le dossier ProgramData lui-meme est retire
REM  s'il est vide (rmdir echoue silencieusement s'il reste des fichiers).
echo === Effacement du buffer SQLite (donnees de sante) ===
if exist "%BUFFER_DIR%\buffer.sqlite"     del /q "%BUFFER_DIR%\buffer.sqlite"
if exist "%BUFFER_DIR%\buffer.sqlite-wal" del /q "%BUFFER_DIR%\buffer.sqlite-wal"
if exist "%BUFFER_DIR%\buffer.sqlite-shm" del /q "%BUFFER_DIR%\buffer.sqlite-shm"
if exist "%BUFFER_DIR%" rmdir "%BUFFER_DIR%" 2>nul

REM ---- Effacement des secrets (.env) ----
echo === Effacement du fichier .env (cle API + mot de passe) ===
if exist ".env" del /q ".env"

echo.
echo Service desinstalle. Buffer SQLite et .env effaces du poste.
echo Aucune donnee de sante ni secret ne subsiste.
echo.
pause
