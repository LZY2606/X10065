package org.simdjson;

/**
 * Scans one 64-byte block of the input and reduces it to raw character class bitmasks.
 * Implementations (SIMD 256-bit, SIMD 512-bit, scalar) are the parallel stage1 front-ends that
 * all feed the same structural index commit logic. Implementations own no cross-block state
 * (all cross-block state lives in {@link StructuralIndexerCarry}) and must produce
 * bit-identical {@link BlockClassification} results for the same input block; they may reuse a
 * single mutable result holder for every call.
 */
interface BlockClassifier {

    /**
     * Classifies the 64 bytes starting at {@code offset}. The caller guarantees that
     * {@code buffer.length - offset >= 64}; incomplete tails are padded by the caller.
     */
    BlockClassification classify(byte[] buffer, int offset);
}
