@echo off
setlocal
set "MAVEN_HOME=%~dp0tools\apache-maven-3.9.11"
if not exist "%MAVEN_HOME%\bin\mvn.cmd" (
  echo Project-local Maven was not found at "%MAVEN_HOME%".
  echo Restore the tools folder or install Maven 3.9+.
  exit /b 1
)
call "%MAVEN_HOME%\bin\mvn.cmd" %*
