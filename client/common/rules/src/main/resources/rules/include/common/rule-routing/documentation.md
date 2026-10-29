# Documentation Rule Routing

## Design Goals

- Route each documentation-writing decision to the focused rule for that kind of document before the reader writes it.

## Guidance

When writing or editing a product, legal, or user-facing workflow document, an overview, release notes, or a changelog,
lazy load `../../documentation/standalone-documents.md`.

When writing or editing the documentation of a module, type, method, function, constructor, script, command, or
configuration entry, including API documentation and command help text, lazy load
`../../documentation/interface-contracts.md`.

When writing or editing a source comment, including a comment in a prompt or rule file, lazy load
`../../documentation/source-comments.md`.

When applying documentation conventions across existing code or documentation, lazy load
`../actionable-documentation.md`.
