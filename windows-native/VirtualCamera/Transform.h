#pragma once
// Orientation transform for virtual-camera frames (plan §B.1: the phone
// bakes NO transform into its frames; rotation + mirroring are applied
// once, here, on the PC).
//
// Semantics — defined here, verified against a real phone in stage 3:
// the input is the sensor-raw frame; `rotation` is the clockwise angle
// (degrees) the image must be rotated to appear upright; `mirrored`
// then flips the upright image horizontally (front-camera convention).
// Input and output are tightly packed BGRA. Pure functions over raw
// buffers — no Windows dependencies, unit-tested in the sandbox.

#include <cstdint>

namespace srvcam {

// Output dimensions after rotating a w×h frame by `rotation` degrees.
void RotatedSize(uint32_t w, uint32_t h, int32_t rotation, uint32_t& outW,
                 uint32_t& outH);

// Rotate `in` (w×h, tight BGRA) clockwise by `rotation` (0/90/180/270;
// other values behave as 0), then mirror horizontally if `mirrored`.
// `out` must hold RotatedSize(w,h,rotation) pixels × 4 bytes.
void TransformBgra(const uint8_t* in, uint32_t w, uint32_t h, int32_t rotation,
                   bool mirrored, uint8_t* out);

}  // namespace srvcam
