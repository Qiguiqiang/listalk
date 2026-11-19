// Sandbox/CI test for FrameChannelCore.h — the shared-memory frame
// protocol, writer and reader over one heap buffer standing in for the
// named mapping. This is the same layout the C# writer and the DLL
// reader exchange in production.
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>

#include "../FrameChannelCore.h"

static int g_failures = 0;
#define CHECK(cond)                                                     \
    do {                                                                \
        if (!(cond)) {                                                  \
            std::printf("FAIL %s:%d: %s\n", __FILE__, __LINE__, #cond); \
            ++g_failures;                                               \
        }                                                               \
    } while (0)

static void FillFrame(uint8_t* buf, uint32_t h, uint32_t stride, uint8_t seed) {
    for (uint32_t y = 0; y < h; ++y)
        for (uint32_t x = 0; x < stride; ++x)
            buf[y * stride + x] = static_cast<uint8_t>(seed + y * 7 + x);
}

int main() {
    auto* base = static_cast<uint8_t*>(std::malloc(srvcam::kTotalBytes));
    CHECK(base != nullptr);
    if (base == nullptr) return 1;

    // Uninitialized memory is not a valid channel.
    std::memset(base, 0x5A, srvcam::kTotalBytes);
    CHECK(!srvcam::ChannelValid(base));

    srvcam::WriterInit(base);
    CHECK(srvcam::ChannelValid(base));

    uint8_t dest[srvcam::kMaxFrameBytes > 4096 ? 4096 : srvcam::kMaxFrameBytes];
    srvcam::FrameView view;
    // No frames yet.
    CHECK(!srvcam::ReadLatest(base, dest, sizeof(dest), view));

    // Frame 1: 4x2, tight stride 16.
    uint8_t frame[16 * 2];
    FillFrame(frame, 2, 16, 0x10);
    uint64_t seq = srvcam::WriterPublish(base, frame, 4, 2, 16, 30, 90, true, 123456);
    CHECK(seq == 1);
    CHECK(srvcam::ReadLatest(base, dest, sizeof(dest), view));
    CHECK(view.seq == 1 && view.width == 4 && view.height == 2 && view.stride == 16);
    CHECK(view.timestampUs == 123456);
    CHECK(std::memcmp(dest, frame, sizeof(frame)) == 0);

    srvcam::FrameFormat fmt = srvcam::ReadFormat(base);
    CHECK(fmt.width == 4 && fmt.height == 2 && fmt.fps == 30 && fmt.rotation == 90 &&
          fmt.mirrored && fmt.active && fmt.latestSeq == 1);

    // Frame 2: different content lands in the other slot; reader sees it.
    FillFrame(frame, 2, 16, 0x40);
    seq = srvcam::WriterPublish(base, frame, 4, 2, 16, 30, 90, true, 123789);
    CHECK(seq == 2);
    CHECK(srvcam::ReadLatest(base, dest, sizeof(dest), view));
    CHECK(view.seq == 2 && std::memcmp(dest, frame, sizeof(frame)) == 0);

    // Frame 3: padded stride (16 bytes of pixels + 8 bytes of padding).
    uint8_t padded[24 * 2];
    FillFrame(padded, 2, 24, 0x70);
    seq = srvcam::WriterPublish(base, padded, 4, 2, 24, 30, 0, false, 124000);
    CHECK(seq == 3);
    CHECK(srvcam::ReadLatest(base, dest, sizeof(dest), view));
    CHECK(view.seq == 3 && view.stride == 16);
    for (uint32_t y = 0; y < 2; ++y)
        CHECK(std::memcmp(dest + y * 16, padded + y * 24, 16) == 0);

    // Oversize frames are refused, never truncated into the channel.
    CHECK(srvcam::WriterPublish(base, frame, srvcam::kMaxFrameWidth + 1, 2, 16, 30,
                                0, false, 1) == 0);
    CHECK(srvcam::WriterPublish(base, frame, 4, 2, 8, 30, 0, false, 1) == 0);
    fmt = srvcam::ReadFormat(base);
    CHECK(fmt.latestSeq == 3);  // refused publishes did not advance the seq

    // Format-only write (stop path): active flips off, frames stay.
    fmt.active = false;
    srvcam::WriterWriteFormat(base, fmt);
    fmt = srvcam::ReadFormat(base);
    CHECK(!fmt.active && fmt.latestSeq == 3);
    CHECK(srvcam::ReadLatest(base, dest, sizeof(dest), view) && view.seq == 3);

    // Corruption guard: if the slot carrying latestSeq loses its seq,
    // the reader reports no frame instead of returning torn data.
    uint8_t* slot = base + srvcam::SlotOffset(3 % srvcam::kSlotCount);
    srvcam::StoreU64(slot + srvcam::kSlotOffSeq, 999);
    CHECK(!srvcam::ReadLatest(base, dest, sizeof(dest), view));

    std::free(base);
    if (g_failures == 0) std::printf("CHANNEL ALL OK\n");
    return g_failures == 0 ? 0 : 1;
}
