@echo off
setlocal enabledelayedexpansion

REM Java-Sleuth Startup Script for Windows

set SCRIPT_DIR=%~dp0
set JAR_FILE=%SCRIPT_DIR%target\java-sleuth-1.0.0-jar-with-dependencies.jar

if not exist "%JAR_FILE%" (
    echo Java-Sleuth JAR file not found: %JAR_FILE%
    echo Please build the project first with: mvn clean package
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
    set CLASSPATH=%JAR_FILE%;!TOOLS_JAR!
) else (
    set CLASSPATH=%JAR_FILE%
)

echo Starting Java-Sleuth...
echo Java Version: %JAVA_MAJOR_VERSION%
echo JAR File: %JAR_FILE%

java -cp "%CLASSPATH%" com.javasleuth.launcher.SleuthLauncher %*