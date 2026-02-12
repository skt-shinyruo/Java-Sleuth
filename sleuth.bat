@echo off
setlocal enabledelayedexpansion

REM Java-Sleuth Startup Script for Windows

set SCRIPT_DIR=%~dp0
set LAUNCHER_JAR=
set AGENT_JAR=
set CORE_JAR=

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

for /f "delims=" %%f in ('dir /b /o:-d "%SCRIPT_DIR%agent\target\java-sleuth-agent*-jar-with-dependencies.jar" 2^>nul') do (
    set AGENT_JAR=%SCRIPT_DIR%agent\target\%%f
    goto :agent_found
)
for /f "delims=" %%f in ('dir /b /o:-d "%SCRIPT_DIR%target\java-sleuth-agent*-jar-with-dependencies.jar" 2^>nul') do (
    echo %%f | findstr /i "^java-sleuth-agent-core-" >nul
    if errorlevel 1 (
        set AGENT_JAR=%SCRIPT_DIR%target\%%f
        goto :agent_found
    )
)
for /f "delims=" %%f in ('dir /b /o:-d "%SCRIPT_DIR%lib\java-sleuth-agent*-jar-with-dependencies.jar" 2^>nul') do (
    echo %%f | findstr /i "^java-sleuth-agent-core-" >nul
    if errorlevel 1 (
        set AGENT_JAR=%SCRIPT_DIR%lib\%%f
        goto :agent_found
    )
)
for /f "delims=" %%f in ('dir /b /o:-d "%SCRIPT_DIR%..\lib\java-sleuth-agent*-jar-with-dependencies.jar" 2^>nul') do (
    echo %%f | findstr /i "^java-sleuth-agent-core-" >nul
    if errorlevel 1 (
        set AGENT_JAR=%SCRIPT_DIR%..\lib\%%f
        goto :agent_found
    )
)

:agent_found

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

for /f "delims=" %%f in ('dir /b /o:-d "%SCRIPT_DIR%core\target\java-sleuth-agent-core*-jar-with-dependencies.jar" 2^>nul') do (
    set CORE_JAR=%SCRIPT_DIR%core\target\%%f
    goto :core_found
)
for /f "delims=" %%f in ('dir /b /o:-d "%SCRIPT_DIR%target\java-sleuth-agent-core*-jar-with-dependencies.jar" 2^>nul') do (
    set CORE_JAR=%SCRIPT_DIR%target\%%f
    goto :core_found
)
for /f "delims=" %%f in ('dir /b /o:-d "%SCRIPT_DIR%lib\java-sleuth-agent-core*-jar-with-dependencies.jar" 2^>nul') do (
    set CORE_JAR=%SCRIPT_DIR%lib\%%f
    goto :core_found
)
for /f "delims=" %%f in ('dir /b /o:-d "%SCRIPT_DIR%..\lib\java-sleuth-agent-core*-jar-with-dependencies.jar" 2^>nul') do (
    set CORE_JAR=%SCRIPT_DIR%..\lib\%%f
    goto :core_found
)

:core_found

if "%CORE_JAR%"=="" (
    if not "%AGENT_JAR%"=="%LAUNCHER_JAR%" (
        echo Java-Sleuth agent CORE JAR file not found under:
        echo   - %SCRIPT_DIR%core\target\
        echo   - %SCRIPT_DIR%target\
        echo   - %SCRIPT_DIR%lib\
        echo   - %SCRIPT_DIR%..\lib\
        echo Tip: set -Dsleuth.agent.core.jar^=<path^> (or env SLEUTH_AGENT_CORE_JAR)
        echo Please build the project first with: mvn clean package
        exit /b 1
    )
)

set CORE_OPTS=
if not "%CORE_JAR%"=="" (
    if exist "%CORE_JAR%" (
        echo Agent CORE JAR: %CORE_JAR%
        set CORE_OPTS=-Dsleuth.agent.core.jar="%CORE_JAR%"
    )
)

java %LAUNCHER_OPTS% %CORE_OPTS% -Dsleuth.agent.jar="%AGENT_JAR%" -cp "%CLASSPATH%" com.javasleuth.launcher.SleuthLauncher %*
