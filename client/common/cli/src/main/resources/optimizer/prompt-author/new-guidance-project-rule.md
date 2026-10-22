	Otherwise, put new guidance in a project rule, which CAT loads in trials and for the prompt's users. Add it to an
	existing rule under `.cat/rules/` on the same topic; when there is none, create a rule in `.cat/rules/common/`,
	or in `.cat/rules/claude/` or `.cat/rules/codex/` when the guidance depends on that harness. When the guidance
	applies only while the agent works on some files, start the rule with `paths` frontmatter whose patterns match
	those files, so that the rule loads only for that work:

	```markdown
	---
	paths:
	  - "**/*.java"
	---
	```

	Rule frontmatter accepts only the keys `agents`, `depends-on` and `paths`.
