#pragma once
// The one media type this virtual camera produces: uncompressed RGB32
// (BGRA byte order in memory — exactly what the Stage-1 decoder emits),
// at the phone stream's current output size and frame rate.

#include <cstdint>

#include <windows.h>
#include <mfapi.h>

HRESULT CreateVideoMediaType(uint32_t width, uint32_t height, uint32_t fps,
                             IMFMediaType** ppType);
