package org.simdjson;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.simdjson.testutils.TestUtils.toUtf8;

/**
 * Differential contract test for stage1. The SIMD (256-bit and 512-bit) and scalar
 * implementations share the block-scan/carry contract, so for every input they must produce
 * identical structural indexes and identical errors (message and offset). This fixture is
 * deliberately self-contained: deterministic seeds, no network, no files on disk.
 */
public class Stage1DifferentialContractTest {

    private static final String UNCLOSED_STRING = "Unclosed string. A string is opened, but never closed.";
    private static final String UNESCAPED_CHARACTERS =
            "Unescaped characters. Within strings, there are characters that should be escaped.";
    private static final String INVALID_UTF8 = "The input is not valid UTF-8";

    private enum Stage1Path {
        VECTOR_256,
        VECTOR_512,
        SCALAR
    }

    private record Stage1Outcome(List<Integer> structuralIndexes, String errorMessage, int errorOffset) {
    }

    private static Stage1Outcome runStage1(Stage1Path path, byte[] buffer) {
        BitIndexes bitIndexes = new BitIndexes(buffer.length + 65);
        StructuralIndexer indexer = switch (path) {
            case VECTOR_256 -> new StructuralIndexer(bitIndexes, new Vector256BlockClassifier());
            case VECTOR_512 -> new StructuralIndexer(bitIndexes, new Vector512BlockClassifier());
            case SCALAR -> new StructuralIndexer(bitIndexes, new ScalarBlockClassifier());
        };
        try {
            if (path == Stage1Path.SCALAR) {
                ScalarUtf8Validator.validate(buffer, buffer.length);
            } else {
                Utf8Validator.validate(buffer, buffer.length);
            }
            indexer.index(buffer, buffer.length);
        } catch (JsonParsingException e) {
            return new Stage1Outcome(null, e.getMessage(), e.errorOffset());
        }
        List<Integer> structuralIndexes = new ArrayList<>();
        while (bitIndexes.hasNext()) {
            structuralIndexes.add(bitIndexes.getAndAdvance());
        }
        return new Stage1Outcome(structuralIndexes, null, -1);
    }

    private static void assertAllPathsAgree(byte[] input, String description) {
        Stage1Outcome vector256 = runStage1(Stage1Path.VECTOR_256, input);
        Stage1Outcome vector512 = runStage1(Stage1Path.VECTOR_512, input);
        Stage1Outcome scalar = runStage1(Stage1Path.SCALAR, input);
        assertThat(vector512).as("vector-512 vs vector-256 for %s", description).isEqualTo(vector256);
        assertThat(scalar).as("scalar vs vector-256 for %s", description).isEqualTo(vector256);
    }

    private static Stage1Outcome assertAllPathsFail(byte[] input, String expectedMessage, int expectedOffset) {
        for (Stage1Path path : Stage1Path.values()) {
            Stage1Outcome outcome = runStage1(path, input);
            assertThat(outcome.errorMessage()).as("error message for %s", path).isEqualTo(expectedMessage);
            assertThat(outcome.errorOffset()).as("error offset for %s", path).isEqualTo(expectedOffset);
        }
        return runStage1(Stage1Path.SCALAR, input);
    }

    // --- Random chunking: random lengths push block boundaries to random phases ---

    @ParameterizedTest
    @ValueSource(longs = {1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20})
    public void randomTokenSoupOfRandomLength(long seed) {
        Random random = new Random(seed);
        String[] tokens = {"\"", "\\", "{", "}", "[", "]", ":", ",", " ", "\t", "\n", "\r",
                "a", "1", "true", "null", "é", "漢", "😀", "\\\\", "\\\""};
        int length = random.nextInt(300);
        StringBuilder input = new StringBuilder();
        for (int i = 0; i < length; i++) {
            input.append(tokens[random.nextInt(tokens.length)]);
        }
        assertAllPathsAgree(toUtf8(input.toString()), "random token soup with seed " + seed);
    }

    @ParameterizedTest
    @ValueSource(longs = {101, 102, 103, 104, 105, 106, 107, 108, 109, 110})
    public void randomRawBytesOfRandomLength(long seed) {
        Random random = new Random(seed);
        byte[] input = new byte[random.nextInt(200)];
        random.nextBytes(input);
        assertAllPathsAgree(input, "random raw bytes with seed " + seed);
    }

