@echo off
REM Build script for dcc-io-daemon (Windows)
REM This script builds JMRI first (if needed), creates a JAR, installs it to local Maven repo, then builds the daemon

setlocal

if "%JMRI_HOME%"=="" set JMRI_HOME=%~dp0..\JMRI-5.12

echo Building dcc-io-daemon...
echo JMRI home: %JMRI_HOME%

REM Check if JMRI classes exist
if not exist "%JMRI_HOME%\java\build\classes" (
    echo JMRI classes not found. Building JMRI first...
    cd /d "%JMRI_HOME%"
    if exist "%JAVA_HOME%\bin\java.exe" (
        call ant
    ) else (
        echo ERROR: Java not found. Please build JMRI manually:
        echo   cd %JMRI_HOME%
        echo   ant
        exit /b 1
    )
    cd /d "%~dp0"
)

REM Create JMRI JAR if it doesn't exist
set JMRI_JAR=%JMRI_HOME%\jmri.jar
if not exist "%JMRI_JAR%" (
    echo Creating JMRI JAR...
    cd /d "%JMRI_HOME%"
    jar -cf jmri.jar -C java\build\classes .
    if errorlevel 1 (
        echo ERROR: Failed to create JMRI JAR. Make sure 'jar' command is available.
        exit /b 1
    )
    cd /d "%~dp0"
)

REM Install JMRI JAR to local Maven repo
echo Installing JMRI JAR to local Maven repository...
call mvn install:install-file -Dfile="%JMRI_JAR%" -DgroupId=org.jmri -DartifactId=jmri -Dversion=5.12.0 -Dpackaging=jar -DgeneratePom=true

REM Build the daemon
echo Building daemon...
cd /d "%~dp0"
call mvn clean package

echo.
echo Build complete! Run with:
echo   java -jar target\dcc-io-daemon-0.1.0-SNAPSHOT-jar-with-dependencies.jar [port]

endlocal

