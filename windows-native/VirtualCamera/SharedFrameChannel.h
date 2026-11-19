#pragma once
// Shared-memory frame channel between the SoundRelay app process (writer)
// and the SoundRelay virtual-camera media source DLL (reader).
//
// The app (.NET, VirtualCameraChannel.cs) publishes the newest decoded
// BGRA frame here; the media source — hosted by the Windows Camera Frame
// Server in a different process — reads it. Named file mapping, created
// by the app with an explicit DACL (see VirtualCameraService.cs), opened
// read-only by the DLL.
//
// The byte layout below is a protocol: the C# writer mirrors these exact
// offsets (VirtualCameraChannel.cs carries the same constants with a
// pointer back here), and FrameChannelCore.h implements both sides of
// the protocol over a raw buffer so the sandbox tests exercise the real
// layout. Change one side, change the other, bump kVersion.
//
// Naming: the mapping name and the media-source CLSID are internal
// identifiers, not brand surface — like the AirBridge-era protocol
// constants they are KEEP-compat and do not follow product renames.

#include <cstdint>

namespace srvcam {

// Session-local object name (no "Global\" prefix): the mapping lives in
// the interactive session's object namespace. Whether the Frame Server's
// hosting process shares that namespace is a real-Windows verification
// item (impl report); the documented fallback is a "Global\" name, which
// needs SeCreateGlobalPrivilege on the creator side.
inline constexpr const wchar_t* kMappingName = L"SoundRelayVirtualCameraFrames.v1";

// Media source CLSID — the sourceId passed to MFCreateVirtualCamera and
// the InprocServer32 registration key for this DLL. Fixed forever.
inline constexpr const wchar_t* kMediaSourceClsid = L"{7AD7CD52-9DC5-45A4-A3C6-F06F6206F8DA}";

inline constexpr uint32_t kMagic = 0x43565253u;  // "SRVC" (little-endian)
inline constexpr uint32_t kVersion = 1;
inline constexpr uint32_t kHeaderBytes = 64;
inline constexpr uint32_t kSlotCount = 2;
inline constexpr uint32_t kMaxFrameWidth = 1920;
inline constexpr uint32_t kMaxFrameHeight = 1080;
inline constexpr uint32_t kBytesPerPixel = 4;  // BGRA
inline constexpr uint32_t kMaxFrameBytes =
    kMaxFrameWidth * kMaxFrameHeight * kBytesPerPixel;  // 8,294,400
inline constexpr uint32_t kSlotHeaderBytes = 32;

constexpr uint32_t SlotOffset(uint32_t i) {
    return kHeaderBytes + i * (kSlotHeaderBytes + kMaxFrameBytes);
}
inline constexpr uint32_t kTotalBytes =
    kHeaderBytes + kSlotCount * (kSlotHeaderBytes + kMaxFrameBytes);

// Header field offsets (all little-endian, natural widths).
enum HeaderField : uint32_t {
    kOffMagic = 0,              // u32 kMagic
    kOffVersion = 4,            // u32 kVersion
    kOffHeaderBytes = 8,        // u32 kHeaderBytes
    kOffSlotCount = 12,         // u32 kSlotCount
    kOffFrameWidth = 16,        // u32 source (sensor-raw) width of newest frame
    kOffFrameHeight = 20,       // u32 source height
    kOffStride = 24,            // u32 source stride in bytes
    kOffFps = 28,               // u32 nominal fps from VIDEO_CONFIG
    kOffRotation = 32,          // i32 clockwise degrees to apply: 0/90/180/270
    kOffMirrored = 36,          // u32 0/1: horizontal flip after rotation
    kOffLatestSeq = 40,         // u64 sequence of newest complete frame (0 = none)
    kOffLatestTimestampUs = 48, // u64 writer monotonic µs at publish (diagnostic)
    kOffActive = 56,            // u32 1 while the feeder is publishing
    kOffReserved = 60,          // u32 zero
};

// Slot field offsets, relative to SlotOffset(i). Pixels follow the slot
// header. A slot is complete iff its seq equals the header's latestSeq
// AND still does after the reader copied it (see FrameChannelCore.h).
enum SlotField : uint32_t {
    kSlotOffSeq = 0,          // u64 frame sequence (written last by the writer)
    kSlotOffTimestampUs = 8,  // u64 writer monotonic µs
    kSlotOffWidth = 16,       // u32
    kSlotOffHeight = 20,      // u32
    kSlotOffStride = 24,      // u32 source stride; pixels may be padded
    kSlotOffReserved = 28,    // u32 zero
    kSlotOffPixels = 32,      // BGRA pixels, stride * height bytes
};

}  // namespace srvcam
