<#
.SYNOPSIS
    Creates the HOPA Android app in the Firebase project and writes its config.

.DESCRIPTION
    Idempotent: lists the project's Android apps, creates com.hopa.android only
    when it is absent, then writes google-services.json under apps/android/app.
    The file is gitignored and the Gradle plugin is applied only when it exists.

    The service-account key, the signed JWT and the access token never leave
    this script: the console output is the app id, the package and the path.

.PARAMETER CredentialPath
    Path to the Firebase service-account JSON (client_email and private_key).

.PARAMETER ProjectId
    Firebase/GCP project id. Defaults to the deployed project.

.EXAMPLE
    .\tools\scripts\fetch-google-services.ps1 -CredentialPath C:\secrets\hopa-fcm-sa.json
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string]$CredentialPath,

    [string]$ProjectId = 'hopa-prod-d767c',

    [string]$PackageName = 'com.hopa.android',

    [string]$DisplayName = 'HOPA',

    [string]$OutFile,

    [string]$OpenSsl = 'C:\Program Files\Git\usr\bin\openssl.exe',

    [int]$OperationTimeoutSeconds = 120
)

$ErrorActionPreference = 'Stop'
[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12

$apiBase = 'https://firebase.googleapis.com/v1beta1'

if (-not $OutFile) {
    $root = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
    $OutFile = Join-Path $root 'apps/android/app/google-services.json'
}

function ConvertTo-Base64Url([byte[]]$Bytes) {
    [Convert]::ToBase64String($Bytes).TrimEnd('=').Replace('+', '-').Replace('/', '_')
}

function Get-AccessToken {
    if (-not (Test-Path -LiteralPath $CredentialPath)) {
        throw "Service-account file not found: $CredentialPath"
    }
    if (-not (Test-Path -LiteralPath $OpenSsl)) {
        throw "openssl not found: $OpenSsl"
    }

    $account = Get-Content -LiteralPath $CredentialPath -Raw | ConvertFrom-Json
    if (-not $account.client_email -or -not $account.private_key) {
        throw 'The service-account file has no client_email or private_key.'
    }
    $tokenUri = if ($account.token_uri) { $account.token_uri } else { 'https://oauth2.googleapis.com/token' }

    $issuedAt = [DateTimeOffset]::UtcNow.ToUnixTimeSeconds()
    $header = [ordered]@{ alg = 'RS256'; typ = 'JWT' } | ConvertTo-Json -Compress
    $claims = [ordered]@{
        iss = $account.client_email
        scope = 'https://www.googleapis.com/auth/cloud-platform'
        aud = $tokenUri
        iat = $issuedAt
        exp = $issuedAt + 3600
    } | ConvertTo-Json -Compress
    $unsigned = (ConvertTo-Base64Url ([Text.Encoding]::UTF8.GetBytes($header))) + '.' +
        (ConvertTo-Base64Url ([Text.Encoding]::UTF8.GetBytes($claims)))

    # Windows PowerShell 5.1 cannot import a PEM key itself, so openssl signs
    # the input and only the signature comes back.
    $work = Join-Path ([IO.Path]::GetTempPath()) ('hopa-fcm-' + [Guid]::NewGuid().ToString('N'))
    New-Item -ItemType Directory -Path $work | Out-Null
    $keyFile = Join-Path $work 'signing-key.pem'
    $inputFile = Join-Path $work 'signing-input.txt'
    $signatureFile = Join-Path $work 'signature.bin'
    try {
        $utf8 = New-Object Text.UTF8Encoding($false)
        [IO.File]::WriteAllText($keyFile, $account.private_key, $utf8)
        [IO.File]::WriteAllText($inputFile, $unsigned, $utf8)
        & $OpenSsl dgst -sha256 -sign $keyFile -out $signatureFile $inputFile
        if ($LASTEXITCODE -ne 0) {
            throw "openssl could not sign the token (exit $LASTEXITCODE)."
        }
        $signature = [IO.File]::ReadAllBytes($signatureFile)
    } finally {
        Remove-Item -LiteralPath $work -Recurse -Force -ErrorAction SilentlyContinue
    }

    $assertion = $unsigned + '.' + (ConvertTo-Base64Url $signature)
    $response = Invoke-RestMethod -Method Post -Uri $tokenUri -ContentType 'application/x-www-form-urlencoded' -Body @{
        grant_type = 'urn:ietf:params:oauth:grant-type:jwt-bearer'
        assertion = $assertion
    }
    if (-not $response.access_token) {
        throw 'The token endpoint returned no access token.'
    }
    return $response.access_token
}

function Get-Firebase([string]$Uri) {
    Invoke-RestMethod -Method Get -Uri $Uri -Headers @{ Authorization = "Bearer $script:accessToken" }
}

function New-AndroidApp {
    $body = @{ packageName = $PackageName; displayName = $DisplayName } | ConvertTo-Json
    $headers = @{ Authorization = "Bearer $script:accessToken" }
    $operation = Invoke-RestMethod -Method Post -Uri "$apiBase/projects/$ProjectId/androidApps" `
        -Headers $headers -ContentType 'application/json' -Body $body
    $deadline = (Get-Date).AddSeconds($OperationTimeoutSeconds)
    while (-not $operation.done) {
        if ((Get-Date) -gt $deadline) {
            throw 'Timed out waiting for the Android app to be created.'
        }
        Start-Sleep -Seconds 2
        $operation = Invoke-RestMethod -Method Get -Uri "$apiBase/$($operation.name)" -Headers $headers
    }
    if ($operation.error) {
        throw "Firebase refused the Android app: $($operation.error.message)"
    }
    return $operation.response.appId
}

$script:accessToken = Get-AccessToken

$listed = Get-Firebase "$apiBase/projects/$ProjectId/androidApps"
$app = @($listed.apps) | Where-Object { $_.packageName -eq $PackageName } | Select-Object -First 1
if ($app) {
    $appId = $app.appId
} else {
    $appId = New-AndroidApp
    if (-not $appId) {
        $listed = Get-Firebase "$apiBase/projects/$ProjectId/androidApps"
        $appId = (@($listed.apps) | Where-Object { $_.packageName -eq $PackageName } | Select-Object -First 1).appId
    }
    if (-not $appId) {
        throw 'The app was created but its id could not be read back.'
    }
}

$config = Get-Firebase "$apiBase/projects/$ProjectId/androidApps/$appId/config"
if (-not $config.configFileContents) {
    throw "Firebase returned no config for app $appId."
}

$directory = Split-Path -Parent $OutFile
if (-not (Test-Path -LiteralPath $directory)) {
    New-Item -ItemType Directory -Path $directory | Out-Null
}
[IO.File]::WriteAllBytes($OutFile, [Convert]::FromBase64String($config.configFileContents))

Write-Host ("app id:  {0}" -f $appId)
Write-Host ("package: {0}" -f $PackageName)
Write-Host ("config:  {0}" -f $OutFile)
