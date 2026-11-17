<#
.SYNOPSIS
    Send one task from the PC to a paired phone.

.DESCRIPTION
    -Pair claims a fresh pairing session as a windows device, keeps the tokens
    in %TEMP%\hopa-pc-tokens.json, says hello, and records the phone's device id
    when it is the only other device online.

    Without -Pair the script reads that file, connects to the gateway, sends one
    task.create to the phone, and prints the ack, the task.result or task.error,
    and the traceparent. Tokens live in %TEMP% only and are never printed.

    The sender is SendTask.java, compiled on first use into %TEMP%\hopa-tools\
    with the JDK already on PATH; there is nothing to install.

.PARAMETER Goal
    What the phone should do, for example "open settings". Required unless -Pair.

.PARAMETER To
    The phone's device id. Defaults to the id -Pair discovered, then to the only
    other device online in the household.

.PARAMETER Pair
    Claim a pairing session as a windows device and stop. Requires
    HOPA_BOOTSTRAP_TOKEN for the minted link. Creates a new household unless
    -HouseholdId names the phone's household.

.PARAMETER Link
    Use this fresh hopa://pair link instead of minting one. Only with -Pair.

.PARAMETER HouseholdId
    Join this existing household when -Pair mints its own session. Use the
    phone's household id (the h= value in the link pair-device.ps1 printed) so
    both devices can exchange tasks. Ignored with -Link.

.PARAMETER Name
    Device name shown in the household. Defaults to the computer name.

.PARAMETER Force
    Pair again even when a token file already exists. Without -HouseholdId or
    -Link, the new session joins the household already stored, so the phone
    stays reachable.

.PARAMETER TimeoutSec
    How long to wait for task.result or task.error. Defaults to 200 seconds.

.EXAMPLE
    .\tools\scripts\send-task.ps1 -Pair

.EXAMPLE
    .\tools\scripts\send-task.ps1 -Pair -HouseholdId <phone household>

.EXAMPLE
    .\tools\scripts\send-task.ps1 -Goal "open settings" -To <phone device id>
#>
[CmdletBinding()]
param(
    [string]$Goal,
    [string]$To,
    [switch]$Pair,
    [string]$Link,
    [string]$HouseholdId,
    [string]$Name,
    [switch]$Force,
    [int]$TimeoutSec = 200
)

$ErrorActionPreference = "Stop"

$tokensPath = Join-Path $env:TEMP "hopa-pc-tokens.json"
$toolsDir = Join-Path $env:TEMP "hopa-tools"
$sourcePath = Join-Path $PSScriptRoot "SendTask.java"
$classPath = Join-Path $toolsDir "SendTask.class"
$java = $null

function Stop-WithMessage([string]$Message, [int]$Code = 1) {
    [Console]::Error.WriteLine("send-task: $Message")
    exit $Code
}

function Get-BackendBase {
    if ($env:HOPA_BACKEND_URL) {
        return $env:HOPA_BACKEND_URL.Trim().TrimEnd('/')
    }
    return "https://hopa-backend-test.onrender.com"
}

function Read-Tokens([string]$Path) {
    if (-not (Test-Path -LiteralPath $Path)) {
        return $null
    }
    try {
        return Get-Content -LiteralPath $Path -Raw -ErrorAction Stop | ConvertFrom-Json
    } catch {
        return $null
    }
}

# The token file holds bearer secrets. Remove inherited access and leave the
# current user read/write, the Windows equivalent of chmod 600. A filesystem
# that refuses the ACL (a FAT USB stick, a container mount) is reported by
# icacls but does not stop the send.
function Protect-TokenFile([string]$Path) {
    if (-not (Test-Path -LiteralPath $Path)) {
        return
    }
    try {
        $who = [System.Security.Principal.WindowsIdentity]::GetCurrent().Name
        & icacls $Path /inheritance:r /grant:r "$($who):(R,W)" 2>&1 | Out-Null
    } catch {
    }
}

