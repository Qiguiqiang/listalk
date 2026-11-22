// devnode.exe - tiny SetupAPI helper that replaces the WDK devcon.exe calls
// used by the LisTalk/SoundRelay driver installer.
//
// Why this exists: devcon.exe ships only with the WDK, so on a normal user
// machine the installer's device-node step silently never ran and the
// virtual audio device was never created. This helper reimplements exactly
// the three devcon verbs the installer uses (install / remove / status)
// against the public SetupAPI / NewDev / CfgMgr32 APIs, so it can ship
// inside the driver package itself with no WDK on the target machine.
//
// Exit codes (mirrored in README.md):
//   0  success (install may still print "reboot required")
//   1  usage error, or (status) no matching device found
//   2  install: device-node creation failed / status: device not running
//   3  install: driver bind (UpdateDriverForPlugAndPlayDevices) failed
//   4  remove: a DIF_REMOVE call failed

#include <windows.h>
#include <setupapi.h>
#include <newdev.h>
#include <cfgmgr32.h>

#include <cstdio>
#include <cstring>
#include <string>
#include <vector>

// The libraries are pulled in here so the build stays a single cl command
// line with no project file; the pragma is MSVC-specific, hence the guard
// (other compilers must name the libraries on their command line instead).
#if defined(_MSC_VER)
#pragma comment(lib, "setupapi.lib")
#pragma comment(lib, "newdev.lib")
#pragma comment(lib, "cfgmgr32.lib")
#endif

