// Builds payload frames in 1920x1080 gray pixel layout, one work-item per pixel.
//   dim 0 = pixel index inside the frame (row-major), dim 1 = frame inside the batch.
// Pixel 0..3 : big-endian frame index. Pixel 4.. : file bytes of this frame's chunk, then zero padding.
__kernel void build_payload_frames(
    __global const uchar* src,   // slice of the file starting at the batch's first chunk
    const long sliceLength,      // number of valid bytes in src
    const int firstFrame,        // payload frame index of the first frame in this batch
    const int maxPayload,        // bytes of file data per frame (frame capacity - 4)
    __global uchar* out)         // batchFrames * frameCapacity pixels
{
    const size_t pixel = get_global_id(0);
    const size_t frame = get_global_id(1);
    const size_t capacity = get_global_size(0);

    uchar value;
    if (pixel < 4) {
        value = (uchar)(((uint)(firstFrame + (int)frame)) >> (8 * (3 - (int)pixel)));
    } else {
        long offset = (long)frame * (long)maxPayload + (long)(pixel - 4);
        value = (offset < sliceLength) ? src[offset] : (uchar)0;
    }
    out[frame * capacity + pixel] = value;
}
