// Sandbox/CI test for Transform.cpp — pure BGRA rotation + mirror.
// Expectations below are hand-computed from the forward definitions in
// Transform.cpp; pixel value = pixel index in the B channel.
#include <cstdint>
#include <cstdio>
#include <cstring>

#include "../Transform.h"

static int g_failures = 0;
#define CHECK(cond)                                                     \
    do {                                                                \
        if (!(cond)) {                                                  \
            std::printf("FAIL %s:%d: %s\n", __FILE__, __LINE__, #cond); \
            ++g_failures;                                               \
        }                                                               \
    } while (0)

// 3x2 input, index grid:
//   0 1 2
//   3 4 5
static void MakeInput(uint8_t* buf) {
    for (int i = 0; i < 6; ++i) {
        buf[i * 4 + 0] = static_cast<uint8_t>(i);  // B = index
        buf[i * 4 + 1] = 0xA0;
        buf[i * 4 + 2] = 0xB0;
        buf[i * 4 + 3] = 0xFF;
    }
}

static void ExpectGrid(const char* name, const uint8_t* out, const int* expect,
                       int count) {
    for (int i = 0; i < count; ++i) {
        if (out[i * 4 + 0] != expect[i] || out[i * 4 + 3] != 0xFF) {
            std::printf("FAIL %s at pixel %d: got B=%d want %d\n", name, i,
                        out[i * 4 + 0], expect[i]);
            ++g_failures;
            return;
        }
    }
}

int main() {
    uint8_t in[6 * 4];
    uint8_t out[6 * 4];
    MakeInput(in);

    uint32_t ow, oh;
    srvcam::RotatedSize(3, 2, 0, ow, oh);
    CHECK(ow == 3 && oh == 2);
    srvcam::RotatedSize(3, 2, 90, ow, oh);
    CHECK(ow == 2 && oh == 3);
    srvcam::RotatedSize(3, 2, 270, ow, oh);
    CHECK(ow == 2 && oh == 3);
    srvcam::RotatedSize(3, 2, 180, ow, oh);
    CHECK(ow == 3 && oh == 2);

    static const int kRot0[] = {0, 1, 2, 3, 4, 5};
    static const int kRot0M[] = {2, 1, 0, 5, 4, 3};
    static const int kRot90[] = {3, 0, 4, 1, 5, 2};
    static const int kRot90M[] = {0, 3, 1, 4, 2, 5};
    static const int kRot180[] = {5, 4, 3, 2, 1, 0};
    static const int kRot180M[] = {3, 4, 5, 0, 1, 2};
    static const int kRot270[] = {2, 5, 1, 4, 0, 3};
    static const int kRot270M[] = {5, 2, 4, 1, 3, 0};

    srvcam::TransformBgra(in, 3, 2, 0, false, out);
    ExpectGrid("rot0", out, kRot0, 6);
    srvcam::TransformBgra(in, 3, 2, 0, true, out);
    ExpectGrid("rot0+mirror", out, kRot0M, 6);
    srvcam::TransformBgra(in, 3, 2, 90, false, out);
    ExpectGrid("rot90", out, kRot90, 6);
    srvcam::TransformBgra(in, 3, 2, 90, true, out);
    ExpectGrid("rot90+mirror", out, kRot90M, 6);
    srvcam::TransformBgra(in, 3, 2, 180, false, out);
    ExpectGrid("rot180", out, kRot180, 6);
    srvcam::TransformBgra(in, 3, 2, 180, true, out);
    ExpectGrid("rot180+mirror", out, kRot180M, 6);
    srvcam::TransformBgra(in, 3, 2, 270, false, out);
    ExpectGrid("rot270", out, kRot270, 6);
    srvcam::TransformBgra(in, 3, 2, 270, true, out);
    ExpectGrid("rot270+mirror", out, kRot270M, 6);

    // Unknown rotation behaves as 0.
    srvcam::TransformBgra(in, 3, 2, 45, false, out);
    ExpectGrid("rot45-as-0", out, kRot0, 6);

    if (g_failures == 0) std::printf("TRANSFORM ALL OK\n");
    return g_failures == 0 ? 0 : 1;
}
