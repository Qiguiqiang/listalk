#include "MediaSource.h"

#include <mferror.h>

#include "MediaStream.h"
#include "Transform.h"
#include "VideoMediaType.h"

namespace {
constexpr uint32_t kDefaultWidth = 1280;
constexpr uint32_t kDefaultHeight = 720;
constexpr uint32_t kDefaultFps = 30;
}  // namespace

MediaSource::MediaSource() = default;

MediaSource::~MediaSource() {
    if (_stream != nullptr) _stream->Shutdown();
}

HRESULT MediaSource::CreateInstance(IMFMediaSource** ppSource) {
    if (ppSource == nullptr) return E_POINTER;
    *ppSource = nullptr;
    ComPtr<MediaSource> source;
    source.Reset(new MediaSource());
    HRESULT hr = source->Initialize();
    if (FAILED(hr)) return hr;
    return source->QueryInterface(IID_IMFMediaSource,
                                  reinterpret_cast<void**>(ppSource));
}

HRESULT MediaSource::Initialize() {
    return MFCreateEventQueue(_eventQueue.ReleaseAndGetAddressOf());
}

// ---- IUnknown ----

HRESULT STDMETHODCALLTYPE MediaSource::QueryInterface(REFIID riid, void** ppv) {
    if (ppv == nullptr) return E_POINTER;
    if (IsEqualGUID(riid, IID_IUnknown) ||
        IsEqualGUID(riid, IID_IMFMediaEventGenerator) ||
        IsEqualGUID(riid, IID_IMFMediaSource)) {
        *ppv = static_cast<IMFMediaSource*>(this);
    } else {
        *ppv = nullptr;
        return E_NOINTERFACE;
    }
    AddRef();
    return S_OK;
}

// ---- IMFMediaEventGenerator (delegated to the event queue) ----

HRESULT STDMETHODCALLTYPE MediaSource::GetEvent(DWORD dwFlags,
                                                IMFMediaEvent** ppEvent) {
    if (_eventQueue == nullptr) return E_FAIL;
    return _eventQueue->GetEvent(dwFlags, ppEvent);
}

HRESULT STDMETHODCALLTYPE MediaSource::BeginGetEvent(IMFAsyncCallback* pCallback,
                                                     IUnknown* punkState) {
    if (_eventQueue == nullptr) return E_FAIL;
    return _eventQueue->BeginGetEvent(pCallback, punkState);
}

HRESULT STDMETHODCALLTYPE MediaSource::EndGetEvent(IMFAsyncResult* pResult,
                                                   IMFMediaEvent** ppEvent) {
    if (_eventQueue == nullptr) return E_FAIL;
    return _eventQueue->EndGetEvent(pResult, ppEvent);
}

HRESULT STDMETHODCALLTYPE MediaSource::QueueEvent(MediaEventType met,
                                                  const GUID* pguidExtendedType,
                                                  HRESULT hrStatus,
                                                  const PROPVARIANT* pvValue) {
    if (_eventQueue == nullptr) return E_FAIL;
    return _eventQueue->QueueEvent(met, pguidExtendedType, hrStatus, pvValue);
}

// ---- IMFMediaSource ----

HRESULT MediaSource::EnsureStreamLocked() {
    if (_stream != nullptr) return S_OK;
    // Advertise exactly the format the phone is currently producing
    // (post-transform size), or the 720p30 default before any stream.
    _channel.Open();
    const srvcam::FrameFormat fmt = _channel.Format();
    const uint32_t srcW = fmt.width != 0 ? fmt.width : kDefaultWidth;
    const uint32_t srcH = fmt.height != 0 ? fmt.height : kDefaultHeight;
    const uint32_t fps = fmt.fps != 0 ? fmt.fps : kDefaultFps;
    uint32_t outW, outH;
    srvcam::RotatedSize(srcW, srcH, fmt.rotation, outW, outH);

    ComPtr<IMFMediaType> type;
    HRESULT hr = CreateVideoMediaType(outW, outH, fps,
                                      type.ReleaseAndGetAddressOf());
    if (FAILED(hr)) return hr;
    IMFMediaType* types[1] = {type.Get()};
    hr = MFCreateStreamDescriptor(0, 1, types,
                                  _descriptor.ReleaseAndGetAddressOf());
    if (FAILED(hr)) return hr;
    _stream.Reset(new MediaStream(this, _descriptor.Get()));
    return S_OK;
}

HRESULT STDMETHODCALLTYPE MediaSource::CreatePresentationDescriptor(
    IMFPresentationDescriptor** ppPresentationDescriptor) {
    if (ppPresentationDescriptor == nullptr) return E_POINTER;
    *ppPresentationDescriptor = nullptr;
    if (_shutdown) return MF_E_SHUTDOWN;
    HRESULT hr = EnsureStreamLocked();
    if (FAILED(hr)) return hr;
    ComPtr<IMFPresentationDescriptor> pd;
    hr = MFCreatePresentationDescriptor(pd.ReleaseAndGetAddressOf());
    if (FAILED(hr)) return hr;
    hr = pd->SetStreamDescriptor(0, TRUE, _descriptor.Get());
    if (FAILED(hr)) return hr;
    *ppPresentationDescriptor = pd.Detach();
    return S_OK;
}

HRESULT STDMETHODCALLTYPE MediaSource::GetCharacteristics(
    DWORD* pdwCharacteristics) {
    if (pdwCharacteristics == nullptr) return E_POINTER;
    if (_shutdown) return MF_E_SHUTDOWN;
    *pdwCharacteristics = MFMEDIASOURCE_IS_LIVE;
    return S_OK;
}

HRESULT STDMETHODCALLTYPE MediaSource::Start(
    IMFPresentationDescriptor* /*pPresentationDescriptor*/,
    const GUID* /*pguidTimeFormat*/,
    const PROPVARIANT* /*pvarStartPosition*/) {
    if (_shutdown) return MF_E_SHUTDOWN;
    HRESULT hr = EnsureStreamLocked();
    if (FAILED(hr)) return hr;
    if (!_newStreamQueued) {
        _newStreamQueued = true;
        if (_eventQueue != nullptr)
            _eventQueue->QueueEventParamUnk(
                MENewStream, GUID_NULL, S_OK,
                static_cast<IMFMediaStream*>(_stream.Get()));
    }
    hr = _stream->Start();
    if (FAILED(hr)) return hr;
    _started = true;
    if (_eventQueue != nullptr)
        _eventQueue->QueueEventParamVar(MESourceStarted, GUID_NULL, S_OK,
                                        nullptr);
    return S_OK;
}

HRESULT STDMETHODCALLTYPE MediaSource::Stop() {
    if (_shutdown) return MF_E_SHUTDOWN;
    if (_stream != nullptr) _stream->Stop();
    _started = false;
    if (_eventQueue != nullptr)
        _eventQueue->QueueEventParamVar(MESourceStopped, GUID_NULL, S_OK,
                                        nullptr);
    return S_OK;
}

HRESULT STDMETHODCALLTYPE MediaSource::Pause() {
    // Live camera sources do not pause.
    return MF_E_INVALID_STATE_TRANSITION;
}

HRESULT STDMETHODCALLTYPE MediaSource::Shutdown() {
    if (_shutdown) return S_OK;
    _shutdown = true;
    if (_stream != nullptr) _stream->Shutdown();
    _channel.Close();
    if (_eventQueue != nullptr) _eventQueue->Shutdown();
    return S_OK;
}
