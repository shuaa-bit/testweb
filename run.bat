@echo off
rem Compile and start the gym site on http://localhost:8080  (needs JDK 17 or newer)
cd /d "%~dp0"
if not exist out mkdir out
javac -d out src\gym\*.java || exit /b 1
java -cp out gym.Main
