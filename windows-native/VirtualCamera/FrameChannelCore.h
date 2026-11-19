#pragma once
// Both sides of the shared-frame-channel protocol, implemented over a
// raw byte buffer so the exact production layout is testable without
// Windows: the sandbox tests (tests/FrameChannelCoreTest.cpp) drive a
// writer and a reader over one malloc'd buffer — the same bytes the C#
// writer (VirtualCameraChannel.cs, a field-for-field mirror of the
// writer below) and the DLL reader (FrameChannel.cpp) exchange in
// production.
//
// Concurrency model (single writer, single reader, lock-free):
//   Writer: copy pixels into slot (seq % kSlotCount), write slot meta,
//           write header format, fence, write slot.seq, fence, write
//           header.latestSeq. A slot's seq is written LAST, so a reader
//           that sees slot.seq == header.latestSeq knows the pixels and
//           meta were fully written before it.
//   Reader: read header.latestSeq, find the slot carrying that seq,
//           copy pixels out, then re-read slot.seq and header.latestSeq.
//           If either moved, the writer lapped the reader mid-copy —
//           retry once, then give up for this tick (the caller repeats
//           the previous frame instead). With 2 slots at 30 fps the
//           writer needs two full frame intervals to lap a copy that
//           takes ~1 ms, so a failed validation means something is
//           deeply wrong, not normal contention.
// All field access goes through memcpy: no alignment or strict-aliasing
// assumptions about the mapped view.

#include <atomic>
#include <cstdint>
#include <cstring>

#include "SharedFrameChannel.h"

