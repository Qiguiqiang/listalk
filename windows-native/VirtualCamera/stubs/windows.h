#pragma once
// STUB for the sandbox syntax pass ONLY — not a Windows SDK replacement.
// Declares just the Win32 surface the VirtualCamera sources use, so
// g++ -fsyntax-only can type-check the production code on Linux. The
// real build (CI, windows-latest) compiles against the actual SDK.
// Fidelity rule: signatures here mirror the SDK's; values of constants
// are plausible but never ship.

#include <cstdint>
#include <cstring>

#define __stdcall
#define __cdecl
#define __declspec(x)
#define WINAPI
#define CALLBACK
#define STDMETHODCALLTYPE
#define TRUE 1
#define FALSE 0

using HRESULT = int32_t;
using DWORD = uint32_t;
using ULONG = uint32_t;
using LONG = int32_t;
using INT32 = int32_t;
using UINT32 = uint32_t;
using UINT = uint32_t;
using BYTE = uint8_t;
using WORD = uint16_t;
using BOOL = int;
using LONGLONG = int64_t;
using HANDLE = void*;
using HMODULE = void*;
using LPVOID = void*;
using LPCWSTR = const wchar_t*;
using LPWSTR = wchar_t*;
using LSTATUS = LONG;
struct HKEY__;
using HKEY = HKEY__*;

struct GUID {
    uint32_t Data1;
    uint16_t Data2;
    uint16_t Data3;
    uint8_t Data4[8];
};
using REFGUID = const GUID&;
inline bool IsEqualGUID(REFGUID a, REFGUID b) {
    return std::memcmp(&a, &b, sizeof(GUID)) == 0;
}
static const GUID GUID_NULL = {0, 0, 0, {0, 0, 0, 0, 0, 0, 0, 0}};

#define SUCCEEDED(hr) (((HRESULT)(hr)) >= 0)
#define FAILED(hr) (((HRESULT)(hr)) < 0)
constexpr HRESULT S_OK = 0;
constexpr HRESULT S_FALSE = 1;
constexpr HRESULT E_FAIL = (HRESULT)0x80004005;
constexpr HRESULT E_POINTER = (HRESULT)0x80004003;
constexpr HRESULT E_INVALIDARG = (HRESULT)0x80070057;
constexpr HRESULT E_NOINTERFACE = (HRESULT)0x80004002;
constexpr HRESULT E_OUTOFMEMORY = (HRESULT)0x8007000E;
constexpr HRESULT E_UNEXPECTED = (HRESULT)0x8000FFFF;
#define HRESULT_FROM_WIN32(x) ((HRESULT)((x) == 0 ? 0 : (((x) & 0xFFFF) | 0x80070000)))

constexpr DWORD WAIT_OBJECT_0 = 0;
constexpr DWORD WAIT_TIMEOUT = 0x102;
constexpr DWORD INFINITE = 0xFFFFFFFF;
constexpr DWORD FILE_MAP_READ = 0x0004;
constexpr DWORD FILE_MAP_WRITE = 0x0002;
constexpr DWORD MAX_PATH = 260;
constexpr DWORD DLL_PROCESS_ATTACH = 1;
constexpr DWORD KEY_WRITE = 0x20006;
constexpr DWORD REG_SZ = 1;
constexpr DWORD ERROR_SUCCESS = 0;
constexpr DWORD ERROR_FILE_NOT_FOUND = 2;
#define HKEY_LOCAL_MACHINE ((HKEY)(uintptr_t)0x80000002)

HANDLE CreateEventW(void*, BOOL, BOOL, LPCWSTR);
BOOL ResetEvent(HANDLE);
BOOL SetEvent(HANDLE);
DWORD WaitForSingleObject(HANDLE, DWORD);
BOOL CloseHandle(HANDLE);
HANDLE OpenFileMappingW(DWORD, BOOL, LPCWSTR);
void* MapViewOfFile(HANDLE, DWORD, DWORD, DWORD, size_t);
BOOL UnmapViewOfFile(const void*);
DWORD GetModuleFileNameW(HMODULE, LPWSTR, DWORD);
BOOL DisableThreadLibraryCalls(HMODULE);
DWORD GetLastError();
LSTATUS RegCreateKeyExW(HKEY, LPCWSTR, DWORD, LPWSTR, DWORD, DWORD, void*, HKEY*, DWORD*);
LSTATUS RegSetValueExW(HKEY, LPCWSTR, DWORD, DWORD, const BYTE*, DWORD);
LSTATUS RegDeleteTreeW(HKEY, LPCWSTR);
LSTATUS RegCloseKey(HKEY);
