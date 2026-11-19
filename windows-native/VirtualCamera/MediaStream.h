#pragma once
// The virtual camera's single video stream. Pull model, MF-standard:
// the pipeline asks with RequestSample; a pacing thread running at the
// negotiated frame rate composes one sample per tick from the newest
// shared-channel frame (orientation transform applied here) and
// answers one pending request per tick. No consumer → no samples are
// produced and nothing is read from the channel beyond a format probe
// (the phone side is told to stop by the app, not by this DLL — the
// DLL has no path back to the phone).
//
// Before the first frame (or whenever the feeder reports inactive) the
// stream serves opaque-black frames at the negotiated format, so a
// consumer that opens the camera early sees a valid, silent picture
// instead of an error.

#include <atomic>
#include <cstdint>
#include <deque>
#include <mutex>
#include <thread>
#include <vector>

#include <windows.h>
#include <mfidl.h>

#include "ComPtr.h"
#include "FrameChannel.h"

class MediaSource;

class MediaStream final : public IMFMediaStream,
                          public ComObjectBase<MediaStream> {
public:
    MediaStream(MediaSource* source, IMFStreamDescriptor* descriptor);
    ~MediaStream() override;

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

    // IMFMediaStream
    HRESULT STDMETHODCALLTYPE GetMediaSource(
        IMFMediaSource** ppMediaSource) override;
    HRESULT STDMETHODCALLTYPE GetStreamDescriptor(
        IMFStreamDescriptor** ppStreamDescriptor) override;
    HRESULT STDMETHODCALLTYPE RequestSample(IUnknown* pToken) override;

    // Lifecycle, driven by MediaSource.
    HRESULT Start();
    HRESULT Stop();
    HRESULT Shutdown();

private:
    void PacerLoop();
    void Tick();  // one pacing tick: pull frame, maybe format-change, deliver ≤1 sample
    void ApplyFormatChange(uint32_t outW, uint32_t outH, uint32_t fps);
    void ComposeBlackLocked();
    void DeliverLocked();
    void ClearTokensLocked();

    MediaSource* _source;  // non-owning: the source owns this stream and
                           // shuts it down before releasing it
    ComPtr<IMFStreamDescriptor> _descriptor;
    ComPtr<IMFMediaEventQueue> _eventQueue;

    std::atomic<bool> _shutdown{false};
    std::mutex _lock;
    bool _started = false;                 // guarded by _lock
    std::deque<IUnknown*> _tokens;         // AddRef'd; guarded by _lock
    std::thread _pacer;
    HANDLE _stopEvent = nullptr;           // manual-reset

    srvcam::FrameChannel _channel;
    std::vector<uint8_t> _rawScratch;      // kMaxFrameBytes
    std::vector<uint8_t> _currentFrame;    // post-transform, tight BGRA
    bool _currentIsBlack = true;
    uint64_t _lastSeq = 0;
    uint32_t _outW = 1280;
    uint32_t _outH = 720;
    uint32_t _fps = 30;
    LONGLONG _startTime100ns = 0;
};
