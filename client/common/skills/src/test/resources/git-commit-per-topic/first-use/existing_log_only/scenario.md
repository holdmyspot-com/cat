# Existing log only

## Variables

- **branch**: The branch whose existing commit subjects the user asks to see

## Scenario

Show the existing commit subjects on {{branch}}. I am not asking to rewrite or organize its history.

## Assertions

### no_discovery_required

- **kind**: guard
- **description**: The reply does not require topic discovery, reviewer evidence sidecars, a distribution build, or a
  rewrite before reporting the existing log.
