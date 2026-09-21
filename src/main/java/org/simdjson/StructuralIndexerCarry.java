package org.simdjson;

/**
 * The cross-block carry state of stage1. This is the second half of the shared contract between
 * the block scanners and the structural index commit logic: every 64-byte block is scanned into a
 * {@link BlockClassification}, and the commit logic folds it into this carry, emitting structural
 * indexes and localizing errors exactly once for all scanner implementations.
 */
final class StructuralIndexerCarry {

    /** 0 or -1 (all ones): all ones when the previously processed block ends inside a string. */
    long prevInString;

    /** 0 or 1: 1 when the previously processed block ends with an odd-length backslash run. */
    long prevEscaped;

    /** 0 or 1: 1 when the previously processed block ends with a non-quote scalar character. */
    long prevScalar;

    /** Structural bits of the previously processed block, committed when the next block is processed. */
    long prevStructurals;

    /** Sticky accumulation of block-relative unescaped-character error bits. */
    long unescapedCharsError;

    /** Absolute offset of the first unescaped character inside a string, or -1 when none was seen. */
    int firstUnescapedCharErrorOffset = -1;

    /** Absolute offset of the most recent quote that opened a string, or -1 when none was seen. */
    int lastOpeningQuoteOffset = -1;

    /** Base offset of the block currently being processed. */
    int blockIndex;
}
