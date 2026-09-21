package org.simdjson;

import java.util.Arrays;

/**
 * Produces the structural index for the input. The block scanning is delegated to a
 * {@link BlockClassifier} (SIMD 256-bit, SIMD 512-bit, or scalar), while the cross-block carry,
 * the structural index commit order, and the error localization live here exactly once. This
 * shared contract guarantees that all stage1 implementations produce identical structural
 * indexes and identical error offsets for the same input.
 */
class StructuralIndexer {

    private static final int STEP_SIZE = 64;
    private static final byte SPACE = 0x20;
    private static final long EVEN_BITS_MASK = 0x5555555555555555L;
    private static final long ODD_BITS_MASK = ~EVEN_BITS_MASK;
    private static final byte[] LAST_BLOCK_SPACES = new byte[STEP_SIZE];

    static {
        Arrays.fill(LAST_BLOCK_SPACES, SPACE);
    }

    private final BitIndexes bitIndexes;
    private final BlockClassifier classifier;
    private final byte[] lastBlock = new byte[STEP_SIZE];
    private final StructuralIndexerCarry carry = new StructuralIndexerCarry();

    StructuralIndexer(BitIndexes bitIndexes) {
        this(bitIndexes, defaultClassifier());
    }

    StructuralIndexer(BitIndexes bitIndexes, BlockClassifier classifier) {
        this.bitIndexes = bitIndexes;
        this.classifier = classifier;
    }

    private static BlockClassifier defaultClassifier() {
        int vectorBitSize = VectorUtils.BYTE_SPECIES.vectorBitSize();
        return switch (vectorBitSize) {
            case 256 -> new Vector256BlockClassifier();
            case 512 -> new Vector512BlockClassifier();
            default -> throw new UnsupportedOperationException("Unsupported vector width: " + vectorBitSize * 64);
        };
    }

    void index(byte[] buffer, int length) {
        bitIndexes.reset();
        resetCarry();

        int loopBound = length - (length % STEP_SIZE);
        int offset = 0;
        for (; offset < loopBound; offset += STEP_SIZE) {
            commitBlock(classifier.classify(buffer, offset), carry);
        }

        // The incomplete tail block is padded with spaces and processed as a full block. Spaces
        // are whitespace, so the padding contributes neither structurals nor errors.
        System.arraycopy(LAST_BLOCK_SPACES, 0, lastBlock, 0, lastBlock.length);
        System.arraycopy(buffer, offset, lastBlock, 0, length - offset);
        commitBlock(classifier.classify(lastBlock, 0), carry);

        bitIndexes.write(carry.blockIndex, carry.prevStructurals);
        bitIndexes.finish();
        if (carry.prevInString != 0) {
            throw new JsonParsingException(
                    "Unclosed string. A string is opened, but never closed.",
                    carry.lastOpeningQuoteOffset
            );
        }
        if (carry.unescapedCharsError != 0) {
            throw new JsonParsingException(
                    "Unescaped characters. Within strings, there are characters that should be escaped.",
                    carry.firstUnescapedCharErrorOffset
            );
        }
    }

    private void resetCarry() {
        carry.prevInString = 0;
        carry.prevEscaped = 0;
        carry.prevScalar = 0;
        carry.prevStructurals = 0;
        carry.unescapedCharsError = 0;
        carry.firstUnescapedCharErrorOffset = -1;
        carry.lastOpeningQuoteOffset = -1;
        carry.blockIndex = 0;
    }

    private void commitBlock(BlockClassification classification, StructuralIndexerCarry carry) {
        int blockBase = carry.blockIndex;

        // string scanning
        long backslash = classification.backslash;
        long escaped;
        if (backslash == 0) {
            escaped = carry.prevEscaped;
            carry.prevEscaped = 0;
        } else {
            backslash &= ~carry.prevEscaped;
            long followsEscape = backslash << 1 | carry.prevEscaped;
            long oddSequenceStarts = backslash & ODD_BITS_MASK & ~followsEscape;

            long sequencesStartingOnEvenBits = oddSequenceStarts + backslash;
            // Here, we check if the unsigned addition above caused an overflow. If that's the case, we store 1 in prevEscaped.
            // The formula used to detect overflow was taken from 'Hacker's Delight, Second Edition' by Henry S. Warren, Jr.,
            // Chapter 2-13.
            carry.prevEscaped = ((oddSequenceStarts >>> 1) + (backslash >>> 1) + ((oddSequenceStarts & backslash) & 1)) >>> 63;

            long invertMask = sequencesStartingOnEvenBits << 1;
            escaped = (EVEN_BITS_MASK ^ invertMask) & followsEscape;
        }

        long quote = classification.quote & ~escaped;
        long inString = prefixXor(quote) ^ carry.prevInString;

        // A quote opens a string when the byte preceding it is outside of any string. Tracking the
        // last opening quote localizes the "unclosed string" error without any extra pass.
        long openingQuotes = quote & ~((inString << 1) | (carry.prevInString & 1));
        if (openingQuotes != 0) {
            carry.lastOpeningQuoteOffset = blockBase + (Long.SIZE - 1 - Long.numberOfLeadingZeros(openingQuotes));
        }
        carry.prevInString = inString >> 63;

        // characters classification
        long scalar = ~(classification.op | classification.whitespace);
        long nonQuoteScalar = scalar & ~quote;
        long followsNonQuoteScalar = nonQuoteScalar << 1 | carry.prevScalar;
        carry.prevScalar = nonQuoteScalar >>> 63;
        long potentialScalarStart = scalar & ~followsNonQuoteScalar;
        long potentialStructuralStart = classification.op | potentialScalarStart;

        // finish
        bitIndexes.write(blockBase, carry.prevStructurals);
        carry.blockIndex = blockBase + STEP_SIZE;
        carry.prevStructurals = potentialStructuralStart & ~(inString ^ quote);

        long unescapedCharsError = classification.unescaped & inString;
        if (unescapedCharsError != 0 && carry.unescapedCharsError == 0) {
            carry.firstUnescapedCharErrorOffset = blockBase + Long.numberOfTrailingZeros(unescapedCharsError);
        }
        carry.unescapedCharsError |= unescapedCharsError;
    }

    private static long prefixXor(long bitmask) {
        bitmask ^= bitmask << 1;
        bitmask ^= bitmask << 2;
        bitmask ^= bitmask << 4;
        bitmask ^= bitmask << 8;
        bitmask ^= bitmask << 16;
        bitmask ^= bitmask << 32;
        return bitmask;
    }
}
