@echo off
cd /d "%~dp0"
if not exist java\classes mkdir java\classes
if "%JAVAC%"=="" set JAVAC=javac
%JAVAC% -d java\classes java\src\core\*.java java\src\app\*.java java\test\core\*.java
if errorlevel 1 exit /b 1
java -cp java\classes core.AllTests
