package org.simdjson;

import jdk.incubator.vector.ByteVector;
import jdk.incubator.vector.VectorShuffle;

import static jdk.incubator.vector.ByteVector.SPECIES_512;
import static jdk.incubator.vector.VectorOperators.ULE;

/**
 * The 512-bit SIMD {@link BlockClassifier}. Each 64-byte block is processed as a single
 * 512-bit chunk.
 */
class Vector512BlockClassifier implements BlockClassifier {

    private final BlockClassification result = new BlockClassification();

    private static final byte BACKSLASH = (byte) '\\';
    private static final byte QUOTE = (byte) '"';
    private static final byte LAST_CONTROL_CHARACTER = (byte) 0x1F;
    private static final byte LOW_NIBBLE_MASK = 0x0f;
    private static final ByteVector WHITESPACE_TABLE = VectorUtils.repeat(ClassificationTables.WHITESPACE, SPECIES_512);
    private static final ByteVector OP_TABLE = VectorUtils.repeat(ClassificationTables.OP, SPECIES_512);

    @Override
    public BlockClassification classify(byte[] buffer, int offset) {
        ByteVector chunk = ByteVector.fromArray(SPECIES_512, buffer, offset);

        long backslash = chunk.eq(BACKSLASH).toLong();
        long unescaped = chunk.compare(ULE, LAST_CONTROL_CHARACTER).toLong();
        long quote = chunk.eq(QUOTE).toLong();

        VectorShuffle<Byte> chunkLow = chunk.and(LOW_NIBBLE_MASK).toShuffle();
        long whitespace = chunk.eq(WHITESPACE_TABLE.rearrange(chunkLow)).toLong();

        ByteVector curlified = chunk.or((byte) 0x20);
        long op = curlified.eq(OP_TABLE.rearrange(chunkLow)).toLong();

        result.set(backslash, quote, unescaped, op, whitespace);
        return result;
    }
}
