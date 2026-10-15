@echo off
cd /d "%~dp0"
if exist "C:\Program Files\Java\jdk-27\bin\javac.exe" set "PATH=C:\Program Files\Java\jdk-27\bin;%PATH%"
where javac >nul 2>nul
if errorlevel 1 (
    echo O JDK nao foi encontrado ^(javac^). Instale com:
    echo     winget install EclipseAdoptium.Temurin.21.JDK
    echo e abra um novo terminal.
    pause
    exit /b 1
)
if not exist out mkdir out
javac -encoding UTF-8 -d out src\*.java
if errorlevel 1 (
    echo Erro ao compilar.
    pause
    exit /b 1
)
java -Dfile.encoding=UTF-8 -cp out Servidor
pause
