# Documentation Rule Routing

## Design Goals

- Route each documentation-writing decision to the focused rule for that kind of document before the reader writes it.

## Guidance

When writing or editing a product, legal, or user-facing workflow document, an overview, release notes, or a changelog,
lazy load `../../documentation/standalone-documents.md`.

When declaring a type, method, function, or constructor, creating a script, command, or configuration entry, or
changing the signature of one, or when writing or editing the documentation of a module, type, method, function,
constructor, script, command, or configuration entry, including API documentation and command help text, lazy load
`../../documentation/interface-contracts.md`.

When writing or editing a source comment, including a comment in a prompt or rule file, lazy load
`../../documentation/source-comments.md`.

When applying documentation conventions across existing code or documentation, lazy load
`../actionable-documentation.md`.

When writing an absolute path inside a user's home directory into documentation, lazy load `../environment.md`.
