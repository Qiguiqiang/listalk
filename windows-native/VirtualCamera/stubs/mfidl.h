#pragma once
// STUB — see windows.h stub header note.
#include <windows.h>
#include <objbase.h>
#include "mfobjects.h"

constexpr DWORD MFMEDIASOURCE_IS_LIVE = 0x2;

static const GUID IID_IMFMediaEventGenerator = {0x2cd2d921, 0xc447, 0x44a7, {0xa1, 0x3c, 0x4a, 0xda, 0xbf, 0xc2, 0x47, 0xe3}};
static const GUID IID_IMFMediaSource = {0x279a808d, 0xaec7, 0x40c8, {0x9c, 0x6b, 0xa6, 0xb8, 0x97, 0x48, 0x7a, 0x15}};
static const GUID IID_IMFMediaStream = {0xd182108f, 0x4ec6, 0x4435, {0xaa, 0xe3, 0xce, 0x41, 0x99, 0x1a, 0x96, 0x04}};

struct IMFMediaSource : IMFMediaEventGenerator {
    virtual HRESULT CreatePresentationDescriptor(IMFPresentationDescriptor** ppPresentationDescriptor) = 0;
    virtual HRESULT GetCharacteristics(DWORD* pdwCharacteristics) = 0;
    virtual HRESULT Start(IMFPresentationDescriptor* pPresentationDescriptor, const GUID* pguidTimeFormat, const PROPVARIANT* pvarStartPosition) = 0;
    virtual HRESULT Stop() = 0;
    virtual HRESULT Pause() = 0;
    virtual HRESULT Shutdown() = 0;
};

struct IMFMediaStream : IMFMediaEventGenerator {
    virtual HRESULT GetMediaSource(IMFMediaSource** ppMediaSource) = 0;
    virtual HRESULT GetStreamDescriptor(IMFStreamDescriptor** ppStreamDescriptor) = 0;
    virtual HRESULT RequestSample(IUnknown* pToken) = 0;
};
