// SoundRelayVirtualCamera.dll — one DLL, two hosts:
//
//  1. The Windows Camera Frame Server loads it as a COM in-proc server
//     and activates MediaSource (CLSID below) whenever an application
//     opens the virtual camera. Registration (DllRegisterServer) is a
//     one-time, machine-wide step performed by the installer — the same
//     HKLM\Software\Classes\CLSID layout Microsoft's Windows-Camera
//     VirtualCamera sample uses — never by the app at runtime.
//
//  2. The SoundRelay app loads it for the flat SrVirtualCameraStart /
//     SrVirtualCameraStop API, which wraps MFCreateVirtualCamera
//     (session lifetime, current-user access: the call itself needs no
//     elevation) and holds the IMFVirtualCamera for the app's lifetime.

#include <atomic>
#include <cstring>
#include <cwchar>
#include <mutex>
#include <string>

#include <windows.h>
#include <mfapi.h>
#include <mfvirtualcamera.h>
#include <objbase.h>

#include "ComPtr.h"
#include "MediaSource.h"
#include "SharedFrameChannel.h"

// {7AD7CD52-9DC5-45A4-A3C6-F06F6206F8DA} — mirrors
// srvcam::kMediaSourceClsid (SharedFrameChannel.h).
static const GUID CLSID_SoundRelayVirtualCameraSource = {
    0x7ad7cd52, 0x9dc5, 0x45a4, {0xa3, 0xc6, 0xf0, 0x6f, 0x62, 0x06, 0xf8, 0xda}};

HMODULE g_hModule = nullptr;
static std::atomic<long> g_moduleRefs{0};
static std::atomic<long> g_serverLocks{0};

void ModuleAddRef() { ++g_moduleRefs; }
void ModuleRelease() { --g_moduleRefs; }

// ---- COM class factory ----

class ClassFactory final : public IClassFactory,
                           public ComObjectBase<ClassFactory> {
public:
    HRESULT STDMETHODCALLTYPE QueryInterface(REFIID riid, void** ppv) override {
        if (ppv == nullptr) return E_POINTER;
        if (IsEqualGUID(riid, IID_IUnknown) ||
            IsEqualGUID(riid, IID_IClassFactory)) {
            *ppv = static_cast<IClassFactory*>(this);
        } else {
            *ppv = nullptr;
            return E_NOINTERFACE;
        }
        AddRef();
        return S_OK;
    }
    ULONG STDMETHODCALLTYPE AddRef() override { return AddRefInternal(); }
    ULONG STDMETHODCALLTYPE Release() override { return ReleaseInternal(); }

    HRESULT STDMETHODCALLTYPE CreateInstance(IUnknown* pUnkOuter, REFIID riid,
                                             void** ppv) override {
        if (ppv == nullptr) return E_POINTER;
        *ppv = nullptr;
        if (pUnkOuter != nullptr) return CLASS_E_NOAGGREGATION;
        ComPtr<IMFMediaSource> source;
        HRESULT hr = MediaSource::CreateInstance(
            source.ReleaseAndGetAddressOf());
        if (FAILED(hr)) return hr;
        return source->QueryInterface(riid, ppv);
    }

    HRESULT STDMETHODCALLTYPE LockServer(BOOL fLock) override {
        if (fLock) {
            ++g_serverLocks;
            ModuleAddRef();
        } else {
            --g_serverLocks;
            ModuleRelease();
        }
        return S_OK;
    }
};

extern "C" __declspec(dllexport) HRESULT __stdcall DllGetClassObject(
    REFCLSID rclsid, REFIID riid, void** ppv) {
    if (ppv == nullptr) return E_POINTER;
    *ppv = nullptr;
    if (!IsEqualGUID(rclsid, CLSID_SoundRelayVirtualCameraSource))
        return CLASS_E_CLASSNOTAVAILABLE;
    ComPtr<ClassFactory> factory;
    factory.Reset(new ClassFactory());
    return factory->QueryInterface(riid, ppv);
}

extern "C" __declspec(dllexport) HRESULT __stdcall DllCanUnloadNow() {
    return (g_moduleRefs.load() == 0 && g_serverLocks.load() == 0) ? S_OK
                                                                  : S_FALSE;
}

// ---- registration (installer-invoked; mirrors the Microsoft sample) ----

static HRESULT WriteRegSz(HKEY key, const wchar_t* name, const wchar_t* value) {
    const DWORD bytes = static_cast<DWORD>((std::wcslen(value) + 1) * sizeof(wchar_t));
    const LSTATUS s = RegSetValueExW(key, name, 0, REG_SZ,
                                     reinterpret_cast<const BYTE*>(value), bytes);
    return s == ERROR_SUCCESS ? S_OK : HRESULT_FROM_WIN32(s);
}

