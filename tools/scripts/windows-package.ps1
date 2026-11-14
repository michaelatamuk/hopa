param(
    [string]$Output = "out/windows",
    [switch]$SkipMsi
)

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$out = Join-Path $root $Output
$stage = Join-Path $out "hopa-windows-0.1.0"
$sidecar = Join-Path $root "apps/windows/sidecar"
$client = Join-Path $root "apps/windows/Hopa.Windows/Hopa.Windows.csproj"
$jdk = $env:JAVA_HOME

if (Test-Path $stage) { Remove-Item $stage -Recurse -Force }
New-Item -ItemType Directory -Path $stage | Out-Null

Write-Host "1/5 sidecar"
& (Join-Path $root "apps/android/gradlew.bat") -p $sidecar installDist --no-daemon
if ($LASTEXITCODE -ne 0) { throw "installDist failed" }
New-Item -ItemType Directory -Path (Join-Path $stage "sidecar") -Force | Out-Null
Copy-Item (Join-Path $sidecar "build/install/hopa-windows-sidecar/lib") (Join-Path $stage "sidecar/lib") -Recurse -Force
Copy-Item (Join-Path $root "LICENSE") (Join-Path $stage "LICENSE.txt") -Force

Write-Host "2/5 publish"
dotnet publish $client -c Release -r win-x64 --self-contained -o $stage
if ($LASTEXITCODE -ne 0) { throw "dotnet publish failed" }

Write-Host "3/5 jlink runtime"
& (Join-Path $jdk "bin/jlink.exe") --add-modules java.base,java.logging,java.net.http,java.desktop,java.management,java.naming,java.security.jgss,jdk.crypto.ec,jdk.crypto.cryptoki,jdk.unsupported,jdk.zipfs --strip-debug --no-header-files --no-man-pages --compress=2 --output (Join-Path $stage "runtime")
if ($LASTEXITCODE -ne 0) { throw "jlink failed" }

Write-Host "4/5 zip"
$zip = Join-Path $out "hopa-windows-0.1.0.zip"
if (Test-Path $zip) { Remove-Item $zip -Force }
Compress-Archive -Path (Join-Path $stage "*") -DestinationPath $zip -Force
$hash = (Get-FileHash $zip -Algorithm SHA256).Hash
Write-Host "zip: $zip"
Write-Host "sha256: $hash"

Write-Host "5/5 msi"
if ($SkipMsi) {
    Write-Host "msi skipped"
    return
}

$wix = Get-Command wix -ErrorAction SilentlyContinue
if ($null -eq $wix) {
    Write-Warning "WiX is not installed; install it with: dotnet tool install --global wix"
    return
}

$msi = Join-Path $out "hopa-windows-0.1.0.msi"
$wxs = Join-Path $root "apps/windows/installer/Hopa.wxs"

$license = Get-Content (Join-Path $root "LICENSE") -Raw
$escaped = $license.Replace('\', '\\').Replace('{', '\{').Replace('}', '\}')
$rtf = "{\rtf1\ansi\deff0{\fonttbl{\f0 Segoe UI;}}\f0\fs18" + (($escaped -split "`r?`n") -join '\par ') + "}"
$licenseRtf = Join-Path $stage "LICENSE.rtf"
Set-Content -Path $licenseRtf -Value $rtf -Encoding ascii

& wix build -ext WixToolset.UI.wixext -d SourceDir="$stage" -d LicenseRtf="$licenseRtf" -o $msi $wxs
if ($LASTEXITCODE -ne 0) { throw "wix build failed" }
Write-Host ("msi: {0}" -f $msi)
Write-Host ("msi sha256: {0}" -f (Get-FileHash $msi -Algorithm SHA256).Hash)
