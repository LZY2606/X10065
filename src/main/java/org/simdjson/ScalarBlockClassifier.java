package org.simdjson;

/**
 * The scalar (non-SIMD) {@link BlockClassifier}. It evaluates exactly the same table-driven
 * character classes as the vector classifiers, including their false positives, so that the
 * shared commit logic observes bit-identical intermediate results on both paths.
 */
class ScalarBlockClassifier implements BlockClassifier {

    private final BlockClassification result = new BlockClassification();

    private static final int BLOCK_SIZE = 64;
    private static final byte BACKSLASH = (byte) '\\';
    private static final byte QUOTE = (byte) '"';
    private static final int LAST_CONTROL_CHARACTER = 0x1F;
    private static final byte LOW_NIBBLE_MASK = 0x0f;
    private static final byte CURLIFY_MASK = 0x20;

    @Override
    public BlockClassification classify(byte[] buffer, int offset) {
        long backslash = 0;
        long quote = 0;
        long unescaped = 0;
        long op = 0;
        long whitespace = 0;
        for (int i = 0; i < BLOCK_SIZE; i++) {
            byte b = buffer[offset + i];
            long bit = 1L << i;
            if (b == BACKSLASH) {
                backslash |= bit;
            }
            if (b == QUOTE) {
                quote |= bit;
            }
            if ((b & 0xFF) <= LAST_CONTROL_CHARACTER) {
                unescaped |= bit;
            }
            if (ClassificationTables.WHITESPACE[b & LOW_NIBBLE_MASK] == b) {
                whitespace |= bit;
            }
            byte curlified = (byte) (b | CURLIFY_MASK);
            if (ClassificationTables.OP[b & LOW_NIBBLE_MASK] == curlified) {
                op |= bit;
            }
        }
        result.set(backslash, quote, unescaped, op, whitespace);
        return result;
    }
}
