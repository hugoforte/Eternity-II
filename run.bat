@echo off
cd /d "%~dp0"
if not exist java\classes\core\MrvSolver.class call build.bat
if errorlevel 1 exit /b 1
if "%PYTHON%"=="" set PYTHON=python
%PYTHON% server\app.py %*
