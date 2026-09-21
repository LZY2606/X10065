package org.simdjson;

/**
 * The shared UTF-8 error localization used by both the SIMD and the scalar stage1 paths. The
 * validators decide *whether* the input is well-formed UTF-8; this locator decides *where* the
 * first error is, so both paths always report the same offset for the same input.
 *
 * <p>The contract: the error offset is the offset of the first byte that begins an ill-formed
 * sequence when the input is decoded left to right. That is the stray continuation byte, the
 * invalid or overlong lead byte, or the lead byte of a sequence whose continuation bytes are
 * missing (truncated at the end of the input) or out of range.
 */
final class Utf8ErrorLocator {

    private static final int MIN_CONTINUATION = 0x80;
    private static final int MAX_CONTINUATION = 0xBF;

    private Utf8ErrorLocator() {
    }

    /**
     * Returns the offset of the first byte that begins an ill-formed UTF-8 sequence, or -1 when
     * the input is well-formed UTF-8.
     */
    static int locateFirstError(byte[] buffer, int length) {
        int i = 0;
        while (i < length) {
            int lead = buffer[i] & 0xFF;
            if (lead < 0x80) {
                i++;
                continue;
            }
            int sequenceLength = sequenceLength(lead);
            if (sequenceLength < 0 || i + sequenceLength > length) {
                return i;
            }
            int minFirstContinuation = MIN_CONTINUATION;
            int maxFirstContinuation = MAX_CONTINUATION;
            switch (lead) {
                case 0xE0 -> minFirstContinuation = 0xA0; // exclude overlong 3-byte sequences
                case 0xED -> maxFirstContinuation = 0x9F; // exclude UTF-16 surrogates
                case 0xF0 -> minFirstContinuation = 0x90; // exclude overlong 4-byte sequences
                case 0xF4 -> maxFirstContinuation = 0x8F; // exclude code points above U+10FFFF
                default -> {
                }
            }
            int firstContinuation = buffer[i + 1] & 0xFF;
            if (firstContinuation < minFirstContinuation || firstContinuation > maxFirstContinuation) {
                return i;
            }
            for (int k = 2; k < sequenceLength; k++) {
                int continuation = buffer[i + k] & 0xFF;
                if (continuation < MIN_CONTINUATION || continuation > MAX_CONTINUATION) {
                    return i;
                }
            }
            i += sequenceLength;
        }
        return -1;
    }

    private static int sequenceLength(int lead) {
        if (lead >= 0xC2 && lead <= 0xDF) {
            return 2;
        }
        if (lead >= 0xE0 && lead <= 0xEF) {
            return 3;
        }
        if (lead >= 0xF0 && lead <= 0xF4) {
            return 4;
        }
        // Stray continuation bytes (0x80-0xBF), overlong 2-byte leads (0xC0-0xC1),
        // and leads that can only produce code points above U+10FFFF (0xF5-0xFF).
        return -1;
    }
}
