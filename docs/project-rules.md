# CAT Rules

CAT adds bundled, user, and project rules to each supported coding-harness session at startup, resume, clear, and
compact. Use rules to state the decisions and practices that should remain available while an agent works.

## User Rules

Put personal CAT guidance in `~/.cat/rules`. This directory is outside every project, so CAT injects its applicable
rules without adding files to a repository or creating anything to commit. It uses the same layout and frontmatter as
project rules:

| Location | Use it for |
| --- | --- |
| `~/.cat/rules/common/` | Personal guidance with the same meaning for Claude Code and Codex. |
| `~/.cat/rules/claude/` | Personal guidance that depends on Claude Code behavior or configuration. |
| `~/.cat/rules/codex/` | Personal guidance that depends on Codex behavior or configuration. |

For example, create `~/.cat/rules/common/personal-style.md` for a rule you want in every CAT-enabled project. CAT
loads bundled rules first, then user rules, then repository rules. Keep a repository-specific rule in
`<project>/.cat/rules` when teammates need it; that rule is versioned and takes precedence when its instruction
conflicts with an earlier user rule. A missing `~/.cat/rules` directory is valid and simply contributes no rules.

CAT copies the user-rule tree into each isolated CAT runner home before starting the nested harness. This makes the same
personal rules available to nested sessions without granting them access to your real home directory.

## Add a Rule

### 1. Choose the right location

| Location | Use it for |
| --- | --- |
| `.cat/rules/common/` | Guidance with the same meaning for Claude Code and Codex. |
| `.cat/rules/claude/` | Guidance that depends on Claude Code behavior or configuration. |
| `.cat/rules/codex/` | Guidance that depends on Codex behavior or configuration. |

Keep common rules free of harness names, harness-specific locations, commands, environment variables, and configuration.
Put those details in the matching harness directory. A harness-specific rule may call on common guidance, but it should
not force the other harness to understand its integration details.

### 2. Create the rule

Create a Markdown file in the location you selected. Files without frontmatter are injected as direct guidance. Rule
frontmatter accepts only the `agents`, `depends-on` and `paths` keys; CAT refuses to load a rule with any other key.
For example, place a rule that applies to both harnesses in `.cat/rules/common/reviews.md`:

```markdown
# Reviews

Run the project's verification command before handing work back for review.
```

### 3. Scope the rule to relevant files

Add `paths` frontmatter when a rule applies only while an agent works on matching files. CAT tells the agent where to
load the rule rather than injecting its full text immediately.

```markdown
---
paths:
  - "**/*.java"
---
# Java Tests

Use TestNG for Java tests.
```

Use direct rules for guidance that should apply throughout a session. Use path-scoped rules only when file type makes
the rule relevant. Keep each rule focused, define project-specific terminology before using it, and avoid duplicating
rules that CAT already supplies.

### 4. Understand rule locations after installation

Bundled CAT rules are installed in the CAT plugin directory (usually the harness's plugin cache). CAT derives that
directory from the target harness's native plugin location; users do not configure it with a `CAT_*` variable. User and
project rules remain outside the installed plugin. They are separate filesystem roots:

| Rule kind | Location after installation |
| --- | --- |
| CAT common rule | `<installed CAT plugin>/rules/common/...` |
| CAT harness rule | `<installed CAT plugin>/rules/<harness>/...` |
| User common rule | `~/.cat/rules/common/...` |
| User harness rule | `~/.cat/rules/<harness>/...` |
| Project common rule | `<project workspace>/.cat/rules/common/...` |
| Project harness rule | `<project workspace>/.cat/rules/<harness>/...` |

When a rule names another file, name it from one of these roots instead of the rule's own directory, because an agent
reads the injected rule without knowing where it came from. The session context states each root's value:

| Write | For a file in |
| --- | --- |
| `${CAT_PLUGIN_ROOT}/rules/...` | the installed CAT plugin's rules |
| `${CAT_USER_RULES}/...` | the user rule directory, for example `${CAT_USER_RULES}/include/terms.md` |
| `${CAT_PROJECT_ROOT}/...` | the project workspace, for example `${CAT_PROJECT_ROOT}/.cat/rules/include/terms.md` |

CAT states `${CAT_USER_RULES}` only in sessions that have a user rule directory.

### 5. Order related rules

Use `depends-on` frontmatter when one direct project rule needs guidance defined by another direct project rule. Each
path is relative to the rule that declares it, using its location after installation. For example,
`.cat/rules/codex/reviews.md` can depend on `.cat/rules/common/terms.md`:

```markdown
---
depends-on:
  - ../common/terms.md
---
# Reviews

Apply the review terms defined by the common rule.
```

CAT injects the dependency before `reviews.md` and removes the frontmatter from the context. A `depends-on` path stays
within the declaring rule's filesystem root: a project rule cannot name an installed CAT rule, and an installed CAT
rule cannot name a project rule. A missing rule, a dependency outside its own tree, or a dependency cycle prevents CAT
from producing rule context.

Path-scoped rules are loaded only after an accessed file matches their `paths` setting. They cannot declare
`depends-on`, and direct rules cannot use them as `depends-on` targets, because they are not present when CAT injects
direct rules. State a path-scoped rule's loading condition instead.

## CAT's Rules

CAT injects every applicable bundled direct rule, then user direct rules, then project direct rules. User and project
rules can therefore use bundled guidance; project rules can add repository-specific instructions that override earlier
user rules. CAT does not guarantee an order among otherwise independent rules from the same source; use `depends-on`
whenever one rule needs another rule's definition or instruction. See [CAT's bundled default rules](default-rules.md)
for the supplied guidance.
