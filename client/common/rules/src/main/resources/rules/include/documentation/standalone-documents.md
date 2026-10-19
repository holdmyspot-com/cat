---
depends-on:
  - ../documentation.md
---

# Standalone Documents

## Design Goals

- Let each section of a product, legal, or workflow document, an overview, release notes, or a changelog support the
	decision, right, obligation, or action that its reader brings to it, without the history of how the document was
	produced.
- Let every reader resolve a location that a document names: a checkout location from the project root and a
	user-owned location from the reader's own home directory.

## Guidance

Write each document section for its intended reader and the decision, right, obligation, or action that section must
support. Include information that helps the reader understand or use the supported contract. Keep authoring, release,
revision, packaging, and other document-management rationale out of reader-facing text unless it affects the reader's
rights, obligations, or use of the documented product.

Apply the same boundary to overviews, release notes, and changelogs. Describe the current reader-visible change and its
consequence; omit editing history, prior presentation, capture or rendering process, extraction provenance, and other
implementation details unless they change a reader's decision, action, right, obligation, or ability to verify a
documented claim. An intentional historical record may state a past product contract when that history itself is the
reader's subject; do not turn it into a record of how the document was produced.

Write a checkout location relative to the project root. Write a user-owned location with `~`, not an author-specific
absolute home directory. Runtime code resolves the same user-owned location from `HOME`. Preserve an absolute path only
when an intentional historical record needs the exact captured value as evidence.
