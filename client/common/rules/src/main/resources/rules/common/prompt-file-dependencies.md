---
paths:
  - "**/rules/**/*.md"
---
# Prompt File Dependencies

## Design Goals

- Ensure a prompt file receives each definition it relies on before the file uses that definition.
- Keep dependency metadata out of default reader context while making its authoring contract available with a rule
	source file.
- Keep lazy routing direct and authoritative: one observable trigger reaches one target rule rather than a duplicate
	index or a routing-only intermediate.
- Keep the text that a direct rule shares across platforms in one file, so that an edit to it reaches the rule on every
	platform, while each platform still receives only its own commands.

## Guidance

Use YAML frontmatter to declare a direct rule's prompt-file prerequisites:

```yaml
---
depends-on:
  - ./intermediate-vs-terminal-goals.md
---
```

Each `depends-on` value is the rendered path to another direct rule, relative to the declaring rule. For example, a rule
in a harness directory can use `../common/terms.md` to depend on a common rule. The frontmatter itself is never
injected. For a rule injected at session start, the injector only places each listed prerequisite before the dependent
rule; it does not add a rule, so the prerequisite must itself be an injected rule, or injection fails. For a lazy-loaded
rule, the build turns the list into an injected instruction to lazy load each prerequisite first, and the agent loads
the prerequisites only by following that instruction.

This rule governs authoring injector metadata, not reader-facing workflow behavior. Keep it path-scoped to rule source
files and do not route it from an eagerly injected rule. The injector consumes a rule's `depends-on` frontmatter while it
assembles session context; it is not part of the context that the rule's reader receives.

Declare a dependency when a direct rule needs a term, contract, or instruction defined by another direct rule. A
dependency is not justified merely because the declaring rule contains `depends-on` frontmatter or because another rule
has the same metadata; its body must use the prerequisite's definition or instruction. A path-scoped rule cannot declare
`depends-on`, and a direct rule cannot name a path-scoped rule: the latter is loaded only after an accessed file matches
its `paths` setting. For a lazy-loaded reference, state the loading condition instead. Every dependency must remain
within its declaring rule's root and name a direct rule selected for the current harness. A missing dependency or
dependency cycle prevents the injector from producing context, so it cannot provide a rule whose required definition is
absent.

When a lazy-loaded rule needs another lazy-loaded rule, the target rule itself must state when to lazy load that
prerequisite before applying its guidance. A routing index must route only to the target rule whose observable trigger
matched; it must not preload the target rule's prerequisites. Route directly to the authoritative rule, not to a file
whose only independent guidance is another lazy-load instruction. Do not give two eagerly available indexes competing
routes to the same target or requirement unless their triggers select distinct reader decisions. This keeps the
dependency available when the target is reached directly or through another route.

Lazy load a rule once when its trigger first applies in a conversation, then reuse that loaded guidance for later
matching work. Read it again only after the conversation context resets or when the rule might have changed since it was
loaded.

Use `<!-- cat:include path -->` in a direct rule only to assemble source-only Markdown beneath the same rule root. The
loader expands that body before injection and rejects a body with frontmatter. A separately routable rule is a
prerequisite and must use `depends-on` instead. Verify that the injected context contains the expanded guidance and no
`cat:include` directive.

When a direct rule's text differs between platforms, write the shared text once and include each paragraph that
differs from a path that names `{platform}`, such as `<!-- cat:include platform/{platform}/shell.md -->`. The loader
replaces `{platform}` with `windows` on Windows and `posix` elsewhere, so give each fragment a file at the same path
under both folders.
