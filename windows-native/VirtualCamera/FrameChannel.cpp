#include "FrameChannel.h"

namespace srvcam {

bool FrameChannel::Open() {
    if (_view != nullptr) return true;
    HANDLE mapping = OpenFileMappingW(FILE_MAP_READ, FALSE, kMappingName);
    if (mapping == nullptr) return false;
    const void* view = MapViewOfFile(mapping, FILE_MAP_READ, 0, 0, kTotalBytes);
    if (view == nullptr) {
        CloseHandle(mapping);
        return false;
    }
    _mapping = mapping;
    _view = static_cast<const uint8_t*>(view);
    if (!ChannelValid(_view)) {
        // The writer created the mapping but has not finished
        // initializing it (or it is corrupt). Treat as not-yet-open;
        // the next tick retries.
        Close();
        return false;
    }
    return true;
}

void FrameChannel::Close() {
    if (_view != nullptr) {
        UnmapViewOfFile(_view);
        _view = nullptr;
    }
    if (_mapping != nullptr) {
        CloseHandle(_mapping);
        _mapping = nullptr;
    }
}

FrameFormat FrameChannel::Format() const {
    if (_view == nullptr || !ChannelValid(_view)) return FrameFormat{};
    return ReadFormat(_view);
}

bool FrameChannel::ReadLatest(uint8_t* dest, size_t destCapacity,
                              FrameView& out) const {
    if (_view == nullptr) return false;
    return srvcam::ReadLatest(_view, dest, destCapacity, out);
}

}  // namespace srvcam
