#pragma once
// STUB — see windows.h stub header note.
#include <windows.h>

using CLSID = GUID;
using IID = GUID;
using REFIID = const GUID&;
using REFCLSID = const GUID&;

struct IUnknown {
    virtual HRESULT QueryInterface(REFIID riid, void** ppv) = 0;
    virtual ULONG AddRef() = 0;
    virtual ULONG Release() = 0;
};

struct IClassFactory : IUnknown {
    virtual HRESULT CreateInstance(IUnknown* pUnkOuter, REFIID riid, void** ppv) = 0;
    virtual HRESULT LockServer(BOOL fLock) = 0;
};

static const GUID IID_IUnknown = {0x00000000, 0x0000, 0x0000, {0xc0, 0, 0, 0, 0, 0, 0, 0x46}};
static const GUID IID_IClassFactory = {0x00000001, 0x0000, 0x0000, {0xc0, 0, 0, 0, 0, 0, 0, 0x46}};

constexpr HRESULT CLASS_E_NOAGGREGATION = (HRESULT)0x80040110;
constexpr HRESULT CLASS_E_CLASSNOTAVAILABLE = (HRESULT)0x80040111;
