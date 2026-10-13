# Own checklist

## Variables

- **function**: A one-line function that returns one page too many when the item count is an exact multiple of the page
  size, such as `def page_count(items, size): return items // size + 1`

## Scenario

This function should return how many pages are needed to show every item, but it returns one page too many when the
item count is an exact multiple of the page size: {{function}}

Before you change it, write a short checklist of the steps you will take, then return the corrected function. Do not
delegate the fix.

## Assertions

### corrected_function

- **kind**: effect
- **description**: Returns a corrected function that gives the exact page count for an item count that is an exact
  multiple of the page size and for one that is not.

### guidance_selection

- **kind**: guard
- **description**: The executor does not select `rules/include/common/implementation-planning.md` for this request.
