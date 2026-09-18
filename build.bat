@echo off
cd /d "%~dp0"
if not exist java\classes mkdir java\classes
if "%JAVAC%"=="" set JAVAC=javac
echo compiling with %JAVAC% ...
%JAVAC% -d java\classes java\src\core\*.java java\src\app\*.java
if errorlevel 1 exit /b 1
echo done -^> java\classes
