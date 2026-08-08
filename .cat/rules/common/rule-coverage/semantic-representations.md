# Semantic Representation Coverage

## Design Goals

- Find every independently editable representation of a governed value or relationship, not only its literal form.

## Guidance

When a requirement governs a semantic relationship rather than an exact token, derive candidates from that relationship
as well as literal matches. For a constant or property, enumerate every representation that formats, encodes, prefixes,
suffixes, or embeds it in names, paths, URLs, messages, configuration, or generated content. Classify whether each
representation derives from the authoritative value. Do not claim complete coverage from an exact-literal search when
independently editable derived representations can express the same concept.

For a constant or property used in configuration, inventory every value-bearing field before filtering candidates:
selectors and triggers, action or tool inputs, environment values, commands, names and titles, paths and globs,
download or upload patterns, and generated arguments. Include expression fragments and embedded shell. Do not exclude
CI, release,
packaging, or other build configuration as plumbing when its value must remain synchronized with an authoritative
source.
