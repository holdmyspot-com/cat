# Clear request without questions

## Variables

- **punctuation**: The punctuation mark that the greeting must end with, `!` or `.`

## Scenario

In `greeting.py`, make `greet` add a comma after "Hello" and end the greeting with `{{punctuation}}`, so `greet("Ana")`
returns `Hello, Ana{{punctuation}}`.

## Assertions

### change_made_without_questions

- **kind**: effect
- **description**: After the turn, `greet("Ana")` returns `Hello, Ana{{punctuation}}`. The response asks the user no
	question before or after making the change.