namespace {

// Owns an HDEVINFO so every exit path destroys the device info list; a
// leaked list is harmless in a short-lived process, but a leaked list in
// the remove loop would also keep removed devices' data alive confusingly.
struct DevInfoList {
    HDEVINFO handle = INVALID_HANDLE_VALUE;
    ~DevInfoList() {
        if (handle != INVALID_HANDLE_VALUE) {
            SetupDiDestroyDeviceInfoList(handle);
        }
    }
    DevInfoList() = default;
    DevInfoList(const DevInfoList&) = delete;
    DevInfoList& operator=(const DevInfoList&) = delete;
};

void PrintLastError(const wchar_t* step) {
    wprintf(L"%s failed (GetLastError=%lu)\n", step,
            static_cast<unsigned long>(GetLastError()));
}

// Builds a REG_MULTI_SZ buffer for one hardware id: "<id>\0\0". The double
// null matters: SPDRP_HARDWAREID is a multi-string, and a single trailing
// null makes the property malformed, which can silently break matching in
// later remove/status enumerations.
std::vector<WCHAR> MakeMultiSz(const wchar_t* value) {
    const size_t len = wcslen(value);
    std::vector<WCHAR> buf(len + 2, L'\0');
    memcpy(buf.data(), value, len * sizeof(WCHAR));
    return buf;
}

// True when any entry of the device's SPDRP_HARDWAREID multi-string equals
// the wanted id. Comparison is case-insensitive because the case stored in
// the registry is whatever the INF / creator used, not necessarily the
// case the caller typed (devcon matches the same way).
bool HardwareIdMatches(HDEVINFO set, PSP_DEVINFO_DATA data,
                       const wchar_t* wanted) {
    DWORD required = 0;
    SetupDiGetDeviceRegistryPropertyW(set, data, SPDRP_HARDWAREID, nullptr,
                                      nullptr, 0, &required);
    if (required == 0) {
        return false;
    }
    std::vector<BYTE> buf(required);
    DWORD type = 0;
    if (!SetupDiGetDeviceRegistryPropertyW(
            set, data, SPDRP_HARDWAREID, &type,
            buf.data(), static_cast<DWORD>(buf.size()), nullptr)) {
        return false;
    }
    if (type != REG_MULTI_SZ) {
        return false;
    }
    const WCHAR* p = reinterpret_cast<const WCHAR*>(buf.data());
    const WCHAR* end = reinterpret_cast<const WCHAR*>(buf.data() + buf.size());
    while (p < end && *p != L'\0') {
        if (_wcsicmp(p, wanted) == 0) {
            return true;
        }
        p += wcslen(p) + 1;
    }
    return false;
}

// Enumerates devices with the given SetupDi flags and collects copies of
// the SP_DEVINFO_DATA of every device whose hardware id matches. Matches
// are collected before any removal: calling DIF_REMOVE while walking the
// enumeration by index would shift the remaining entries and skip some.
bool CollectMatches(DWORD flags, const wchar_t* hwid,
                    DevInfoList& outSet,
                    std::vector<SP_DEVINFO_DATA>& outMatches) {
    outSet.handle = SetupDiGetClassDevsW(nullptr, nullptr, nullptr, flags);
    if (outSet.handle == INVALID_HANDLE_VALUE) {
        PrintLastError(L"SetupDiGetClassDevs");
        return false;
    }
    for (DWORD i = 0;; ++i) {
        SP_DEVINFO_DATA data = {};
        data.cbSize = sizeof(data);
        if (!SetupDiEnumDeviceInfo(outSet.handle, i, &data)) {
            break;  // ERROR_NO_MORE_ITEMS: end of the enumeration.
        }
        if (HardwareIdMatches(outSet.handle, &data, hwid)) {
            outMatches.push_back(data);
        }
    }
    return true;
}

// ---------------------------------------------------------------------------
// install <InfPath> <HardwareId>
// devcon semantics: create a root-enumerated device node carrying the
// hardware id, register it, then let PnP bind the driver from the INF.
// ---------------------------------------------------------------------------
int DoInstall(const wchar_t* infPath, const wchar_t* hwid) {
    // The class GUID must come from the INF itself: creating the node under
    // a guessed class would register the device in the wrong device class.
    GUID classGuid = {};
    WCHAR className[MAX_CLASS_NAME_LEN] = {};
    if (!SetupDiGetINFClassW(infPath, &classGuid, className,
                             ARRAYSIZE(className), nullptr)) {
        PrintLastError(L"SetupDiGetINFClass");
        return 2;
    }
    wprintf(L"INF class: %s\n", className);

    DevInfoList set;
    set.handle = SetupDiCreateDeviceInfoList(&classGuid, nullptr);
    if (set.handle == INVALID_HANDLE_VALUE) {
        PrintLastError(L"SetupDiCreateDeviceInfoList");
        return 2;
    }

    SP_DEVINFO_DATA data = {};
    data.cbSize = sizeof(data);
    // devcon passes the hardware id as the device name and lets
    // DICD_GENERATE_ID mint a unique instance id (ROOT\<...>\<n>) for it.
    if (!SetupDiCreateDeviceInfoW(set.handle, hwid, &classGuid, nullptr,
                                  nullptr, DICD_GENERATE_ID, &data)) {
        PrintLastError(L"SetupDiCreateDeviceInfo");
        return 2;
    }
    wprintf(L"Device info created.\n");

    // Without the hardware id on the node, PnP has nothing to match the
    // INF's Models section against and the driver will never bind.
    const std::vector<WCHAR> multiSz = MakeMultiSz(hwid);
    if (!SetupDiSetDeviceRegistryPropertyW(
            set.handle, &data, SPDRP_HARDWAREID,
            reinterpret_cast<const BYTE*>(multiSz.data()),
            static_cast<DWORD>(multiSz.size() * sizeof(WCHAR)))) {
        PrintLastError(L"SetupDiSetDeviceRegistryProperty(SPDRP_HARDWAREID)");
        return 2;
    }
    wprintf(L"Hardware id set: %s\n", hwid);

    if (!SetupDiCallClassInstaller(DIF_REGISTERDEVICE, set.handle, &data)) {
        PrintLastError(L"SetupDiCallClassInstaller(DIF_REGISTERDEVICE)");
        return 2;
    }
    wprintf(L"Device node registered.\n");

    // INSTALLFLAG_FORCE because a stale / worse-ranked driver may already
    // be bound to this hardware id from an earlier partial install; devcon
    // install behaves the same way (it does not accept "already installed"
    // as success when the INF given differs).
    BOOL reboot = FALSE;
    if (!UpdateDriverForPlugAndPlayDevicesW(nullptr, hwid, infPath,
                                            INSTALLFLAG_FORCE, &reboot)) {
        PrintLastError(L"UpdateDriverForPlugAndPlayDevices");
        return 3;
    }
    wprintf(L"Driver installed for %s.\n", hwid);
    if (reboot) {
        // Not a failure: the device works after the next restart, and the
        // caller (a .bat) treats a nonzero exit as a hard install error.
        wprintf(L"reboot required\n");
    }
    return 0;
}

// ---------------------------------------------------------------------------
// remove <HardwareId>
// Enumerates ALL classes (including non-present devices): a leftover
// phantom node would make the next install bind to the stale node, which
// is exactly why the installer removes before it installs.
// ---------------------------------------------------------------------------
int DoRemove(const wchar_t* hwid) {
    DevInfoList set;
    std::vector<SP_DEVINFO_DATA> matches;
    if (!CollectMatches(DIGCF_ALLCLASSES, hwid, set, matches)) {
        return 4;
    }
    if (matches.empty()) {
        wprintf(L"No matching devices found\n");
        return 0;
    }
    int removed = 0;
    bool failed = false;
    for (SP_DEVINFO_DATA& data : matches) {
        if (SetupDiCallClassInstaller(DIF_REMOVE, set.handle, &data)) {
            ++removed;
        } else {
            PrintLastError(L"SetupDiCallClassInstaller(DIF_REMOVE)");
            failed = true;
        }
    }
    wprintf(L"Removed %d device(s).\n", removed);
    return failed ? 4 : 0;
}

// ---------------------------------------------------------------------------
// status <HardwareId>
// Only PRESENT devices count here: a phantom node is not a working device.
// The success line intentionally contains "Driver is running" verbatim -
// the installer's .bat greps the output for that exact phrase (inherited
// from devcon's status output).
// ---------------------------------------------------------------------------
int DoStatus(const wchar_t* hwid) {
    DevInfoList set;
    std::vector<SP_DEVINFO_DATA> matches;
    if (!CollectMatches(DIGCF_ALLCLASSES | DIGCF_PRESENT, hwid, set,
                        matches)) {
        return 1;
    }
    if (matches.empty()) {
        wprintf(L"No matching devices found\n");
        return 1;
    }
    ULONG status = 0;
    ULONG problem = 0;
    const CONFIGRET cr = CM_Get_DevNode_Status(&status, &problem,
                                               matches[0].DevInst, 0);
    if (cr != CR_SUCCESS) {
        wprintf(L"CM_Get_DevNode_Status failed (CONFIGRET=%lu)\n",
                static_cast<unsigned long>(cr));
        return 2;
    }
    if ((status & DN_STARTED) != 0 && problem == 0) {
        wprintf(L"Driver is running\n");
        return 0;
    }
    wprintf(L"Device is not running (CM_PROB problem code=%lu, status=0x%08lX)\n",
            static_cast<unsigned long>(problem),
            static_cast<unsigned long>(status));
    return 2;
}

void PrintUsage() {
    wprintf(L"Usage:\n");
    wprintf(L"  devnode install <InfPath> <HardwareId>\n");
    wprintf(L"  devnode remove  <HardwareId>\n");
    wprintf(L"  devnode status  <HardwareId>\n");
}

}  // namespace

int wmain(int argc, wchar_t* argv[]) {
    if (argc >= 2) {
        if (_wcsicmp(argv[1], L"install") == 0 && argc == 4) {
            return DoInstall(argv[2], argv[3]);
        }
        if (_wcsicmp(argv[1], L"remove") == 0 && argc == 3) {
            return DoRemove(argv[2]);
        }
        if (_wcsicmp(argv[1], L"status") == 0 && argc == 3) {
            return DoStatus(argv[2]);
        }
    }
    PrintUsage();
    return 1;
}
