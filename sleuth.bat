@echo off
setlocal enabledelayedexpansion

REM Java-Sleuth Startup Script for Windows

set SCRIPT_DIR=%~dp0
set LAUNCHER_JAR=
set AGENT_JAR=
set CONTAINER_JAR=

REM Distribution default: stable jar filenames in the same directory as this script
if exist "%SCRIPT_DIR%java-sleuth-launcher.jar" (
    set LAUNCHER_JAR=%SCRIPT_DIR%java-sleuth-launcher.jar
    goto :launcher_found
)

for /f "delims=" %%f in ('dir /b /o:-d "%SCRIPT_DIR%launcher\target\java-sleuth-launcher*-jar-with-dependencies.jar" 2^>nul') do (
    set LAUNCHER_JAR=%SCRIPT_DIR%launcher\target\%%f
    goto :launcher_found
)
for /f "delims=" %%f in ('dir /b /o:-d "%SCRIPT_DIR%target\java-sleuth-launcher*-jar-with-dependencies.jar" 2^>nul') do (
    set LAUNCHER_JAR=%SCRIPT_DIR%target\%%f
    goto :launcher_found
)
for /f "delims=" %%f in ('dir /b /o:-d "%SCRIPT_DIR%lib\java-sleuth-launcher*-jar-with-dependencies.jar" 2^>nul') do (
    set LAUNCHER_JAR=%SCRIPT_DIR%lib\%%f
    goto :launcher_found
)
for /f "delims=" %%f in ('dir /b /o:-d "%SCRIPT_DIR%..\lib\java-sleuth-launcher*-jar-with-dependencies.jar" 2^>nul') do (
    set LAUNCHER_JAR=%SCRIPT_DIR%..\lib\%%f
    goto :launcher_found
)

REM Backward compatibility: legacy single fat-jar (artifactId=java-sleuth)
for /f "delims=" %%f in ('dir /b /o:-d "%SCRIPT_DIR%core\target\java-sleuth-[0-9]*-jar-with-dependencies.jar" 2^>nul') do (
    set LAUNCHER_JAR=%SCRIPT_DIR%core\target\%%f
    goto :launcher_found
)
for /f "delims=" %%f in ('dir /b /o:-d "%SCRIPT_DIR%target\java-sleuth-[0-9]*-jar-with-dependencies.jar" 2^>nul') do (
    set LAUNCHER_JAR=%SCRIPT_DIR%target\%%f
    goto :launcher_found
)
for /f "delims=" %%f in ('dir /b /o:-d "%SCRIPT_DIR%lib\java-sleuth-[0-9]*-jar-with-dependencies.jar" 2^>nul') do (
    set LAUNCHER_JAR=%SCRIPT_DIR%lib\%%f
    goto :launcher_found
)
for /f "delims=" %%f in ('dir /b /o:-d "%SCRIPT_DIR%..\lib\java-sleuth-[0-9]*-jar-with-dependencies.jar" 2^>nul') do (
    set LAUNCHER_JAR=%SCRIPT_DIR%..\lib\%%f
    goto :launcher_found
)

:launcher_found

REM Distribution default: stable agent jar
if exist "%SCRIPT_DIR%java-sleuth-agent.jar" (
    set AGENT_JAR=%SCRIPT_DIR%java-sleuth-agent.jar
    goto :agent_found
)

for /f "delims=" %%f in ('dir /b /o:-d "%SCRIPT_DIR%agent\target\java-sleuth-agent*-jar-with-dependencies.jar" 2^>nul') do (
    set AGENT_JAR=%SCRIPT_DIR%agent\target\%%f
    goto :agent_found
)
for /f "delims=" %%f in ('dir /b /o:-d "%SCRIPT_DIR%target\java-sleuth-agent*-jar-with-dependencies.jar" 2^>nul') do (
    set AGENT_JAR=%SCRIPT_DIR%target\%%f
    goto :agent_found
)
for /f "delims=" %%f in ('dir /b /o:-d "%SCRIPT_DIR%lib\java-sleuth-agent*-jar-with-dependencies.jar" 2^>nul') do (
    set AGENT_JAR=%SCRIPT_DIR%lib\%%f
    goto :agent_found
)
for /f "delims=" %%f in ('dir /b /o:-d "%SCRIPT_DIR%..\lib\java-sleuth-agent*-jar-with-dependencies.jar" 2^>nul') do (
    set AGENT_JAR=%SCRIPT_DIR%..\lib\%%f
    goto :agent_found
)

:agent_found

REM Distribution default: stable container jar (payload)
if exist "%SCRIPT_DIR%java-sleuth-container.jar" (
    set CONTAINER_JAR=%SCRIPT_DIR%java-sleuth-container.jar
    goto :container_found
)

