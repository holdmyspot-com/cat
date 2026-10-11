# Format reuses glossary term

## Variables

- **term**: The glossary's term for a named slot in a prompt test's turns, in lower case, such as `variation dimension`
  or `test parameter`

## Scenario

You maintain a prompt-testing tool. Its design document has this glossary entry:

> **{{term}}**: a named slot in a prompt test's turns, written in double braces, with a list of values. A
> test case takes one value of each {{term}}.

The tool stores prompt tests as JSON with a `variations` array whose entries each have a `dimension` name and `values`.
Test authors now want to write each prompt test by hand as a Markdown file instead. Propose the Markdown format: show a
short example file with one test that has two such slots, and explain each section in a sentence.

## Assertions

### one_term_for_the_slot

- **kind**: effect
- **description**: The response uses one term for the named slot throughout its example and explanation. It either
  reuses the glossary's term or proposes a replacement that it says should also replace the glossary term, the JSON
  names, or both; it does not introduce a second name for the slot while leaving the glossary term in place.

### guidance_selection

- **kind**: effect
- **description**: The retained events establish that the executor selects
  `rules/include/common/consistent-terminology.md` before its first governed action.
