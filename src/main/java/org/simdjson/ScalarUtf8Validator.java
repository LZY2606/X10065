package org.simdjson;

/**
 * The scalar (non-SIMD) UTF-8 validation path of stage1. It is the sequential reference
 * implementation that mirrors {@link Utf8Validator}; both report errors through the shared
 * {@link Utf8ErrorLocator}, so they always agree on the error offset.
 */
class ScalarUtf8Validator {

    static void validate(byte[] buffer, int length) {
        int errorOffset = Utf8ErrorLocator.locateFirstError(buffer, length);
        if (errorOffset >= 0) {
            throw new JsonParsingException("The input is not valid UTF-8", errorOffset);
        }
    }
}