    // --- Quotes and backslash runs straddling block boundaries ---

    @ParameterizedTest
    @ValueSource(ints = {64, 128})
    public void backslashRunStraddlingBlockBoundary(int boundary) {
        for (int runEnd = boundary - 2; runEnd <= boundary + 2; runEnd++) {
            for (int runLength = 1; runLength <= 8; runLength++) {
                int runStart = runEnd - runLength;
                if (runStart < 1) {
                    continue;
                }
                String input = "\"" + "a".repeat(runStart - 1)
                        + "\\".repeat(runLength) + "\""
                        + "b".repeat(3) + "\"";
                assertAllPathsAgree(toUtf8(input),
                        "backslash run [" + runStart + ", " + runEnd + ") of length " + runLength);
            }
        }
    }

    @Test
    public void quoteAtEveryPositionAroundBlockBoundary() {
        for (int quotePosition = 60; quotePosition <= 68; quotePosition++) {
            String input = "\"" + "a".repeat(quotePosition - 1) + "\"" + " " + "1";
            assertAllPathsAgree(toUtf8(input), "quote at position " + quotePosition);
        }
    }

    // --- Illegal UTF-8: identical error offsets on both paths ---

    @ParameterizedTest
    @ValueSource(ints = {1, 31, 63, 64, 65, 127})
    public void injectedInvalidUtf8Sequences(int baseLength) {
        byte[][] invalidSequences = {
                {(byte) 0x80},                                     // lone continuation
                {(byte) 0xC0, (byte) 0x80},                        // overlong 2-byte
                {(byte) 0xC1, (byte) 0xBF},                        // overlong 2-byte
                {(byte) 0xC2},                                     // truncated 2-byte lead
                {(byte) 0xE0, (byte) 0x80, (byte) 0x80},           // overlong 3-byte
                {(byte) 0xED, (byte) 0xA0, (byte) 0x80},           // UTF-16 surrogate
                {(byte) 0xE2, (byte) 0x82},                        // truncated 3-byte
                {(byte) 0xF0, (byte) 0x80, (byte) 0x80, (byte) 0x80}, // overlong 4-byte
                {(byte) 0xF4, (byte) 0x90, (byte) 0x80, (byte) 0x80}, // above U+10FFFF
                {(byte) 0xF5, (byte) 0x80, (byte) 0x80, (byte) 0x80}, // invalid lead
                {(byte) 0xF8},                                     // invalid lead
        };
        for (byte[] sequence : invalidSequences) {
            for (int offset = 0; offset <= baseLength; offset++) {
                byte[] input = new byte[baseLength + sequence.length];
                for (int i = 0; i < input.length; i++) {
                    input[i] = 'a';
                }
                System.arraycopy(sequence, 0, input, offset, sequence.length);
                assertAllPathsFail(input, INVALID_UTF8, offset);
            }
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2, 3, 62, 63, 64, 65, 126, 127, 128})
    public void truncatedMultibyteSequenceAtEndOfInput(int prefixLength) {
        byte[] input = new byte[prefixLength + 2];
        for (int i = 0; i < prefixLength; i++) {
            input[i] = 'a';
        }
        input[prefixLength] = (byte) 0xE2; // 3-byte lead missing its last continuation
        input[prefixLength + 1] = (byte) 0x82;
        assertAllPathsFail(input, INVALID_UTF8, prefixLength);
    }

    // --- Tail blocks of 1..63 bytes ---

