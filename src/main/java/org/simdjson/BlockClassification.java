package org.simdjson;

/**
 * The intermediate result of scanning a single 64-byte block of the input. This is the shared
 * contract between the block scanners (SIMD and scalar) and the structural index commit logic.
 * Each field is a bitmask in which bit i corresponds to the byte at offset (block base + i).
 *
 * <p>The bitmasks describe raw, carry-independent character classes. Turning them into escaped
 * characters, in-string regions, and structural indexes requires the cross-block carry state
 * ({@link StructuralIndexerCarry}) and is performed exactly once, in a single place, by
 * {@link StructuralIndexer}.
 *
 * <p>The holder is mutable and is reused for every block by its owning {@link BlockClassifier},
 * so the block-scan hot loop stays allocation-free, just like the original single-path
 * indexer.
 */
final class BlockClassification {

    /** Bits set wherever the byte is a backslash. */
    long backslash;

    /** Bits set wherever the byte is a quote, regardless of whether it is escaped or not. */
    long quote;

    /** Bits set wherever the byte is a control character (unsigned value <= 0x1F). */
    long unescaped;

    /** Bits set wherever the byte is classified as a JSON operator ('{:},[]' modulo table false positives). */
    long op;

    /** Bits set wherever the byte is classified as whitespace. */
    long whitespace;

    void set(long backslash, long quote, long unescaped, long op, long whitespace) {
        this.backslash = backslash;
        this.quote = quote;
        this.unescaped = unescaped;
        this.op = op;
        this.whitespace = whitespace;
    }
}
