@echo off
REM ============================================================
REM  Installation du service Windows SIGDEP-3 "sigdep-sync".
REM
REM  A EXECUTER EN TANT QU'ADMINISTRATEUR :
REM    clic droit sur ce fichier > "Executer en tant qu'administrateur"
REM
REM  Etapes :
REM    1. Verifie les droits administrateur.
REM    2. Verifie que tous les fichiers requis sont presents.
REM    3. Verifie que le service n'est pas deja installe.
REM    4. Installe le service, le demarre, affiche son statut.
REM
REM  NOTE : ce fichier doit rester enregistre avec des fins de ligne
REM  Windows (CRLF). Avec des fins de ligne Unix (LF), cmd.exe echoue
REM  avec un message du type "'.' etait inattendu.".
REM ============================================================

setlocal EnableExtensions
title Installation du service SIGDEP-3 sigdep-sync
cd /d "%~dp0"

set "SERVICE_ID=sigdep-sync"
set "WRAPPER=sigdep-sync-service.exe"
set "RC=0"

echo ============================================================
echo   SIGDEP-3 Edge Sync Agent - installation du service Windows
echo ============================================================
echo.
echo Dossier d'installation : %CD%
echo.

REM ---------- 1. Droits administrateur ----------
net session >nul 2>&1
if errorlevel 1 goto ERR_ADMIN

REM ---------- 2. Fichiers requis ----------
if not exist "%WRAPPER%" goto ERR_WRAPPER
if not exist "sigdep-sync-service.xml" goto ERR_XML
if not exist "sigdep-sync.jar" goto ERR_JAR
if not exist "jre\bin\java.exe" goto ERR_JRE
if not exist ".env" goto ERR_ENV

REM ---------- 3. Service deja installe ? ----------
sc query "%SERVICE_ID%" >nul 2>&1
if not errorlevel 1 goto ERR_EXISTS

REM ---------- 4. Installation ----------
echo [1/3] Installation du service...
"%WRAPPER%" install
if errorlevel 1 goto ERR_INSTALL
echo       OK.
echo.

echo [2/3] Demarrage du service...
"%WRAPPER%" start
if errorlevel 1 goto ERR_START
echo       OK.
echo.

echo [3/3] Statut du service :
"%WRAPPER%" status
echo.

echo ============================================================
echo   Service installe et demarre.
echo ============================================================
echo.
echo Journaux         : %CD%\logs\
echo Statut           : sc query %SERVICE_ID%
echo Arreter          : %WRAPPER% stop
echo Redemarrer       : %WRAPPER% restart
echo Desinstaller     : uninstall-service.bat
echo.
goto FIN

REM ============================================================
REM  Messages d'erreur
REM ============================================================

:ERR_ADMIN
echo [ERREUR] Droits administrateur requis.
echo.
echo Fermez cette fenetre, puis faites un clic droit sur
echo install-service.bat et choisissez "Executer en tant
echo qu'administrateur".
set "RC=1"
goto FIN

:ERR_WRAPPER
echo [ERREUR] %WRAPPER% introuvable dans ce dossier.
echo Verifiez que l'archive a ete completement extraite.
set "RC=2"
goto FIN

:ERR_XML
echo [ERREUR] sigdep-sync-service.xml introuvable.
echo Ce fichier de configuration doit etre a cote de %WRAPPER%.
set "RC=2"
goto FIN

:ERR_JAR
echo [ERREUR] sigdep-sync.jar introuvable.
echo L'archive n'a pas ete extraite completement.
set "RC=2"
goto FIN

:ERR_JRE
echo [ERREUR] jre\bin\java.exe introuvable.
echo Le runtime Java embarque est manquant : reextrayez l'archive
echo complete, dossier jre inclus.
set "RC=2"
goto FIN

:ERR_ENV
echo [ERREUR] Fichier .env manquant.
echo.
echo Copiez sigdep-sync.env.example en .env, puis renseignez :
echo    SIGDEP_SITE_CODE
echo    SIGDEP_LOCAL_DB_PASSWORD
echo    SIGDEP_CENTRAL_API_URL
echo    SIGDEP_API_KEY
echo.
echo Commande : copy sigdep-sync.env.example .env
set "RC=3"
goto FIN

:ERR_EXISTS
echo [ERREUR] Le service "%SERVICE_ID%" est deja installe.
echo.
echo Pour le reinstaller, desinstallez-le d'abord :
echo    uninstall-service.bat
echo Pour simplement le redemarrer :
echo    %WRAPPER% restart
set "RC=4"
goto FIN

:ERR_INSTALL
echo.
echo [ERREUR] L'installation du service a echoue.
echo Causes frequentes :
echo    - script non lance en tant qu'administrateur
echo    - sigdep-sync-service.xml invalide
echo    - antivirus bloquant %WRAPPER%
set "RC=5"
goto FIN

:ERR_START
echo.
echo [ATTENTION] Le service est installe mais n'a pas demarre.
echo Consultez les journaux dans %CD%\logs\ pour le motif
echo (fichiers *.err.log), puis relancez :
echo    %WRAPPER% start
set "RC=6"
goto FIN

REM ============================================================
:FIN
echo.
pause
endlocal & exit /b %RC%
