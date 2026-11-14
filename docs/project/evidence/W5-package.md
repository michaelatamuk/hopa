# W5 — packaging and delivery

Date: 2026-11-14. Host: Windows; .NET 8 SDK; JDK 17 with `jmods`; WiX 5.0.2.

The client now ships as a self-contained folder, a zip and an MSI. The MSI
carries the .NET client, the Kotlin sidecar and a `jlink` runtime, so a machine
needs neither .NET nor Java installed.

## Artifacts

| File | Size | SHA-256 |
|---|---|---|
| `out/windows/hopa-windows-0.1.0.zip` | 107 MB | `9D22108BF30F92F17F900BC91141A96A1F171DDF4E1230114008603DED9F7886` |
| `out/windows/hopa-windows-0.1.0.msi` | 94 MB | `B57848C31B91157A1C7996002711B1A59E788257DEE61D2C920DB603D721D6BE` |

`out/` is ignored by git; `tools/scripts/windows-package.ps1` rebuilds both:

1. sidecar `installDist` and copy `lib/` into the stage;
2. `dotnet publish -c Release -r win-x64 --self-contained`;
3. `jlink` with `java.base`, `java.logging`, `java.net.http`, `java.desktop`,
   `java.management`, `java.naming`, `java.security.jgss`, `jdk.crypto.ec`,
   `jdk.crypto.cryptoki`, `jdk.unsupported`, `jdk.zipfs`;
4. zip the stage;
5. a WiX MSI with a Start-menu shortcut, version metadata and the licence page
   (`WixUI_InstallDir` + `WixUILicenseRtf`).

## Verified

- The **packaged** app runs from the stage with the bundled runtime and sidecar,
  no `JAVA_HOME` in play:

  ```
  out\windows\hopa-windows-0.1.0> Hopa.Windows.exe --turn "packaged check" --out out.txt
  sidecar: ready (protocol v1), conversations at C:\Users\...\.hopa\windows, runs=8
  sidecar: configured: bridge=off ... tone=warm
  HOPA: Sidecar answered: "packaged check".
  ```

  The device error in that log is the locked screen refusing honestly.
- The MSI passes an **administrative install** (`msiexec /a ... TARGETDIR=...`):
  the payload contains `Hopa.Windows.exe`, `runtime\bin\java.exe`,
  `sidecar\lib` and `LICENSE.rtf`.

## Not done

- **Code signing**: no certificate, so SmartScreen warns; the `uiAccess` helper
  for elevated windows is not built.
- **Clean-VM E2E**: the MSI installs per machine and needs an unlocked desktop
  and an administrator; the payload and the packaged run are proven, the install
  flow is not.
- **Windows CI smoke**: the job builds the client and runs the sidecar tests;
  a UI smoke needs a desktop, so it is not in CI yet.
- MSI V7 required accepting the Open Source Maintenance Fee EULA, so the build
  pins WiX **5.0.2** and the UI extension `5.0.2` (free).
