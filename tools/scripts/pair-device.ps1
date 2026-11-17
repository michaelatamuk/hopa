<#
.SYNOPSIS
    Mint a single-use pairing link for a device.

.DESCRIPTION
    Reads HOPA_BACKEND_URL (default https://hopa-backend-test.onrender.com) and
    HOPA_BOOTSTRAP_TOKEN, then POSTs /v1/pair/sessions. Prints the
    hopa://pair?... link and the expiry the backend assigned. The bootstrap
    token is never printed, echoed into a file, or put in the request body.

    A session is claimed once and expires after five minutes. The link contains
    the gateway URL the new device connects to.

.PARAMETER HouseholdName
    Name used when the session creates a new household. Ignored with -HouseholdId.

.PARAMETER MemberName
    Display name of the member the new device belongs to.

.PARAMETER HouseholdId
    Join this existing household instead of creating one (the second device).

.PARAMETER LinkOnly
    Print only the link. send-task.ps1 -Pair uses this to mint its own session.

.EXAMPLE
    $env:HOPA_BOOTSTRAP_TOKEN = (Get-Content "$env:TEMP\hopa-bootstrap.txt" -Raw).Trim()
    .\tools\scripts\pair-device.ps1

.EXAMPLE
    .\tools\scripts\pair-device.ps1 -HouseholdId <uuid> -MemberName "Desk"
#>
[CmdletBinding()]
param(
    [string]$HouseholdName = "HOPA",
    [string]$MemberName = "Owner",
    [string]$HouseholdId,
    [switch]$LinkOnly
)

$ErrorActionPreference = "Stop"

function Stop-WithMessage([string]$Message) {
    [Console]::Error.WriteLine("pair-device: $Message")
    exit 1
}

# The refusal body is a JSON error with a code and a message. Only those two
# fields are read: an unexpected body is never echoed, so no secret can leak
# through a proxy's error page.
function Get-Refusal($ErrorRecord) {
    $exception = $ErrorRecord.Exception
    if ($exception -eq $null) {
        return "the request failed"
    }
    $response = $exception.Response
    if ($response -eq $null) {
        return $exception.Message
    }
    $status = [int]$response.StatusCode
    try {
        $reader = New-Object System.IO.StreamReader($response.GetResponseStream())
        $text = $reader.ReadToEnd()
        $reader.Close()
        $refusal = $text | ConvertFrom-Json
        if ($refusal.code) {
            return "HTTP $status $($refusal.code) $($refusal.message)"
        }
    } catch {
    }
    return "HTTP $status"
}

if (-not $env:HOPA_BOOTSTRAP_TOKEN) {
    Stop-WithMessage "HOPA_BOOTSTRAP_TOKEN is not set; export the operator bootstrap token first"
}

$base = "https://hopa-backend-test.onrender.com"
if ($env:HOPA_BACKEND_URL) {
    $base = $env:HOPA_BACKEND_URL.Trim().TrimEnd('/')
}

$body = [ordered]@{
    householdName = $HouseholdName
    memberName    = $MemberName
}
if ($HouseholdId) {
    $body.householdId = $HouseholdId
}

$request = @{
    Method      = "Post"
    Uri         = "$base/v1/pair/sessions"
    Headers     = @{ Authorization = "Bearer $($env:HOPA_BOOTSTRAP_TOKEN)" }
    ContentType = "application/json; charset=utf-8"
    Body        = ($body | ConvertTo-Json -Compress)
    TimeoutSec  = 30
}

try {
    $session = Invoke-RestMethod @request
} catch {
    Stop-WithMessage "could not mint a pairing session: $(Get-Refusal $_)"
}

if (-not $session.qr) {
    Stop-WithMessage "the backend returned no pairing link"
}
if ($LinkOnly) {
    Write-Output $session.qr
    exit 0
}

Write-Output $session.qr
Write-Output "expires=$($session.expiresAt) (single use, five minutes)"
exit 0