    @ParameterizedTest
    @ValueSource(ints = {1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16,
            17, 18, 19, 20, 21, 22, 23, 24, 25, 26, 27, 28, 29, 30, 31, 32,
            33, 34, 35, 36, 37, 38, 39, 40, 41, 42, 43, 44, 45, 46, 47, 48,
            49, 50, 51, 52, 53, 54, 55, 56, 57, 58, 59, 60, 61, 62, 63})
    public void tailBlockOfAnyLengthAfterFullBlock(int tailLength) {
        Random random = new Random(1000 + tailLength);
        String[] tokens = {"\"", "\\", "{", "}", "[", "]", ":", ",", " ", "a", "1", "é"};
        StringBuilder input = new StringBuilder("{\"key\":\"value\",\"array\":[1,2,3,4,5,6,7,8,9,0]}");
        for (int i = 0; i < tailLength; i++) {
            input.append(tokens[random.nextInt(tokens.length)]);
        }
        assertAllPathsAgree(toUtf8(input.toString()), "64-byte prefix plus tail of " + tailLength);
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16,
            17, 18, 19, 20, 21, 22, 23, 24, 25, 26, 27, 28, 29, 30, 31, 32,
            33, 34, 35, 36, 37, 38, 39, 40, 41, 42, 43, 44, 45, 46, 47, 48,
            49, 50, 51, 52, 53, 54, 55, 56, 57, 58, 59, 60, 61, 62, 63})
    public void inputConsistingOnlyOfTailBlock(int length) {
        Random random = new Random(2000 + length);
        String[] tokens = {"\"", "\\", "{", "}", "[", "]", ":", ",", " ", "a", "1"};
        StringBuilder input = new StringBuilder();
        for (int i = 0; i < length; i++) {
            input.append(tokens[random.nextInt(tokens.length)]);
        }
        assertAllPathsAgree(toUtf8(input.toString()), "tail-only input of length " + length);
    }

    // --- Unescaped control characters inside strings: identical error offsets ---

    @ParameterizedTest
    @ValueSource(ints = {1, 31, 62, 63, 64, 65, 100, 126})
    public void unescapedControlCharacterInsideString(int controlCharPosition) {
        byte[] input = toUtf8("\"" + "a".repeat(controlCharPosition) + "b\"");
        input[controlCharPosition] = 0x01;
        assertAllPathsFail(input, UNESCAPED_CHARACTERS, controlCharPosition);
    }

    // --- Unclosed strings: identical error offsets, pinned to the opening quote ---

    @Test
    public void unclosedStringReportsOpeningQuoteOffset() {
        assertAllPathsFail(toUtf8("\"abc"), UNCLOSED_STRING, 0);
        assertAllPathsFail(toUtf8("\"a\" \"b"), UNCLOSED_STRING, 4);
        assertAllPathsFail(toUtf8("{\"key\":\"value"), UNCLOSED_STRING, 7);
    }

    // --- The most dangerous counterexample: an odd-length backslash run ending exactly at the
    // --- block boundary escapes the quote that starts the next block. Dropping the prevEscaped
    // --- carry flips the outcome from a valid string to a spurious unclosed-string error.

    @Test
    public void oddBackslashRunEndingAtBlockBoundaryEscapesQuoteInNextBlock() {
        // '"' at 0, 62 x 'x', '\' at 63, '"' at 64 (escaped), 'y' at 65, '"' at 66 (closing)
        byte[] input = toUtf8("\"" + "x".repeat(62) + "\\" + "\"" + "y" + "\"");
        for (Stage1Path path : Stage1Path.values()) {
            Stage1Outcome outcome = runStage1(path, input);
            assertThat(outcome.errorMessage()).as("no error for %s", path).isNull();
            assertThat(outcome.structuralIndexes()).as("structural indexes for %s", path)
                    .containsExactly(0);
        }
    }

    @Test
    public void evenBackslashRunEndingAtBlockBoundaryLeavesQuoteUnescaped() {
        // '"' at 0, 61 x 'x', '\\' at 62-63, '"' at 64 (closes the string), 'y' at 65, '"' at 66
        // (opens a string that is never closed)
        byte[] input = toUtf8("\"" + "x".repeat(61) + "\\\\" + "\"" + "y" + "\"");
        assertAllPathsFail(input, UNCLOSED_STRING, 66);
    }

    // --- Happy path pinned to hand-computed structural indexes ---

    @Test
    public void smallJsonDocumentProducesExpectedIndexesOnAllPaths() {
        byte[] input = toUtf8("{\"a\":1}");
        for (Stage1Path path : Stage1Path.values()) {
            Stage1Outcome outcome = runStage1(path, input);
            assertThat(outcome.errorMessage()).as("no error for %s", path).isNull();
            assertThat(outcome.structuralIndexes()).as("structural indexes for %s", path)
                    .containsExactly(0, 1, 4, 5, 6);
        }
    }
}
