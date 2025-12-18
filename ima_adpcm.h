#ifndef IMA_ADPCM_H
#define IMA_ADPCM_H

#include <cstdint>

// Structure to hold the ADPCM state
struct ImaAdpcmState {
    int16_t predictor; // Predicted PCM sample
    int step_index;    // Index into the step_size_table

    ImaAdpcmState() : predictor(0), step_index(0) {}
};

// IMA ADPCM step size table
const int ima_step_table[89] = {
    7, 8, 9, 10, 11, 12, 13, 14, 16, 17,
    19, 21, 23, 25, 28, 31, 34, 37, 41, 45,
    50, 55, 60, 66, 73, 80, 88, 97, 107, 118,
    130, 143, 157, 173, 190, 209, 230, 253, 279, 307,
    337, 371, 408, 449, 494, 544, 598, 658, 724, 796,
    876, 963, 1060, 1166, 1282, 1411, 1552, 1707, 1878, 2066,
    2272, 2499, 2749, 3024, 3327, 3660, 4026, 4428, 4871, 5358,
    5894, 6484, 7132, 7845, 8630, 9493, 10442, 11487, 12635, 13899,
    15289, 16818, 18500, 20350, 22385, 24623, 27086, 29794, 32767
};

// IMA ADPCM index adjustment table
const int ima_index_table[16] = {
    -1, -1, -1, -1, 2, 4, 6, 8,
    -1, -1, -1, -1, 2, 4, 6, 8
};

// IMA ADPCM Encoder
uint8_t ima_adpcm_encode(int16_t pcm_sample, ImaAdpcmState& state) {
    long diff = (long)pcm_sample - state.predictor;
    uint8_t code = 0;
    if (diff < 0) {
        code = 8;
        diff = -diff;
    }

    int step = ima_step_table[state.step_index];
    int mask = 4;
    int vpdiff = step >> 3;

    while(mask) {
        if (diff >= step) {
            code |= mask;
            diff -= step;
            vpdiff += step;
        }
        step >>= 1;
        mask >>= 1;
    }

    if (code & 8) {
        state.predictor -= vpdiff;
    } else {
        state.predictor += vpdiff;
    }

    if (state.predictor > 32767) {
        state.predictor = 32767;
    } else if (state.predictor < -32768) {
        state.predictor = -32768;
    }

    state.step_index += ima_index_table[code];
    if (state.step_index < 0) {
        state.step_index = 0;
    } else if (state.step_index > 88) {
        state.step_index = 88;
    }

    	return code;
    }

// IMA ADPCM Decoder
int16_t ima_adpcm_decode(uint8_t code, ImaAdpcmState& state) {
    int step = ima_step_table[state.step_index];
    long diff = step >> 3;

    if (code & 4) diff += step;
    if (code & 2) diff += step >> 1;
    if (code & 1) diff += step >> 2;

    if (code & 8) {
        state.predictor -= diff;
    } else {
        state.predictor += diff;
    }

    if (state.predictor > 32767) {
        state.predictor = 32767;
    } else if (state.predictor < -32768) {
        state.predictor = -32768;
    }

    state.step_index += ima_index_table[code];
    if (state.step_index < 0) {
        state.step_index = 0;
    } else if (state.step_index > 88) {
        state.step_index = 88;
    }

    return state.predictor;
}

#endif // IMA_ADPCM_H