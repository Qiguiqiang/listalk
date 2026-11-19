#pragma once
// STUB — see windows.h stub header note. Signatures mirror the real
// mfvirtualcamera.h for the members this DLL calls (Start's
// IMFAsyncCallback* parameter is doc-verified on Microsoft Learn).
#include <windows.h>
#include <objbase.h>
#include "mfobjects.h"

enum MFVirtualCameraType { MFVirtualCameraType_SoftwareCameraSource = 0 };
enum MFVirtualCameraLifetime {
    MFVirtualCameraLifetime_Session = 0,
    MFVirtualCameraLifetime_System = 1
};
enum MFVirtualCameraAccess {
    MFVirtualCameraAccess_CurrentUser = 0,
    MFVirtualCameraAccess_AllUsers = 1
};

struct IMFMediaSource;

struct IMFVirtualCamera : IMFAttributes {
    virtual HRESULT AddDeviceSourceInfo(LPCWSTR deviceSymbolicLink) = 0;
    virtual HRESULT AddProperty(void* pProperty) = 0;
    virtual HRESULT AddRegistryEntry(void* pEntry) = 0;
    virtual HRESULT CreateSyncEvent(void* pEvent) = 0;
    virtual HRESULT CreateSyncSemaphore(void* pSemaphore) = 0;
    virtual HRESULT GetMediaSource(IMFMediaSource** ppMediaSource) = 0;
    virtual HRESULT Remove() = 0;
    virtual HRESULT SendCameraProperty(void* pProperty, void* pData) = 0;
    virtual HRESULT Shutdown() = 0;
    virtual HRESULT Start(IMFAsyncCallback* pCallback) = 0;
    virtual HRESULT Stop() = 0;
};

HRESULT MFCreateVirtualCamera(MFVirtualCameraType type,
                              MFVirtualCameraLifetime lifetime,
                              MFVirtualCameraAccess access,
                              LPCWSTR friendlyName, LPCWSTR sourceId,
                              const GUID* categories, ULONG categoryCount,
                              IMFVirtualCamera** virtualCamera);
