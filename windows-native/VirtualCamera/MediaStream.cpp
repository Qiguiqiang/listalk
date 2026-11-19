#include "MediaStream.h"

#include <cstring>

#include <mferror.h>

#include "MediaSource.h"
#include "Transform.h"
#include "VideoMediaType.h"

namespace {
constexpr size_t kMaxPendingTokens = 8;
constexpr uint32_t kDefaultWidth = 1280;
constexpr uint32_t kDefaultHeight = 720;
constexpr uint32_t kDefaultFps = 30;
}  // namespace

MediaStream::MediaStream(MediaSource* source, IMFStreamDescriptor* descriptor)
    : _source(source), _descriptor(descriptor) {
    MFCreateEventQueue(_eventQueue.ReleaseAndGetAddressOf());
    _stopEvent = CreateEventW(nullptr, TRUE, FALSE, nullptr);
    _rawScratch.resize(srvcam::kMaxFrameBytes);
}

MediaStream::~MediaStream() {
    // Defensive teardown: per MF contract the source calls Shutdown
    // before the final Release, but never let the pacer outlive `this`.
    _shutdown.store(true);
    if (_stopEvent != nullptr) SetEvent(_stopEvent);
    if (_pacer.joinable()) _pacer.join();
    ClearTokensLocked();
    if (_stopEvent != nullptr) {
        CloseHandle(_stopEvent);
        _stopEvent = nullptr;
    }
}

// ---- IUnknown ----

HRESULT STDMETHODCALLTYPE MediaStream::QueryInterface(REFIID riid, void** ppv) {
    if (ppv == nullptr) return E_POINTER;
    if (IsEqualGUID(riid, IID_IUnknown) ||
        IsEqualGUID(riid, IID_IMFMediaEventGenerator) ||
        IsEqualGUID(riid, IID_IMFMediaStream)) {
        *ppv = static_cast<IMFMediaStream*>(this);
    } else {
        *ppv = nullptr;
        return E_NOINTERFACE;
    }
    AddRef();
    return S_OK;
}

// ---- IMFMediaEventGenerator (delegated to the event queue) ----

HRESULT STDMETHODCALLTYPE MediaStream::GetEvent(DWORD dwFlags,
                                                IMFMediaEvent** ppEvent) {
    if (_eventQueue == nullptr) return E_FAIL;
    return _eventQueue->GetEvent(dwFlags, ppEvent);
}

HRESULT STDMETHODCALLTYPE MediaStream::BeginGetEvent(IMFAsyncCallback* pCallback,
                                                     IUnknown* punkState) {
    if (_eventQueue == nullptr) return E_FAIL;
    return _eventQueue->BeginGetEvent(pCallback, punkState);
}

HRESULT STDMETHODCALLTYPE MediaStream::EndGetEvent(IMFAsyncResult* pResult,
                                                   IMFMediaEvent** ppEvent) {
    if (_eventQueue == nullptr) return E_FAIL;
    return _eventQueue->EndGetEvent(pResult, ppEvent);
}

HRESULT STDMETHODCALLTYPE MediaStream::QueueEvent(MediaEventType met,
                                                  const GUID* pguidExtendedType,
                                                  HRESULT hrStatus,
                                                  const PROPVARIANT* pvValue) {
    if (_eventQueue == nullptr) return E_FAIL;
    return _eventQueue->QueueEvent(met, pguidExtendedType, hrStatus, pvValue);
}

// ---- IMFMediaStream ----

HRESULT STDMETHODCALLTYPE MediaStream::GetMediaSource(
    IMFMediaSource** ppMediaSource) {
    if (ppMediaSource == nullptr) return E_POINTER;
    if (_source == nullptr) return E_UNEXPECTED;
    return _source->QueryInterface(IID_IMFMediaSource,
                                   reinterpret_cast<void**>(ppMediaSource));
}

HRESULT STDMETHODCALLTYPE MediaStream::GetStreamDescriptor(
    IMFStreamDescriptor** ppStreamDescriptor) {
    if (ppStreamDescriptor == nullptr) return E_POINTER;
    if (_descriptor == nullptr) return E_UNEXPECTED;
    *ppStreamDescriptor = _descriptor.Get();
    (*ppStreamDescriptor)->AddRef();
    return S_OK;
}

