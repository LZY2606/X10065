package org.simdjson;

/**
 * The canonical character classification tables shared by all {@link BlockClassifier}
 * implementations. Defining them once ensures the SIMD and scalar paths cannot drift apart.
 */
final class ClassificationTables {

    /**
     * Maps the low nibble of a byte to the whitespace character it has to be equal to in order to
     * be classified as whitespace. Values different from any real whitespace character are sentinels
     * that never match.
     */
    static final byte[] WHITESPACE = {' ', 100, 100, 100, 17, 100, 113, 2, 100, '\t', '\n', 112, 100, '\r', 100, 100};

    /**
     * Maps the low nibble of a byte ORed with 0x20 to the character it has to be equal to in order
     * to be classified as a JSON operator. Zero entries never match.
     */
    static final byte[] OP = {0, 0, 0, 0, 0, 0, 0, 0, 0, 0, ':', '{', ',', '}', 0, 0};

    private ClassificationTables() {
    }
}
