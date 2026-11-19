#include "Transform.h"

#include <cstring>

namespace srvcam {

void RotatedSize(uint32_t w, uint32_t h, int32_t rotation, uint32_t& outW,
                 uint32_t& outH) {
    if (rotation == 90 || rotation == 270) {
        outW = h;
        outH = w;
    } else {
        outW = w;
        outH = h;
    }
}

void TransformBgra(const uint8_t* in, uint32_t w, uint32_t h, int32_t rotation,
                   bool mirrored, uint8_t* out) {
    if (in == nullptr || out == nullptr || w == 0 || h == 0) return;
    uint32_t outW, outH;
    RotatedSize(w, h, rotation, outW, outH);
    // Per output pixel: undo the mirror in rotated space, then undo the
    // rotation to find the source pixel. Forward definitions:
    //   rot 90 CW : R(x,y) = in(y, h-1-x)        (R is outW=h wide)
    //   rot 180   : R(x,y) = in(w-1-x, h-1-y)
    //   rot 270 CW: R(x,y) = in(w-1-y, x)
    //   mirror    : F(x,y) = R(outW-1-x, y)
    for (uint32_t y = 0; y < outH; ++y) {
        for (uint32_t x = 0; x < outW; ++x) {
            const uint32_t rx = mirrored ? (outW - 1 - x) : x;
            const uint32_t ry = y;
            uint32_t sx, sy;
            switch (rotation) {
                case 90:  sx = ry;         sy = h - 1 - rx; break;
                case 180: sx = w - 1 - rx; sy = h - 1 - ry; break;
                case 270: sx = w - 1 - ry; sy = rx;         break;
                default:  sx = rx;         sy = ry;         break;
            }
            std::memcpy(out + (static_cast<size_t>(y) * outW + x) * 4,
                        in + (static_cast<size_t>(sy) * w + sx) * 4, 4);
        }
    }
}

}  // namespace srvcam