HRESULT STDMETHODCALLTYPE MediaStream::RequestSample(IUnknown* pToken) {
    if (_shutdown.load()) return MF_E_SHUTDOWN;
    std::lock_guard<std::mutex> guard(_lock);
    if (!_started) return MF_E_INVALID_STATE_TRANSITION;
    if (pToken != nullptr) pToken->AddRef();
    if (_tokens.size() >= kMaxPendingTokens) {
        // Live source: stale demand is worthless. Drop the oldest
        // request rather than growing an unbounded backlog.
        IUnknown* old = _tokens.front();
        _tokens.pop_front();
        if (old != nullptr) old->Release();
    }
    _tokens.push_back(pToken);
    return S_OK;
}

// ---- lifecycle ----

HRESULT MediaStream::Start() {
    if (_shutdown.load()) return MF_E_SHUTDOWN;
    {
        std::lock_guard<std::mutex> guard(_lock);
        if (_started) return S_OK;
        _started = true;
        // Seed the output format from the descriptor's current type
        // (the pipeline may have selected it before starting us).
        ComPtr<IMFMediaTypeHandler> handler;
        if (_descriptor != nullptr &&
            SUCCEEDED(_descriptor->GetMediaTypeHandler(
                handler.ReleaseAndGetAddressOf()))) {
            ComPtr<IMFMediaType> type;
            if (SUCCEEDED(handler->GetCurrentMediaType(
                    type.ReleaseAndGetAddressOf())) &&
                type != nullptr) {
                UINT32 w = 0, h = 0, num = 0, den = 0;
                if (SUCCEEDED(MFGetAttributeSize(type.Get(), MF_MT_FRAME_SIZE,
                                                 &w, &h)) &&
                    w != 0 && h != 0) {
                    _outW = w;
                    _outH = h;
                }
                if (SUCCEEDED(MFGetAttributeRatio(type.Get(), MF_MT_FRAME_RATE,
                                                  &num, &den)) &&
                    den != 0 && num / den != 0) {
                    _fps = num / den;
                }
            }
        }
        _startTime100ns = MFGetSystemTime();
        _lastSeq = 0;
        _currentFrame.clear();
        _currentIsBlack = true;
    }
    ResetEvent(_stopEvent);
    _pacer = std::thread([this] { PacerLoop(); });
    if (_eventQueue != nullptr)
        _eventQueue->QueueEventParamVar(MEStreamStarted, GUID_NULL, S_OK,
                                        nullptr);
    return S_OK;
}

HRESULT MediaStream::Stop() {
    {
        std::lock_guard<std::mutex> guard(_lock);
        if (!_started) return S_OK;
        _started = false;
    }
    if (_stopEvent != nullptr) SetEvent(_stopEvent);
    if (_pacer.joinable()) _pacer.join();
    {
        std::lock_guard<std::mutex> guard(_lock);
        ClearTokensLocked();
    }
    if (!_shutdown.load() && _eventQueue != nullptr)
        _eventQueue->QueueEventParamVar(MEStreamStopped, GUID_NULL, S_OK,
                                        nullptr);
    return S_OK;
}

HRESULT MediaStream::Shutdown() {
    if (_shutdown.exchange(true)) return S_OK;
    Stop();
    _channel.Close();
    if (_eventQueue != nullptr) _eventQueue->Shutdown();
    return S_OK;
}

// ---- pacing + delivery (stream's own thread) ----

void MediaStream::PacerLoop() {
    for (;;) {
        uint32_t fps;
        {
            std::lock_guard<std::mutex> guard(_lock);
            fps = _fps != 0 ? _fps : kDefaultFps;
        }
        DWORD intervalMs = 1000 / fps;
        if (intervalMs == 0) intervalMs = 1;
        if (WaitForSingleObject(_stopEvent, intervalMs) == WAIT_OBJECT_0)
            break;
        Tick();
    }
}