extern "C" __declspec(dllexport) HRESULT __stdcall DllRegisterServer() {
    const std::wstring keyPath =
        std::wstring(L"Software\\Classes\\CLSID\\") + srvcam::kMediaSourceClsid;
    wchar_t modulePath[MAX_PATH] = {};
    if (GetModuleFileNameW(g_hModule, modulePath, MAX_PATH) == 0)
        return HRESULT_FROM_WIN32(GetLastError());

    HKEY key = nullptr;
    LSTATUS s = RegCreateKeyExW(HKEY_LOCAL_MACHINE, keyPath.c_str(), 0, nullptr,
                                0, KEY_WRITE, nullptr, &key, nullptr);
    if (s != ERROR_SUCCESS) return HRESULT_FROM_WIN32(s);
    HRESULT hr = WriteRegSz(key, nullptr, L"LisTalk Virtual Camera Source");
    HKEY sub = nullptr;
    if (SUCCEEDED(hr)) {
        s = RegCreateKeyExW(key, L"InprocServer32", 0, nullptr, 0, KEY_WRITE,
                            nullptr, &sub, nullptr);
        if (s != ERROR_SUCCESS) hr = HRESULT_FROM_WIN32(s);
    }
    if (sub != nullptr) {
        if (SUCCEEDED(hr)) hr = WriteRegSz(sub, nullptr, modulePath);
        if (SUCCEEDED(hr)) hr = WriteRegSz(sub, L"ThreadingModel", L"Both");
        RegCloseKey(sub);
    }
    RegCloseKey(key);
    return hr;
}

extern "C" __declspec(dllexport) HRESULT __stdcall DllUnregisterServer() {
    const std::wstring keyPath =
        std::wstring(L"Software\\Classes\\CLSID\\") + srvcam::kMediaSourceClsid;
    const LSTATUS s = RegDeleteTreeW(HKEY_LOCAL_MACHINE, keyPath.c_str());
    return (s == ERROR_SUCCESS || s == ERROR_FILE_NOT_FOUND)
               ? S_OK
               : HRESULT_FROM_WIN32(s);
}

// ---- flat API for the SoundRelay app ----

namespace {
std::mutex g_vcamLock;
IMFVirtualCamera* g_virtualCamera = nullptr;
bool g_mfStarted = false;
}  // namespace

// Create + register the virtual camera (session lifetime, current
// user). Idempotent while started. The camera stays enumerable until
// SrVirtualCameraStop (or process exit — session lifetime removes it).
extern "C" __declspec(dllexport) HRESULT __stdcall SrVirtualCameraStart(
    const wchar_t* friendlyName) {
    std::lock_guard<std::mutex> guard(g_vcamLock);
    if (g_virtualCamera != nullptr) return S_OK;
    if (friendlyName == nullptr) return E_INVALIDARG;

    HRESULT hr = MFStartup(MF_VERSION, MFSTARTUP_LITE);
    if (FAILED(hr)) return hr;
    g_mfStarted = true;

    IMFVirtualCamera* camera = nullptr;
    hr = MFCreateVirtualCamera(
        MFVirtualCameraType_SoftwareCameraSource,
        MFVirtualCameraLifetime_Session, MFVirtualCameraAccess_CurrentUser,
        friendlyName, srvcam::kMediaSourceClsid,
        nullptr,  // categories: defaults (VIDEO_CAMERA/VIDEO/CAPTURE)
        0, &camera);
    if (SUCCEEDED(hr) && camera != nullptr) hr = camera->Start(nullptr);
    if (FAILED(hr) || camera == nullptr) {
        if (camera != nullptr) {
            camera->Shutdown();
            camera->Release();
        }
        MFShutdown();
        g_mfStarted = false;
        return FAILED(hr) ? hr : E_FAIL;
    }
    g_virtualCamera = camera;
    return S_OK;
}

// Disable + unregister the virtual camera and release everything.
extern "C" __declspec(dllexport) HRESULT __stdcall SrVirtualCameraStop() {
    std::lock_guard<std::mutex> guard(g_vcamLock);
    if (g_virtualCamera != nullptr) {
        IMFVirtualCamera* camera = g_virtualCamera;
        g_virtualCamera = nullptr;
        camera->Stop();
        camera->Remove();
        camera->Shutdown();
        camera->Release();
    }
    if (g_mfStarted) {
        MFShutdown();
        g_mfStarted = false;
    }
    return S_OK;
}

BOOL WINAPI DllMain(HMODULE hModule, DWORD reason, void* /*reserved*/) {
    if (reason == DLL_PROCESS_ATTACH) {
        g_hModule = hModule;
        DisableThreadLibraryCalls(hModule);
    }
    return TRUE;
}
