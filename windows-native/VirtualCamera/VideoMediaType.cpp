#include "VideoMediaType.h"

#include "ComPtr.h"

HRESULT CreateVideoMediaType(uint32_t width, uint32_t height, uint32_t fps,
                             IMFMediaType** ppType) {
    if (ppType == nullptr) return E_POINTER;
    *ppType = nullptr;
    if (width == 0 || height == 0) return E_INVALIDARG;
    if (fps == 0) fps = 30;

    ComPtr<IMFMediaType> type;
    HRESULT hr = MFCreateMediaType(type.ReleaseAndGetAddressOf());
    if (FAILED(hr)) return hr;
    hr = type->SetGUID(MF_MT_MAJOR_TYPE, MFMediaType_Video);
    if (FAILED(hr)) return hr;
    hr = type->SetGUID(MF_MT_SUBTYPE, MFVideoFormat_RGB32);
    if (FAILED(hr)) return hr;
    MFSetAttributeSize(type.Get(), MF_MT_FRAME_SIZE, width, height);
    MFSetAttributeRatio(type.Get(), MF_MT_FRAME_RATE, fps, 1);
    MFSetAttributeRatio(type.Get(), MF_MT_PIXEL_ASPECT_RATIO, 1, 1);
    type->SetUINT32(MF_MT_INTERLACE_MODE, MFVideoInterlace_Progressive);
    type->SetUINT32(MF_MT_ALL_SAMPLES_INDEPENDENT, TRUE);
    type->SetUINT32(MF_MT_FIXED_SIZE_SAMPLES, TRUE);
    type->SetUINT32(MF_MT_SAMPLE_SIZE, width * height * 4);
    // Rows are written top-down into the sample buffer; a negative
    // default stride is how MF declares top-down RGB. (Orientation is
    // on the real-Windows verification list: if the Camera app shows
    // the image upside down, this sign — not the pixel order — is the
    // one-line fix.)
    type->SetUINT32(MF_MT_DEFAULT_STRIDE,
                    static_cast<UINT32>(-static_cast<INT32>(width * 4)));
    *ppType = type.Detach();
    return S_OK;
}
