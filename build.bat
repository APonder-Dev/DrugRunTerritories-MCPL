@echo off
rem ---------------------------------------------------------------------------
rem DrugRunTerritories build script (Windows)
rem Validates Java 25+, compiles, runs every test, and reports the plugin JAR.
rem Usage: build.bat            full build with tests
rem        build.bat -DskipTests  build without tests (not recommended)
rem ---------------------------------------------------------------------------
setlocal EnableDelayedExpansion
cd /d "%~dp0"

rem Prefer JAVA_HOME; fall back to java on PATH.
if defined JAVA_HOME (
    set "JAVA_EXE=%JAVA_HOME%\bin\java.exe"
) else (
    set "JAVA_EXE=java"
)
"%JAVA_EXE%" -version >nul 2>&1
if errorlevel 1 (
    echo [build] ERROR: Java was not found. Install JDK 25 and set JAVA_HOME.
    exit /b 1
)

rem Read the major version from "java -version" (first line, e.g. openjdk version "25.0.3").
set "JAVA_MAJOR="
for /f "tokens=3" %%v in ('call "%JAVA_EXE%" -version 2^>^&1 ^| findstr /i "version"') do (
    set "RAW=%%~v"
    for /f "delims=." %%m in ("!RAW!") do set "JAVA_MAJOR=%%m"
)
if not defined JAVA_MAJOR (
    echo [build] ERROR: Could not read the Java version.
    exit /b 1
)
if !JAVA_MAJOR! LSS 25 (
    echo [build] ERROR: Java !JAVA_MAJOR! found; DrugRunTerritories needs Java 25 or newer.
    echo [build]        Set JAVA_HOME to a JDK 25 installation, e.g. C:\Program Files\Java\jdk-25.0.3
    exit /b 1
)
echo [build] Using Java !JAVA_MAJOR!

if not exist "mvnw.cmd" (
    echo [build] ERROR: mvnw.cmd is missing; the Maven Wrapper is required.
    exit /b 1
)

rem DrugLab is not on a public repository: it must be installed locally once.
if not exist "%USERPROFILE%\.m2\repository\dev\aponder\DrugLab\1.0.0\DrugLab-1.0.0.jar" (
    echo [build] ERROR: DrugLab 1.0.0 is not in your local Maven repository.
    echo [build]        Run "mvnw.cmd install -DskipTests" in the DrugLab project first. See INSTALLATION.md.
    exit /b 1
)

call "%~dp0mvnw.cmd" -B clean verify %*
if errorlevel 1 (
    echo.
    echo [build] BUILD FAILED. See the output above; test reports are in target\surefire-reports.
    exit /b 1
)

set "JAR=%CD%\target\DrugRunTerritories-0.1.0-SNAPSHOT.jar"
if not exist "%JAR%" (
    echo [build] ERROR: Build reported success but the JAR was not produced.
    exit /b 1
)
echo.
echo [build] BUILD SUCCEEDED
echo [build] Plugin JAR: %JAR%
endlocal
exit /b 0