namespace srvcam {

inline uint32_t LoadU32(const uint8_t* p) {
    uint32_t v;
    std::memcpy(&v, p, sizeof(v));
    return v;
}
inline uint64_t LoadU64(const uint8_t* p) {
    uint64_t v;
    std::memcpy(&v, p, sizeof(v));
    return v;
}
inline void StoreU32(uint8_t* p, uint32_t v) { std::memcpy(p, &v, sizeof(v)); }
inline void StoreU64(uint8_t* p, uint64_t v) { std::memcpy(p, &v, sizeof(v)); }

inline void FullFence() { std::atomic_thread_fence(std::memory_order_seq_cst); }

// ---- format snapshot (header fields a reader negotiates from) ----
struct FrameFormat {
    uint32_t width = 0;
    uint32_t height = 0;
    uint32_t stride = 0;
    uint32_t fps = 0;
    int32_t rotation = 0;
    bool mirrored = false;
    bool active = false;
    uint64_t latestSeq = 0;
};

inline bool ChannelValid(const uint8_t* base) {
    return base != nullptr && LoadU32(base + kOffMagic) == kMagic &&
           LoadU32(base + kOffVersion) == kVersion &&
           LoadU32(base + kOffSlotCount) == kSlotCount;
}

inline FrameFormat ReadFormat(const uint8_t* base) {
    FrameFormat f;
    f.width = LoadU32(base + kOffFrameWidth);
    f.height = LoadU32(base + kOffFrameHeight);
    f.stride = LoadU32(base + kOffStride);
    f.fps = LoadU32(base + kOffFps);
    f.rotation = static_cast<int32_t>(LoadU32(base + kOffRotation));
    f.mirrored = LoadU32(base + kOffMirrored) != 0;
    f.latestSeq = LoadU64(base + kOffLatestSeq);
    f.active = LoadU32(base + kOffActive) != 0;
    return f;
}

// ---- writer side (production writer is C#; this is its reference) ----

// Initialize a freshly created mapping. Only the creator calls this,
// and only when the mapping did not already exist.
inline void WriterInit(uint8_t* base) {
    std::memset(base, 0, kTotalBytes);
    StoreU32(base + kOffMagic, kMagic);
    StoreU32(base + kOffVersion, kVersion);
    StoreU32(base + kOffHeaderBytes, kHeaderBytes);
    StoreU32(base + kOffSlotCount, kSlotCount);
    FullFence();
}

// Publish the current format without a frame (used before the first
// frame exists, and to flip `active` on stop).
inline void WriterWriteFormat(uint8_t* base, const FrameFormat& f) {
    StoreU32(base + kOffFrameWidth, f.width);
    StoreU32(base + kOffFrameHeight, f.height);
    StoreU32(base + kOffStride, f.stride);
    StoreU32(base + kOffFps, f.fps);
    StoreU32(base + kOffRotation, static_cast<uint32_t>(f.rotation));
    StoreU32(base + kOffMirrored, f.mirrored ? 1 : 0);
    StoreU32(base + kOffActive, f.active ? 1 : 0);
    FullFence();
}

// Publish one frame. `bgra` holds stride*height bytes (rows may be
// padded). Returns the new sequence number, or 0 if the frame does not
// fit the channel (caller drops it — never blocks, never truncates).
inline uint64_t WriterPublish(uint8_t* base, const uint8_t* bgra, uint32_t width,
                              uint32_t height, uint32_t stride, uint32_t fps,
                              int32_t rotation, bool mirrored,
                              uint64_t timestampUs) {
    if (base == nullptr || bgra == nullptr || width == 0 || height == 0 ||
        width > kMaxFrameWidth || height > kMaxFrameHeight ||
        stride < width * kBytesPerPixel ||
        static_cast<uint64_t>(stride) * height > kMaxFrameBytes) {
        return 0;
    }
    const uint64_t seq = LoadU64(base + kOffLatestSeq) + 1;
    uint8_t* slot = base + SlotOffset(static_cast<uint32_t>(seq % kSlotCount));
    std::memcpy(slot + kSlotOffPixels, bgra, static_cast<size_t>(stride) * height);
    StoreU64(slot + kSlotOffTimestampUs, timestampUs);
    StoreU32(slot + kSlotOffWidth, width);
    StoreU32(slot + kSlotOffHeight, height);
    StoreU32(slot + kSlotOffStride, stride);
    FrameFormat f;
    f.width = width;
    f.height = height;
    f.stride = stride;
    f.fps = fps;
    f.rotation = rotation;
    f.mirrored = mirrored;
    f.active = true;
    WriterWriteFormat(base, f);
    FullFence();
    StoreU64(slot + kSlotOffSeq, seq);
    StoreU64(base + kOffLatestTimestampUs, timestampUs);
    FullFence();
    StoreU64(base + kOffLatestSeq, seq);
    return seq;
}

// ---- reader side (production reader is FrameChannel.cpp) ----

struct FrameView {
    uint32_t width = 0;
    uint32_t height = 0;
    uint32_t stride = 0;  // stride of the copied-out pixels (tight: width*4)
    uint64_t seq = 0;
    uint64_t timestampUs = 0;
};

// Copy the newest complete frame into `dest` (tightly packed BGRA,
// width*4 stride). Returns false when there is no frame yet, the frame
// does not fit `destCapacity`, or the copy could not be validated after
// a retry — the caller then repeats its previous frame for this tick.
inline bool ReadLatest(const uint8_t* base, uint8_t* dest, size_t destCapacity,
                       FrameView& out) {
    if (base == nullptr || dest == nullptr || !ChannelValid(base)) return false;
    for (int attempt = 0; attempt < 2; ++attempt) {
        const uint64_t seq = LoadU64(base + kOffLatestSeq);
        if (seq == 0) return false;
        const uint8_t* slot = nullptr;
        for (uint32_t i = 0; i < kSlotCount; ++i) {
            const uint8_t* s = base + SlotOffset(i);
            if (LoadU64(s + kSlotOffSeq) == seq) {
                slot = s;
                break;
            }
        }
        if (slot == nullptr) continue;  // writer mid-publish; retry once
        const uint32_t w = LoadU32(slot + kSlotOffWidth);
        const uint32_t h = LoadU32(slot + kSlotOffHeight);
        const uint32_t stride = LoadU32(slot + kSlotOffStride);
        if (w == 0 || h == 0 || w > kMaxFrameWidth || h > kMaxFrameHeight ||
            stride < w * kBytesPerPixel ||
            static_cast<uint64_t>(stride) * h > kMaxFrameBytes ||
            static_cast<uint64_t>(w) * h * kBytesPerPixel > destCapacity) {
            return false;  // corrupt or oversized: do not retry garbage
        }
        const uint8_t* px = slot + kSlotOffPixels;
        const uint32_t tight = w * kBytesPerPixel;
        if (stride == tight) {
            std::memcpy(dest, px, static_cast<size_t>(tight) * h);
        } else {
            for (uint32_t y = 0; y < h; ++y)
                std::memcpy(dest + static_cast<size_t>(y) * tight,
                            px + static_cast<size_t>(y) * stride, tight);
        }
        FullFence();
        // Validate: neither the slot nor the header moved under the copy.
        if (LoadU64(slot + kSlotOffSeq) != seq) continue;
        if (LoadU64(base + kOffLatestSeq) != seq) continue;
        out.width = w;
        out.height = h;
        out.stride = tight;
        out.seq = seq;
        out.timestampUs = LoadU64(slot + kSlotOffTimestampUs);
        return true;
    }
    return false;
}

}  // namespace srvcam