void MediaStream::Tick() {
    std::lock_guard<std::mutex> guard(_lock);
    if (!_started || _shutdown.load()) return;

    _channel.Open();  // lazy retry; no-op while open
    const srvcam::FrameFormat fmt = _channel.Format();
    const bool haveSource = fmt.width != 0 && fmt.height != 0;
    const uint32_t srcW = haveSource ? fmt.width : kDefaultWidth;
    const uint32_t srcH = haveSource ? fmt.height : kDefaultHeight;
    const int32_t rotation = haveSource ? fmt.rotation : 0;
    const bool mirrored = haveSource && fmt.mirrored;
    const uint32_t fps = (haveSource && fmt.fps != 0) ? fmt.fps : _fps;

    uint32_t outW, outH;
    srvcam::RotatedSize(srcW, srcH, rotation, outW, outH);
    if (outW != _outW || outH != _outH || fps != _fps) {
        ApplyFormatChange(outW, outH, fps);
        return;  // no sample this tick: the pipeline re-queries the type
    }

    bool serveBlack = true;
    if (_channel.IsOpen() && fmt.active) {
        srvcam::FrameView view;
        if (_channel.ReadLatest(_rawScratch.data(), _rawScratch.size(), view)) {
            if (view.seq != _lastSeq) {
                _lastSeq = view.seq;
                _currentFrame.resize(static_cast<size_t>(outW) * outH * 4);
                srvcam::TransformBgra(_rawScratch.data(), view.width,
                                      view.height, rotation, mirrored,
                                      _currentFrame.data());
                _currentIsBlack = false;
            }
            serveBlack = false;  // live feed: repeat the last frame
        } else if (!_currentIsBlack) {
            serveBlack = false;  // no new frame yet: repeat the last one
        }
    }
    if (serveBlack && (_currentFrame.empty() || !_currentIsBlack))
        ComposeBlackLocked();
    DeliverLocked();
}

void MediaStream::ApplyFormatChange(uint32_t outW, uint32_t outH, uint32_t fps) {
    _outW = outW;
    _outH = outH;
    _fps = fps != 0 ? fps : kDefaultFps;
    _currentFrame.clear();
    _currentIsBlack = true;
    _lastSeq = 0;
    ComPtr<IMFMediaTypeHandler> handler;
    if (_descriptor != nullptr &&
        SUCCEEDED(_descriptor->GetMediaTypeHandler(
            handler.ReleaseAndGetAddressOf()))) {
        ComPtr<IMFMediaType> type;
        if (SUCCEEDED(CreateVideoMediaType(outW, outH, _fps,
                                           type.ReleaseAndGetAddressOf())))
            handler->SetCurrentMediaType(type.Get());
    }
    if (_eventQueue != nullptr)
        _eventQueue->QueueEventParamVar(MEStreamFormatChanged, GUID_NULL,
                                        S_OK, nullptr);
}

void MediaStream::ComposeBlackLocked() {
    const size_t bytes = static_cast<size_t>(_outW) * _outH * 4;
    _currentFrame.assign(bytes, 0);
    // Opaque black: B=G=R=0, A=255.
    for (size_t i = 3; i < bytes; i += 4) _currentFrame[i] = 0xFF;
    _currentIsBlack = true;
}

void MediaStream::DeliverLocked() {
    if (_tokens.empty() || _currentFrame.empty() || _eventQueue == nullptr)
        return;
    ComPtr<IMFSample> sample;
    if (FAILED(MFCreateSample(sample.ReleaseAndGetAddressOf()))) return;
    ComPtr<IMFMediaBuffer> buffer;
    const DWORD bytes = static_cast<DWORD>(_currentFrame.size());
    if (FAILED(MFCreateMemoryBuffer(bytes, buffer.ReleaseAndGetAddressOf())))
        return;
    BYTE* data = nullptr;
    DWORD maxLen = 0, curLen = 0;
    if (FAILED(buffer->Lock(&data, &maxLen, &curLen))) return;
    std::memcpy(data, _currentFrame.data(), bytes);
    buffer->Unlock();
    buffer->SetCurrentLength(bytes);
    if (FAILED(sample->AddBuffer(buffer.Get()))) return;

    IUnknown* token = _tokens.front();
    _tokens.pop_front();
    if (token != nullptr) {
        sample->SetUnknown(MFSampleExtension_Token, token);
        token->Release();
    }
    sample->SetSampleTime(MFGetSystemTime() - _startTime100ns);
    sample->SetSampleDuration(10000000LL / (_fps != 0 ? _fps : kDefaultFps));
    _eventQueue->QueueEventParamUnk(MEMediaSample, GUID_NULL, S_OK,
                                    sample.Get());
}

void MediaStream::ClearTokensLocked() {
    for (IUnknown* token : _tokens)
        if (token != nullptr) token->Release();
    _tokens.clear();
}
