# devnode.exe — SetupAPI device-node helper

A small, self-contained Windows console program (`devnode.cpp`, single file,
no dependencies beyond `setupapi.lib` / `newdev.lib` / `cfgmgr32.lib`) that
creates, removes, and checks the root-enumerated device node for the
LisTalk/SoundRelay virtual audio driver (`ROOT\SimpleAudioSample`,
`SimpleAudioSample.inf`).

## Why it exists

The one-click installer previously did this with the WDK's `devcon.exe`.
devcon ships **only** with the WDK, which normal user machines don't have —
so on those machines the device-node step silently never happened and the
virtual audio device was never created. This helper reimplements exactly the
three devcon verbs the installer uses, against the public SetupAPI / NewDev /
CfgMgr32 APIs, so it can ship inside the driver package itself.

## Usage

```
devnode install <InfPath> <HardwareId>
devnode remove  <HardwareId>
devnode status  <HardwareId>
```

These are drop-in replacements for the corresponding `devcon` calls:

- **install** — reads the class GUID from the INF (`SetupDiGetINFClass`),
  creates and registers a device node carrying the hardware id
  (`SetupDiCreateDeviceInfo` with `DICD_GENERATE_ID`,
  `SPDRP_HARDWAREID`, `DIF_REGISTERDEVICE`), then binds the driver with
  `UpdateDriverForPlugAndPlayDevices` (`INSTALLFLAG_FORCE`). If Windows
  flags a reboot, it prints a line containing `reboot required` but still
  exits 0.
- **remove** — enumerates all devices (all classes, including non-present
  phantoms), matches `SPDRP_HARDWAREID` case-insensitively, and removes each
  match with `DIF_REMOVE`. Prints how many were removed, or
  `No matching devices found`.
- **status** — finds the first *present* matching device and checks
  `CM_Get_DevNode_Status`. On success it prints a line containing the exact
  phrase `Driver is running` (the installer `.bat` greps for it, inherited
  from devcon's output); otherwise it prints the CM_PROB problem code.

### Exit codes

| Code | install | remove | status |
|------|---------|--------|--------|
| 0 | OK (reboot may still be required) | OK (0 removed is OK) | Driver is running |
| 1 | Usage error | — | No matching device found |
| 2 | Device-node creation failed | — | Device found but not running |
| 3 | Driver bind failed (`GetLastError` printed) | — | — |
| 4 | — | A `DIF_REMOVE` call failed | — |

(Usage errors exit 1 for all subcommands.)

## How it gets built

- **Normal path:** `driver/build-and-sign.bat` compiles `devnode.cpp` as one
  of its steps and copies `devnode.exe` into `driver/build-output/` alongside
  the `.inf` / `.sys` / `.cat`. If the helper fails to compile, the whole
  build fails — shipping a package without the helper is exactly the bug this
  tool fixes.
- **Standalone:** run `build-devnode.bat` in this folder (VS2022 via
  vswhere + `VsDevCmd.bat -arch=x64`, then a single `cl` invocation). It
  produces `devnode.exe` next to the script.
