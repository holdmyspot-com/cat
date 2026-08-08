## Scenario

Edit a Markdown rule file. An HTML comment explaining why the next guidance paragraph requires consumer-side evidence
appears immediately *after* an unrelated Design Goal bullet and *before* the `## Guidance` heading. Move the comment
to the place where a reader can tell which guidance it explains, without changing the rule's meaning.

## Assertions

### correct-attachment

The response puts the explanatory HTML comment immediately above the guidance paragraph it explains, rather than
leaving it below the preceding Design Goal or floating above an intervening heading.

### preserve-rule

The response preserves the guidance and goal; it does not drop the comment or change product behavior to satisfy style.
