#pragma once
// STUB — see windows.h stub header note.
#include <windows.h>
#include <objbase.h>

using UINT64 = uint64_t;

struct PROPVARIANT {
    uint16_t vt = 0;
    uint16_t wReserved1 = 0, wReserved2 = 0, wReserved3 = 0;
    union {
        LONGLONG llVal;
        IUnknown* punkVal;
    };
};

using MediaEventType = uint32_t;
constexpr MediaEventType MEUnknown = 0;
constexpr MediaEventType MEError = 1;
constexpr MediaEventType MEExtendedType = 2;
constexpr MediaEventType MENewStream = 102;
constexpr MediaEventType MEUpdatedStream = 103;
constexpr MediaEventType MESourceStarted = 104;
constexpr MediaEventType MESourceStopped = 105;
constexpr MediaEventType MEMediaSample = 106;
constexpr MediaEventType MEStreamStarted = 112;
constexpr MediaEventType MEStreamStopped = 113;
constexpr MediaEventType MEStreamFormatChanged = 305;

using MFVideoInterlaceMode = uint32_t;
constexpr MFVideoInterlaceMode MFVideoInterlace_Progressive = 2;

static const GUID MFMediaType_Video = {0x73646976, 0x0000, 0x0010, {0x80, 0, 0, 0xaa, 0, 0x38, 0x9b, 0x71}};
static const GUID MFVideoFormat_RGB32 = {0x00000016, 0x0000, 0x0010, {0x80, 0, 0, 0xaa, 0, 0x38, 0x9b, 0x71}};
static const GUID MFSampleExtension_Token = {0x8294da66, 0xf7f4, 0x47a7, {0xbf, 0xb3, 0x6a, 0xcf, 0x54, 0x2e, 0x2e, 0x2e}};

struct IMFMediaEvent : IUnknown {
    virtual HRESULT GetType(MediaEventType* pType) = 0;
};

struct IMFAsyncResult : IUnknown {
    virtual HRESULT GetState(IUnknown** ppunkState) = 0;
    virtual HRESULT GetStatus() = 0;
    virtual HRESULT GetObject(IUnknown** ppObject) = 0;
};

struct IMFAsyncCallback : IUnknown {
    virtual HRESULT GetParameters(DWORD* pdwFlags, DWORD* pdwQueue) = 0;
    virtual HRESULT Invoke(IMFAsyncResult* pAsyncResult) = 0;
};

struct IMFAttributes : IUnknown {
    virtual HRESULT GetItem(REFGUID guidKey, PROPVARIANT* pValue) = 0;
    virtual HRESULT GetUINT32(REFGUID guidKey, UINT32* punValue) = 0;
    virtual HRESULT GetUINT64(REFGUID guidKey, UINT64* punValue) = 0;
    virtual HRESULT GetGUID(REFGUID guidKey, GUID* pguidValue) = 0;
    virtual HRESULT SetItem(REFGUID guidKey, const PROPVARIANT& value) = 0;
    virtual HRESULT SetUINT32(REFGUID guidKey, UINT32 unValue) = 0;
    virtual HRESULT SetUINT64(REFGUID guidKey, UINT64 unValue) = 0;
    virtual HRESULT SetGUID(REFGUID guidKey, REFGUID guidValue) = 0;
    virtual HRESULT SetUnknown(REFGUID guidKey, IUnknown* pUnknown) = 0;
};

struct IMFMediaEventQueue : IUnknown {
    virtual HRESULT GetEvent(DWORD dwFlags, IMFMediaEvent** ppEvent) = 0;
    virtual HRESULT BeginGetEvent(IMFAsyncCallback* pCallback, IUnknown* punkState) = 0;
    virtual HRESULT EndGetEvent(IMFAsyncResult* pResult, IMFMediaEvent** ppEvent) = 0;
    virtual HRESULT QueueEvent(MediaEventType met, const GUID* pguidExtendedType, HRESULT hrStatus, const PROPVARIANT* pvValue) = 0;
    virtual HRESULT QueueEventParamVar(MediaEventType met, REFGUID guidExtendedType, HRESULT hrStatus, const PROPVARIANT* pvValue) = 0;
    virtual HRESULT QueueEventParamUnk(MediaEventType met, REFGUID guidExtendedType, HRESULT hrStatus, IUnknown* pUnk) = 0;
    virtual HRESULT Shutdown() = 0;
};

struct IMFMediaEventGenerator : IUnknown {
    virtual HRESULT GetEvent(DWORD dwFlags, IMFMediaEvent** ppEvent) = 0;
    virtual HRESULT BeginGetEvent(IMFAsyncCallback* pCallback, IUnknown* punkState) = 0;
    virtual HRESULT EndGetEvent(IMFAsyncResult* pResult, IMFMediaEvent** ppEvent) = 0;
    virtual HRESULT QueueEvent(MediaEventType met, const GUID* pguidExtendedType, HRESULT hrStatus, const PROPVARIANT* pvValue) = 0;
};

struct IMFMediaBuffer : IUnknown {
    virtual HRESULT Lock(BYTE** ppbBuffer, DWORD* pcbMaxLength, DWORD* pcbCurrentLength) = 0;
    virtual HRESULT Unlock() = 0;
    virtual HRESULT GetCurrentLength(DWORD* pcbCurrentLength) = 0;
    virtual HRESULT SetCurrentLength(DWORD cbCurrentLength) = 0;
    virtual HRESULT GetMaxLength(DWORD* pcbMaxLength) = 0;
};

struct IMFSample : IMFAttributes {
    virtual HRESULT AddBuffer(IMFMediaBuffer* pBuffer) = 0;
    virtual HRESULT SetSampleTime(LONGLONG hnsSampleTime) = 0;
    virtual HRESULT SetSampleDuration(LONGLONG hnsSampleDuration) = 0;
    virtual HRESULT GetSampleTime(LONGLONG* phnsSampleTime) = 0;
};

struct IMFMediaType : IMFAttributes {};

struct IMFMediaTypeHandler : IUnknown {
    virtual HRESULT IsMediaTypeSupported(IMFMediaType* pMediaType, IMFMediaType** ppMediaType) = 0;
    virtual HRESULT GetMediaTypeCount(DWORD* pdwTypeCount) = 0;
    virtual HRESULT GetMediaTypeByIndex(DWORD dwIndex, IMFMediaType** ppType) = 0;
    virtual HRESULT SetCurrentMediaType(IMFMediaType* pMediaType) = 0;
    virtual HRESULT GetCurrentMediaType(IMFMediaType** ppMediaType) = 0;
    virtual HRESULT GetMajorType(GUID* pguidMajorType) = 0;
};

struct IMFStreamDescriptor : IUnknown {
    virtual DWORD GetStreamIdentifier() = 0;
    virtual HRESULT GetMediaTypeHandler(IMFMediaTypeHandler** ppMediaTypeHandler) = 0;
};

struct IMFPresentationDescriptor : IUnknown {
    virtual HRESULT SetStreamDescriptor(DWORD dwIndex, BOOL fSelected, IMFStreamDescriptor* pDescriptor) = 0;
    virtual HRESULT GetStreamDescriptorCount(DWORD* pdwDescriptorCount) = 0;
    virtual HRESULT GetStreamDescriptorByIndex(DWORD dwIndex, BOOL* pfSelected, IMFStreamDescriptor** ppDescriptor) = 0;
};
