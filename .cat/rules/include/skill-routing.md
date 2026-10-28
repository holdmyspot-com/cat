# Skill Routing

## Design Goals

- Let a harness select the applicable skill from a user's request without reading that skill's implementation.

## Guidance

A skill's frontmatter `description` is routing metadata: name the user requests, verbs, and terms that should invoke the
skill, beginning with “Use when the user asks …”. Do not use it to summarize the skill's implementation or internal
workflow. Put that explanation in the skill body, where it helps after routing has selected the skill.
