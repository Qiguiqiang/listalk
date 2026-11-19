#pragma once
// Windows wrapper around the shared frame channel: opens the app's
// named file mapping read-only and reads through FrameChannelCore.h.
// The mapping is created by the app (.NET VirtualCameraChannel); this
// side never creates it — absence simply means "no frames yet", and
// callers retry lazily.

#include <cstdint>

#include <windows.h>

#include "FrameChannelCore.h"

namespace srvcam {

class FrameChannel {
public:
    FrameChannel() = default;
    ~FrameChannel() { Close(); }
    FrameChannel(const FrameChannel&) = delete;
    FrameChannel& operator=(const FrameChannel&) = delete;

    // Open (or keep open) the mapping. False when the writer has not
    // created it yet or its header is not valid yet — both transient
    // at startup, so callers retry on their pacing tick.
    bool Open();
    void Close();
    bool IsOpen() const { return _view != nullptr; }

    // Current format snapshot; all-zero when closed/invalid.
    FrameFormat Format() const;
    // Copy the newest complete frame out (tight BGRA). See the core
    // header for the validation/retry semantics.
    bool ReadLatest(uint8_t* dest, size_t destCapacity, FrameView& out) const;

private:
    HANDLE _mapping = nullptr;
    const uint8_t* _view = nullptr;
};

}  // namespace srvcam
