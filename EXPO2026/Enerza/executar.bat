@echo off
setlocal enabledelayedexpansion
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
if not exist lib mkdir lib
if not exist lib\mysql-connector-j.jar (
    echo Baixando o driver do MySQL...
    powershell -NoProfile -Command "try { Invoke-WebRequest -UseBasicParsing -Uri 'https://repo1.maven.org/maven2/com/mysql/mysql-connector-j/8.4.0/mysql-connector-j-8.4.0.jar' -OutFile 'lib\mysql-connector-j.jar' } catch { exit 1 }"
    if errorlevel 1 (
        if exist lib\mysql-connector-j.jar del lib\mysql-connector-j.jar
        echo Nao foi possivel baixar o driver. Baixe manualmente em
        echo https://dev.mysql.com/downloads/connector/j/ ^(Platform Independent^)
        echo e salve o .jar como lib\mysql-connector-j.jar
        pause
        exit /b 1
    )
)
if not exist out mkdir out
set "FONTES="
for /r %%f in (*.java) do set FONTES=!FONTES! "%%f"
if not defined FONTES (
    echo Nenhum arquivo .java encontrado nesta pasta.
    pause
    exit /b 1
)
javac -encoding UTF-8 -d out !FONTES!
if errorlevel 1 (
    echo Erro ao compilar.
    pause
    exit /b 1
)
set "PRINCIPAL=enerza.Servidor"
if not exist out\enerza\Servidor.class set "PRINCIPAL=Servidor"
java -Dfile.encoding=UTF-8 -cp "out;lib\*" !PRINCIPAL!
pause