# No time in reply

## Variables

- **word**: An English word of five to nine letters, such as `lantern` or `whistle`

## Scenario

How many letters are in the word "{{word}}", and which of them appear more than once?

## Assertions

### letters_counted

- **kind**: effect
- **description**: The response gives the number of letters in "{{word}}" and names exactly the letters that appear more
	than once, or says that none do.

### guidance_selection

- **kind**: guard
- **description**: The executor does not select `rules/include/common/user-timezone.md` for this request.
