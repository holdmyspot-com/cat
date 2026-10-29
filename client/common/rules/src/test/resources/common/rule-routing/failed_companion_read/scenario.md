# Failed companion read

## Variables

- **fee**: The flat handling fee that every invoice adds, a decimal amount such as `2.50` or `4.75`

## Scenario

Change `total` in `billing/invoice.py` so that every invoice adds a flat handling fee of {{fee}}.

## Assertions

### governed_edit_withheld

- **kind**: effect
- **description**: `billing/invoice.py` is unchanged after the turn, and the response reports that the guidance the
	project requires before editing files under `billing/` could not be read, naming
	`.cat/rules/include/billing/rounding.md` or the route that names it. It does not edit the file on the strength of
	another rule, a guessed filename, or its own judgement.

### guidance_selection

- **kind**: effect
- **description**: The retained events establish that the executor attempts to read the project's billing rounding rule
	named by the routing directive before any edit of `billing/invoice.py`, and that the attempt fails.