function Build-Sender {
    if (-not (Test-Path -LiteralPath $sourcePath)) {
        Stop-WithMessage "SendTask.java is missing next to this script"
    }
    $javaCommand = Get-Command java -ErrorAction SilentlyContinue
    if (-not $javaCommand) {
        Stop-WithMessage "java was not found; install JDK 21 and put it on PATH"
    }
    $script:java = $javaCommand.Source

    $needsBuild = -not (Test-Path -LiteralPath $classPath)
    if (-not $needsBuild) {
        $needsBuild = (Get-Item -LiteralPath $sourcePath).LastWriteTimeUtc -gt
            (Get-Item -LiteralPath $classPath).LastWriteTimeUtc
    }
    if (-not $needsBuild) {
        return
    }
    $javac = Get-Command javac -ErrorAction SilentlyContinue
    if (-not $javac) {
        Stop-WithMessage "javac was not found; install JDK 21 and put it on PATH"
    }
    New-Item -ItemType Directory -Force -Path $toolsDir | Out-Null
    $compile = & $javac.Source -encoding UTF-8 -d $toolsDir $sourcePath 2>&1
    if ($LASTEXITCODE -ne 0) {
        foreach ($line in @($compile)) {
            [Console]::Error.WriteLine("send-task: javac: $line")
        }
        Stop-WithMessage "could not compile SendTask.java"
    }
}

if (-not $Name -or -not $Name.Trim()) {
    $Name = if ($env:COMPUTERNAME) { $env:COMPUTERNAME } else { "HOPA-PC" }
}

if ($Pair) {
    $existing = Read-Tokens $tokensPath
    $existingHousehold = ""
    if ($existing -and $existing.householdId) {
        $existingHousehold = [string]$existing.householdId
    }
    # A pasted link or an explicit household is a request to pair now, even
    # though a device is already stored. Silently keeping the old identity
    # would make the fresh link look consumed when it was never used.
    $explicitPairing = ($Link -and $Link.Trim()) -or ($HouseholdId -and $HouseholdId.Trim())

    if ($existing -and $existing.accessToken -and -not $Force) {
        if (-not $explicitPairing) {
            $keeps = if ($existingHousehold) { " (keeps household $existingHousehold)" } else { "" }
            Write-Output "already paired windows device $($existing.deviceId); run send-task.ps1 -Pair -Force to pair again$keeps"
            exit 0
        }
        Write-Output "re-pairing windows device $($existing.deviceId) from the explicit request"
    }

    $pairLink = $Link
    if (-not $pairLink) {
        $mint = Join-Path $PSScriptRoot "pair-device.ps1"
        if (-not (Test-Path -LiteralPath $mint)) {
            Stop-WithMessage "pair-device.ps1 is missing next to this script"
        }
        # Re-pairing must not move the PC to a fresh household: the phone is
        # still in the old one. -HouseholdId or -Link overrides this default.
        $joinHousehold = $HouseholdId
        if (-not $joinHousehold -and $existingHousehold) {
            $joinHousehold = $existingHousehold
        }
        $mintArgs = @("-LinkOnly")
        if ($joinHousehold) {
            $mintArgs += @("-HouseholdId", $joinHousehold.Trim())
        }
        $minted = & $mint @mintArgs
        if ($LASTEXITCODE -ne 0) {
            Stop-WithMessage "could not mint a pairing session"
        }
        $pairLink = @($minted) |
            Where-Object { $_ -match "^hopa://pair" } |
            Select-Object -First 1
    }
    if (-not $pairLink) {
        Stop-WithMessage "no pairing link; pair-device.ps1 printed none"
    }

    Build-Sender
    $pairArgs = @(
        "pair",
        "--base", (Get-BackendBase),
        "--link", $pairLink,
        "--name", $Name,
        "--out", $tokensPath
    )
    & $java -cp $toolsDir SendTask @pairArgs
    if ($LASTEXITCODE -ne 0) {
        exit $LASTEXITCODE
    }
    Protect-TokenFile $tokensPath
    exit 0
}

$tokens = Read-Tokens $tokensPath
if (-not $tokens -or -not $tokens.accessToken -or -not $tokens.gatewayUrl) {
    [Console]::Error.WriteLine("no paired windows device; run send-task.ps1 -Pair first")
    exit 2
}
if (-not $Goal -or -not $Goal.Trim()) {
    Stop-WithMessage "-Goal is required, for example -Goal `"open settings`""
}
if (-not $To -and $tokens.to) {
    $To = [string]$tokens.to
}

Build-Sender
$sendArgs = @(
    "send",
    "--tokens", $tokensPath,
    "--goal", $Goal,
    "--timeout", "$TimeoutSec"
)
if ($To) {
    $sendArgs += @("--to", $To)
}
& $java -cp $toolsDir SendTask @sendArgs
exit $LASTEXITCODE