for /f "delims=" %%f in ('dir /b /o:-d "%SCRIPT_DIR%container\target\java-sleuth-container*-jar-with-dependencies.jar" 2^>nul') do (
    set CONTAINER_JAR=%SCRIPT_DIR%container\target\%%f
    goto :container_found
)
for /f "delims=" %%f in ('dir /b /o:-d "%SCRIPT_DIR%target\java-sleuth-container*-jar-with-dependencies.jar" 2^>nul') do (
    set CONTAINER_JAR=%SCRIPT_DIR%target\%%f
    goto :container_found
)
for /f "delims=" %%f in ('dir /b /o:-d "%SCRIPT_DIR%lib\java-sleuth-container*-jar-with-dependencies.jar" 2^>nul') do (
    set CONTAINER_JAR=%SCRIPT_DIR%lib\%%f
    goto :container_found
)
for /f "delims=" %%f in ('dir /b /o:-d "%SCRIPT_DIR%..\lib\java-sleuth-container*-jar-with-dependencies.jar" 2^>nul') do (
    set CONTAINER_JAR=%SCRIPT_DIR%..\lib\%%f
    goto :container_found
)

:container_found

if "%LAUNCHER_JAR%"=="" (
    echo Java-Sleuth launcher JAR file not found under:
    echo   - %SCRIPT_DIR%launcher\target\
    echo   - %SCRIPT_DIR%target\
    echo   - %SCRIPT_DIR%lib\
    echo   - %SCRIPT_DIR%..\lib\
    echo Please build the project first with: mvn clean package
    exit /b 1
)

if not exist "%LAUNCHER_JAR%" (
    echo Java-Sleuth launcher JAR file not found: %LAUNCHER_JAR%
    exit /b 1
)

if "%AGENT_JAR%"=="" (
    for %%a in ("%LAUNCHER_JAR%") do set LAUNCHER_NAME=%%~nxa
    echo !LAUNCHER_NAME! | findstr /i "^java-sleuth-agent-" >nul && set LAUNCHER_IS_AGENT=1
    echo !LAUNCHER_NAME! | findstr /i "^java-sleuth-launcher-" >nul && set LAUNCHER_IS_LAUNCHER=1
    if "!LAUNCHER_IS_AGENT!"=="1" (
        set AGENT_JAR=%LAUNCHER_JAR%
    ) else if "!LAUNCHER_IS_LAUNCHER!"=="" (
        set AGENT_JAR=%LAUNCHER_JAR%
    )
)

if "%AGENT_JAR%"=="" (
    echo Java-Sleuth agent JAR file not found under:
    echo   - %SCRIPT_DIR%agent\target\
    echo   - %SCRIPT_DIR%target\
    echo   - %SCRIPT_DIR%lib\
    echo   - %SCRIPT_DIR%..\lib\
    echo Tip: set -Dsleuth.agent.jar^=<path^> (or env SLEUTH_AGENT_JAR)
    echo Please build the project first with: mvn clean package
    exit /b 1
)

if not exist "%AGENT_JAR%" (
    echo Java-Sleuth agent JAR file not found: %AGENT_JAR%
    exit /b 1
)

REM Check Java version
for /f "tokens=3" %%g in ('java -version 2^>^&1 ^| findstr /i "version"') do (
    set JAVA_VERSION_STRING=%%g
)
set JAVA_VERSION_STRING=%JAVA_VERSION_STRING:"=%
for /f "delims=." %%a in ("%JAVA_VERSION_STRING%") do set JAVA_MAJOR_VERSION=%%a

if %JAVA_MAJOR_VERSION% LSS 9 (
    set TOOLS_JAR=%JAVA_HOME%\lib\tools.jar
    if not exist "!TOOLS_JAR!" (
        echo tools.jar not found. Please set JAVA_HOME correctly for JDK ^< 9
        exit /b 1
    )
    set CLASSPATH=%LAUNCHER_JAR%;!TOOLS_JAR!
) else (
    set CLASSPATH=%LAUNCHER_JAR%
)

echo Starting Java-Sleuth...
echo Java Version: %JAVA_MAJOR_VERSION%
echo Launcher JAR: %LAUNCHER_JAR%
echo Agent JAR: %AGENT_JAR%

set LAUNCHER_OPTS=
if not "%SLEUTH_CONFIG_FILE%"=="" (
    set LAUNCHER_OPTS=-Dsleuth.config.file="%SLEUTH_CONFIG_FILE%"
)

if "%CONTAINER_JAR%"=="" (
    if not "%AGENT_JAR%"=="%LAUNCHER_JAR%" (
        echo Java-Sleuth agent CONTAINER JAR file not found under:
        echo   - %SCRIPT_DIR%java-sleuth-container.jar
        echo   - %SCRIPT_DIR%container\target\
        echo   - %SCRIPT_DIR%target\
        echo   - %SCRIPT_DIR%lib\
        echo   - %SCRIPT_DIR%..\lib\
        echo Tip: set -Dsleuth.agent.container.jar^=<path^> (or env SLEUTH_AGENT_CONTAINER_JAR)
        echo Please build the project first with: mvn clean package
        exit /b 1
    )
)

set CONTAINER_OPTS=
if not "%CONTAINER_JAR%"=="" (
    if exist "%CONTAINER_JAR%" (
        echo Agent CONTAINER JAR: %CONTAINER_JAR%
        set CONTAINER_OPTS=-Dsleuth.agent.container.jar="%CONTAINER_JAR%"
    )
)

java %LAUNCHER_OPTS% %CONTAINER_OPTS% -Dsleuth.agent.jar="%AGENT_JAR%" -cp "%CLASSPATH%" com.javasleuth.launcher.SleuthLauncher %*
