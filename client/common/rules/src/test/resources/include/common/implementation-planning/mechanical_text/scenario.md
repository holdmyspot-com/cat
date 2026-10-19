# Mechanical text

## Variables

- **sentence**: A sentence that starts with `Implementation plan:` and contains exactly one misspelled word, such as
	`Implementation plan: teh formatter will preserve all punctuation.`

## Scenario

Correct only the spelling error in this sentence and return the corrected sentence: {{sentence}}

## Assertions

### observable_decisions

- **kind**: guard
- **description**: Returns the corrected sentence and does not select or read the implementation-planning rule.
