#pragma once
// The virtual camera media source: one live video stream fed from the
// shared frame channel. Activated by the Windows Camera Frame Server
// (CoCreateInstance of the CLSID in SharedFrameChannel.h) whenever an
// app opens the virtual camera that the SoundRelay app registered via
// MFCreateVirtualCamera. Architecture mirrors Microsoft's
// Windows-Camera VirtualCamera sample (SimpleMediaSource): a source
// owning one stream, both event-generator based, live characteristics,
// no pause/seek.

#include <windows.h>
#include <mfidl.h>

#include "ComPtr.h"
#include "FrameChannel.h"

class MediaStream;

class MediaSource final : public IMFMediaSource,
                          public ComObjectBase<MediaSource> {
public:
    MediaSource();
    ~MediaSource() override;

    static HRESULT CreateInstance(IMFMediaSource** ppSource);
    HRESULT Initialize();

    // IUnknown
    HRESULT STDMETHODCALLTYPE QueryInterface(REFIID riid, void** ppv) override;
    ULONG STDMETHODCALLTYPE AddRef() override { return AddRefInternal(); }
    ULONG STDMETHODCALLTYPE Release() override { return ReleaseInternal(); }

    // IMFMediaEventGenerator
    HRESULT STDMETHODCALLTYPE GetEvent(DWORD dwFlags,
                                       IMFMediaEvent** ppEvent) override;
    HRESULT STDMETHODCALLTYPE BeginGetEvent(IMFAsyncCallback* pCallback,
                                            IUnknown* punkState) override;
    HRESULT STDMETHODCALLTYPE EndGetEvent(IMFAsyncResult* pResult,
                                          IMFMediaEvent** ppEvent) override;
    HRESULT STDMETHODCALLTYPE QueueEvent(MediaEventType met,
                                         const GUID* pguidExtendedType,
                                         HRESULT hrStatus,
                                         const PROPVARIANT* pvValue) override;

    // IMFMediaSource
    HRESULT STDMETHODCALLTYPE CreatePresentationDescriptor(
        IMFPresentationDescriptor** ppPresentationDescriptor) override;
    HRESULT STDMETHODCALLTYPE GetCharacteristics(
        DWORD* pdwCharacteristics) override;
    HRESULT STDMETHODCALLTYPE Start(
        IMFPresentationDescriptor* pPresentationDescriptor,
        const GUID* pguidTimeFormat,
        const PROPVARIANT* pvarStartPosition) override;
    HRESULT STDMETHODCALLTYPE Stop() override;
    HRESULT STDMETHODCALLTYPE Pause() override;
    HRESULT STDMETHODCALLTYPE Shutdown() override;

private:
    HRESULT EnsureStreamLocked();

    ComPtr<IMFMediaEventQueue> _eventQueue;
    ComPtr<IMFStreamDescriptor> _descriptor;
    ComPtr<MediaStream> _stream;
    srvcam::FrameChannel _channel;  // format probes for descriptor creation
    bool _shutdown = false;
    bool _started = false;
    bool _newStreamQueued = false;
};
