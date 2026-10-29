# Exact header spelling required

## Variables

- **run_id**: The run identifier in the example request, such as `r-20481` or `nightly-7`

## Scenario

Implement `run_id` in `gateway/run_id.py`. It receives the raw head of an HTTP/1.1 request. Our gateway contract says the
run identifier arrives in a header field spelled exactly `X-Run-ID`: a head containing `X-Run-ID: {{run_id}}` returns
`{{run_id}}`, but a head whose only such field is spelled `x-run-id` is a client error and must raise `ValueError`, even
though both spellings name the same field to most HTTP software.

## Assertions

### exact_spelling_kept

- **kind**: effect
- **description**: The delivered `run_id` returns `{{run_id}}` for a head that contains `X-Run-ID: {{run_id}}` and raises
	`ValueError` for a head whose only such field is spelled `x-run-id`; the parsing step it uses keeps the field name's
	original spelling available to that check.

### guidance_selection

- **kind**: effect
- **description**: The retained events establish that the executor selects `rules/include/common/prefer-reuse.md`, then
	`rules/include/common/preserve-required-distinctions.md`, before its first governed action.
