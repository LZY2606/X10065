package org.simdjson;

import jdk.incubator.vector.ByteVector;
import jdk.incubator.vector.VectorShuffle;

import static jdk.incubator.vector.ByteVector.SPECIES_256;
import static jdk.incubator.vector.VectorOperators.ULE;

/**
 * The 256-bit SIMD {@link BlockClassifier}. Each 64-byte block is processed as two 256-bit
 * chunks whose masks are stitched into a single 64-bit mask per character class.
 */
class Vector256BlockClassifier implements BlockClassifier {

    private final BlockClassification result = new BlockClassification();

    private static final byte BACKSLASH = (byte) '\\';
    private static final byte QUOTE = (byte) '"';
    private static final byte LAST_CONTROL_CHARACTER = (byte) 0x1F;
    private static final byte LOW_NIBBLE_MASK = 0x0f;
    private static final ByteVector WHITESPACE_TABLE = VectorUtils.repeat(ClassificationTables.WHITESPACE, SPECIES_256);
    private static final ByteVector OP_TABLE = VectorUtils.repeat(ClassificationTables.OP, SPECIES_256);

    @Override
    public BlockClassification classify(byte[] buffer, int offset) {
        ByteVector chunk0 = ByteVector.fromArray(SPECIES_256, buffer, offset);
        ByteVector chunk1 = ByteVector.fromArray(SPECIES_256, buffer, offset + 32);

        long backslash = chunk0.eq(BACKSLASH).toLong() | (chunk1.eq(BACKSLASH).toLong() << 32);
        long unescaped = chunk0.compare(ULE, LAST_CONTROL_CHARACTER).toLong()
                | (chunk1.compare(ULE, LAST_CONTROL_CHARACTER).toLong() << 32);
        long quote = chunk0.eq(QUOTE).toLong() | (chunk1.eq(QUOTE).toLong() << 32);

        VectorShuffle<Byte> chunk0Low = chunk0.and(LOW_NIBBLE_MASK).toShuffle();
        VectorShuffle<Byte> chunk1Low = chunk1.and(LOW_NIBBLE_MASK).toShuffle();

        long whitespace = chunk0.eq(WHITESPACE_TABLE.rearrange(chunk0Low)).toLong()
                | (chunk1.eq(WHITESPACE_TABLE.rearrange(chunk1Low)).toLong() << 32);

        ByteVector curlified0 = chunk0.or((byte) 0x20);
        ByteVector curlified1 = chunk1.or((byte) 0x20);
        long op = curlified0.eq(OP_TABLE.rearrange(chunk0Low)).toLong()
                | (curlified1.eq(OP_TABLE.rearrange(chunk1Low)).toLong() << 32);

        result.set(backslash, quote, unescaped, op, whitespace);
        return result;
    }
}
