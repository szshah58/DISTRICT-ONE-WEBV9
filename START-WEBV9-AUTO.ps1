$ErrorActionPreference = "Continue"

$Root = "D:\WEB_V9"
$Log = "$Root\logs"

New-Item -ItemType Directory -Force -Path $Log | Out-Null

Start-Transcript -Path "$Log\AUTO-START.log" -Append

Write-Host "DISTRICT ONE WEBV9 STARTUP"
Write-Host (Get-Date)

# Load permanent machine environment variables
foreach ($v in @(
    "WEBV9_DB_URL",
    "WEBV9_DB_USERNAME",
    "WEBV9_DB_PASSWORD"
)) {
    $value = [Environment]::GetEnvironmentVariable($v,"Machine")

    if ($value) {
        [Environment]::SetEnvironmentVariable($v,$value,"Process")
    }
}

# Check database configuration
if (-not $env:WEBV9_DB_URL -or
    -not $env:WEBV9_DB_USERNAME -or
    -not $env:WEBV9_DB_PASSWORD) {

    Write-Host "ERROR: Database environment configuration missing."
    Stop-Transcript
    exit 1
}

# Start/check MySQL
try {
    Start-Service MySQL84 -ErrorAction SilentlyContinue

    $DatabaseReady = $false

    for ($i = 1; $i -le 45; $i++) {
        $Client = New-Object System.Net.Sockets.TcpClient

        try {
            $Client.Connect("127.0.0.1",3306)
            $DatabaseReady = $true
            break
        }
        catch {
            Start-Sleep -Seconds 2
        }
        finally {
            $Client.Dispose()
        }
    }

    if (-not $DatabaseReady) {
        throw "MySQL port 3306 unavailable."
    }

    Write-Host "MYSQL: READY ON 3306"
}
catch {
    Write-Host "MYSQL ERROR: $_"
    Stop-Transcript
    exit 1
}

# Helper: check TCP port
function Test-Port($Port) {
    $Client = New-Object System.Net.Sockets.TcpClient

    try {
        $Client.Connect("127.0.0.1",$Port)
        return $true
    }
    catch {
        return $false
    }
    finally {
        $Client.Dispose()
    }
}

# Start Backend
$Jar = "$Root\backend\target\webv9-0.1.0.jar"

$Java = "C:\Program Files\Android\Android Studio\jbr\bin\java.exe"

if (-not (Test-Path $Jar)) {
    Write-Host "BACKEND ERROR: JAR NOT FOUND."
}
elseif (-not (Test-Path $Java)) {
    Write-Host "BACKEND ERROR: JAVA NOT FOUND."
}
elseif (Test-Port 8081) {
    Write-Host "BACKEND: ALREADY RUNNING."
}
else {
    Start-Process -FilePath $Java `
        -ArgumentList @("-jar", "`"$Jar`"") `
        -WorkingDirectory "$Root\backend" `
        -RedirectStandardOutput "$Log\BACKEND.log" `
        -RedirectStandardError "$Log\BACKEND-ERROR.log"

    Write-Host "BACKEND START COMMAND SENT."

    $BackendReady = $false

    for ($i = 1; $i -le 60; $i++) {
        Start-Sleep -Seconds 2

        if (Test-Port 8081) {
            $BackendReady = $true
            break
        }
    }

    if ($BackendReady) {
        Write-Host "BACKEND: PORT 8081 READY."
    }
    else {
        Write-Host "BACKEND: NOT READY - CHECK BACKEND LOGS."
    }
}

# Start Frontend
$Frontend = "$Root\frontend"
$Npm = "C:\Program Files\nodejs\npm.cmd"

if (-not (Test-Path "$Frontend\package.json")) {
    Write-Host "FRONTEND ERROR: PACKAGE.JSON MISSING."
}
elseif (-not (Test-Path $Npm)) {
    Write-Host "FRONTEND ERROR: NPM NOT FOUND."
}
elseif (Test-Port 8080) {
    Write-Host "FRONTEND: ALREADY RUNNING ON 8080."
}
else {
    Start-Process -FilePath "cmd.exe" `
        -ArgumentList @(
            "/c",
            "`"$Npm`" run dev -- --host 0.0.0.0 --port 8080"
        ) `
        -WorkingDirectory $Frontend `
        -RedirectStandardOutput "$Log\FRONTEND.log" `
        -RedirectStandardError "$Log\FRONTEND-ERROR.log"

    Write-Host "FRONTEND START COMMAND SENT."

    Start-Sleep -Seconds 8

    if (Test-Port 8080) {
        Write-Host "FRONTEND: PORT 8080 READY."
    }
    else {
        Write-Host "FRONTEND: NOT READY - CHECK FRONTEND LOGS."
    }
}

Write-Host "================================"
Write-Host "WEBV9 STARTUP CHECK COMPLETED"
Write-Host "================================"

Stop-Transcript
