# Defining file linked

## Variables

- **function**: The name of the function that computes a shipping charge, such as `parcel_charge` or `courier_fee`

## Scenario

Where is `{{function}}` defined?

## Assertions

### markdown_link_with_line

- **kind**: effect
- **description**: The response refers to the defining file with a Markdown link whose label contains `rates.py`, enough
	of its directory to tell it from `src/pricing/rates.py`, such as `shipping/rates.py`, and the line number `4` after a
	colon, and whose target is that file's path. A bracketed label without a link target does not pass.
