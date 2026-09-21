package org.simdjson;

public class JsonParsingException extends RuntimeException {

    private static final int UNKNOWN_OFFSET = -1;

    private final int errorOffset;

    JsonParsingException(String message) {
        this(message, UNKNOWN_OFFSET);
    }

    JsonParsingException(String message, Throwable throwable) {
        super(message, throwable);
        this.errorOffset = UNKNOWN_OFFSET;
    }

    JsonParsingException(String message, int errorOffset) {
        super(message);
        this.errorOffset = errorOffset;
    }

    /**
     * The offset in the input at which the error was detected, or -1 when the offset is unknown.
     * Kept package-private so that the public API surface remains unchanged; the stage1 contract
     * tests rely on it to assert that the SIMD and scalar paths localize errors identically.
     */
    int errorOffset() {
        return errorOffset;
    }
}
