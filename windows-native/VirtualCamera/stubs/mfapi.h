#pragma once
// STUB — see windows.h stub header note.
#include <windows.h>
#include <objbase.h>
#include "mfobjects.h"

constexpr ULONG MF_VERSION = 0x00020070;
constexpr DWORD MFSTARTUP_LITE = 1;
constexpr DWORD MFSTARTUP_FULL = 0;

static const GUID MF_MT_MAJOR_TYPE = {0x48eba18e, 0xf8c9, 0x4687, {0xbf, 0x11, 0x0a, 0x74, 0xc9, 0xf9, 0x6a, 0x8f}};
static const GUID MF_MT_SUBTYPE = {0xf7e34c9a, 0x42e8, 0x4714, {0xb7, 0x4b, 0xcb, 0x29, 0xd7, 0x2c, 0x35, 0xe5}};
static const GUID MF_MT_FRAME_SIZE = {0x1652c33d, 0xd6b2, 0x4012, {0xb8, 0x34, 0x72, 0x03, 0x08, 0x49, 0xa3, 0x7d}};
static const GUID MF_MT_FRAME_RATE = {0xc459a2e8, 0x3d2c, 0x4e44, {0xb1, 0x32, 0xfe, 0xe5, 0x15, 0x6c, 0x7b, 0xb0}};
static const GUID MF_MT_PIXEL_ASPECT_RATIO = {0xc6376a1e, 0x8d0a, 0x4027, {0xbe, 0x45, 0x6d, 0x9a, 0x0a, 0x6d, 0xb5, 0xb5}};
static const GUID MF_MT_INTERLACE_MODE = {0xe2724bb8, 0xe676, 0x4806, {0xb4, 0xb2, 0xa8, 0xd6, 0xef, 0xb4, 0x4c, 0x34}};
static const GUID MF_MT_ALL_SAMPLES_INDEPENDENT = {0xc9173739, 0x5dd5, 0x461c, {0xbf, 0x99, 0xed, 0xb2, 0x44, 0xf0, 0x7c, 0x07}};
static const GUID MF_MT_FIXED_SIZE_SAMPLES = {0xb8ebefaf, 0xb718, 0x4e14, {0xb3, 0xc9, 0xd7, 0xa0, 0xed, 0x86, 0x17, 0x73}};
static const GUID MF_MT_SAMPLE_SIZE = {0xdad3ab78, 0x1990, 0x4dd6, {0xb4, 0xad, 0xf4, 0x43, 0xab, 0x66, 0x71, 0x69}};
static const GUID MF_MT_DEFAULT_STRIDE = {0x644b4e48, 0x1e02, 0x4516, {0xb0, 0xeb, 0xc0, 0x1c, 0xa9, 0xd4, 0x9a, 0xc6}};

HRESULT MFStartup(ULONG version, DWORD dwFlags);
HRESULT MFShutdown();
LONGLONG MFGetSystemTime();
HRESULT MFCreateEventQueue(IMFMediaEventQueue** ppMediaEventQueue);
HRESULT MFCreateSample(IMFSample** ppIMFSample);
HRESULT MFCreateMemoryBuffer(DWORD cbMaxLength, IMFMediaBuffer** ppBuffer);
HRESULT MFCreateMediaType(IMFMediaType** ppMFType);
HRESULT MFCreateStreamDescriptor(DWORD dwStreamIdentifier, DWORD cMediaTypes, IMFMediaType** apMediaTypes, IMFStreamDescriptor** ppDescriptor);
HRESULT MFCreatePresentationDescriptor(IMFPresentationDescriptor** ppPresentationDescriptor);
HRESULT MFSetAttributeSize(IMFAttributes* pAttributes, REFGUID guidKey, UINT32 unWidth, UINT32 unHeight);
HRESULT MFSetAttributeRatio(IMFAttributes* pAttributes, REFGUID guidKey, UINT32 unNumerator, UINT32 unDenominator);
HRESULT MFGetAttributeSize(IMFAttributes* pAttributes, REFGUID guidKey, UINT32* punWidth, UINT32* punHeight);
HRESULT MFGetAttributeRatio(IMFAttributes* pAttributes, REFGUID guidKey, UINT32* punNumerator, UINT32* punDenominator);
