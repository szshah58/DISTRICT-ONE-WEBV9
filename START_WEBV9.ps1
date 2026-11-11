$ErrorActionPreference = "Stop"
$credentialTarget = "DistrictOne_WEBV9_MySQL"
$credential = Get-StoredCredential -Target $credentialTarget
if (-not $credential) {
    throw "MySQL credential not found in Windows Credential Manager."
}
$env:WEBV9_DB_USERNAME = "district_one"
$env:WEBV9_DB_PASSWORD = $credential.GetNetworkCredential().Password
$env:WEBV9_DB_URL = "jdbc:mysql://127.0.0.1:3306/district_one?useSSL=false&serverTimezone=UTC&allowPublicKeyRetrieval=true"
Set-Location "D:\WEB_V9"
java -jar "D:\WEB_V9\backend\target\webv9-0.1.0.jar"
