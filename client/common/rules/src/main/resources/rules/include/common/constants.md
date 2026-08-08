# Constants

## Design Goals

- Centralize repeated constants behind meaningful names and one authoritative definition.
- Keep maintained resource-layout and protocol identifiers authoritative even before more than one consumer uses them.
- Keep constants in a readable, semantically meaningful form until an interface requires a different
  representation.

## Guidance

When the same meaningful value is inlined in multiple semantically coupled places, define it once as a clearly named
constant at the narrowest common layer that owns its meaning, then reference it everywhere it applies.

Treat a formatted, encoded, or otherwise derived representation as another use of its source value. Construct that
representation from the authoritative value instead of inlining its current result. For example, derive a release tag as
`v${VERSION}` from `VERSION`, rather than separately writing `"v1.0"`. Apply this to representations embedded in names,
paths, URLs, messages, configuration, and generated content whenever changing the source value must update the
representation.

Before defining or inlining a value, search the applicable owning scope, configuration object, and authoritative
provider. When one already resolves that value, reuse its accessor rather than defining a sibling constant or
reconstructing its literal representation at the consumer—even when the consumer currently knows the default—because
doing so bypasses later configuration and creates a competing definition.

Define a named constant at the owning layer for a literal that establishes a maintained resource-layout or protocol
identifier—such as a directory segment, persisted filename prefix, configuration key, or generated artifact name—even
when it currently has one use. That literal is an authoritative contract element for future creation, lookup, cleanup,
or documentation; do not leave it inline merely because a second consumer has not been added yet. This does not apply
to a one-off language- or library-required separator whose value is not part of a maintained contract.

- Extract only values whose repeated use expresses one shared concept and whose owner has authority to define it.
- Name a value for its meaning, not its representation or one caller's use.
- Keep a value in the smallest module, component, or harness that needs it. Promote it to a shared common layer only
  when both its value and meaning are harness-neutral.
- Keep harness-specific values in their harness-specific layer. Do not place them in common or create a common
  abstraction that exposes harness details.
- Do not extract a one-off, language- or protocol-required literal, value clearer at its use site, or coincidentally
  equal values that may evolve independently.

A constant is correct when changing its definition intentionally updates every reference governed by the same
concept, and changing one reference independently would be a defect.
