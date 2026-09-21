# Changelog

## Unreleased

### Stage1: shared SIMD/scalar structural index contract

#### What changed

The stage1 pipeline (UTF-8 validation + structural indexing) was refactored so that the
SIMD and scalar implementations are locked together by one shared contract instead of being
parallel copies of the same algorithm:

- `BlockClassification` (new) is the per-block intermediate result: raw bitmasks for
  backslash, quote, control-character, operator, and whitespace classes of one 64-byte block.
- `StructuralIndexerCarry` (new) is the cross-block carry: `prevInString`, `prevEscaped`,
  `prevScalar`, the delayed `prevStructurals` commit slot, and the error-localization state.
- `BlockClassifier` (new) is the block-scan front-end contract with three implementations:
  `Vector256BlockClassifier` and `Vector512BlockClassifier` (extracted, logic unchanged, from
  the old `index256`/`index512` methods) and the new `ScalarBlockClassifier` reference path.
  All three share the canonical lookup tables (`ClassificationTables`), so the table-driven
  character classes — including their deliberate false positives — cannot drift apart.
- `StructuralIndexer` now owns the single commit path: escape-parity resolution, prefix-XOR
  in-string mask, structural index emission order (one-block-delayed `BitIndexes.write`),
  space-padded tail-block handling, and error localization all exist exactly once and are
  used by every classifier.
  The carry and the classifier's result holder are reusable, indexer-owned scratch objects
  (reset at every `index(...)` call), so the 64-byte-block hot loop stays allocation-free and
  the original steady-state performance characteristic is preserved.
- UTF-8 validation gained a scalar path (`ScalarUtf8Validator`) next to the existing vector
  `Utf8Validator`. Both report errors through the shared `Utf8ErrorLocator`, which defines
  the canonical error offset: the first byte that begins an ill-formed sequence when decoding
  left to right (stray continuation, invalid/overlong lead, or lead of a truncated/out-of-range
  sequence). The locator runs only on the error path, so the happy-path cost is unchanged.
- `JsonParsingException` now carries the error offset as diagnostic context via a
  package-private accessor. Public API, exception messages, error priority (UTF-8 first, then
  unclosed string, then unescaped characters), and the `org.simdjson.species` feature
  detection are all unchanged.

#### Why

`StructuralIndexer` previously inlined the whole algorithm twice (`index256`, `index512`),
and the remainder-block epilogue of each copy already diverged subtly from its main loop
(no `prevEscaped` reset/overflow carry after the last block — harmless today, silent
divergence tomorrow). There was no scalar path at all, so the SIMD bitmask logic had no
independent oracle. Error reporting discarded all position information, which made
differential comparison of the paths impossible.

#### Coverage gaps closed

The new fixture `Stage1DifferentialContractTest` (runnable on its own via
`mvn -q test -Dtest=Stage1DifferentialContractTest`) compares the 256-bit SIMD, 512-bit SIMD,
and scalar paths on identical inputs and requires identical structural indexes, identical
error messages, and identical error offsets:

- random token soup and random raw bytes of random lengths (random block-boundary phases);
- quote/backslash runs of length 1..8 straddling the 64/128-byte block boundaries;
- injected illegal UTF-8 (lone continuations, overlong forms, surrogates, out-of-range leads,
  truncated sequences) at every offset of ASCII bases with lengths around block boundaries,
  with the error offset pinned to the injection offset;
- tail blocks of every length 1..63, both after a full block and as the whole input;
- unescaped control characters inside strings at boundary-crossing offsets, with pinned
  error offsets;
- unclosed strings, with the error offset pinned to the opening quote.

#### Adjacent-semantics regression protection

- All pre-existing tests (`StructuralIndexerTest`, `Utf8ValidationTest`, parsing tests) run
  unchanged against the refactored code, pinning exception messages byte-for-byte.
- The default constructor of `StructuralIndexer` keeps the exact species-based dispatch
  (256/512, `UnsupportedOperationException` otherwise), and `VectorUtils` species detection
  is untouched.
- The tail-block contract is unchanged: the incomplete final block is copied into the
  indexer-owned, space-padded `lastBlock` buffer and committed as a full block; ownership of
  `BitIndexes` (caller-provided) and of the padding buffer (indexer-owned) did not move.
- `BitIndexes` write order is unchanged: structurals of block *i* are committed while block
  *i+1* is processed, followed by one final write and `finish()`.

#### The most dangerous counterexample

An **odd-length backslash run ending exactly at the 64-byte block boundary, immediately
followed by a quote in the next block**. The escape-parity computation detects the run's
carry-out as a 64-bit addition overflow (`prevEscaped`); if that carry is dropped or
computed per-block, the quote at offset 64 is misclassified as unescaped, the string appears
to close one block early, and every downstream structural index and error offset shifts.
This is precisely the state that the old duplicated epilogue did not maintain.

Regression cases in `Stage1DifferentialContractTest`:

- `oddBackslashRunEndingAtBlockBoundaryEscapesQuoteInNextBlock` — `"` + 62×`x` + `\`
  (offset 63) + `"` (offset 64, escaped) + `y"` must yield exactly the structural index `[0]`
  and no error on all three paths;
- `evenBackslashRunEndingAtBlockBoundaryLeavesQuoteUnescaped` — the same shape with a
  two-backslash run must instead close the string at offset 64 and report
  `Unclosed string...` with error offset 66 (the later, genuinely unclosed quote) on all
  three paths.

#### Build

A Maven build (`pom.xml`) was added next to the Gradle build so that
`mvn -q -DskipTests package` and `mvn -q test` work from the repository root. It selects a
JDK 25 toolchain, compiles/tests with `--add-modules jdk.incubator.vector`, and defaults the
test JVM to `-Dorg.simdjson.species=256` (mirroring the Gradle `test256` variant; override
with `-Dsimdjson.test.species=512`). The `testdata/parse-number-fxx-test-data` directory
(same data the Gradle `downloadTestData` task fetches) is a local, git-ignored cache.
