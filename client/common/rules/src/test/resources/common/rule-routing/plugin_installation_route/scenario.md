# Plugin Installation Route

## Design Goals

- Load installation guidance before acting on a direct plugin installation request, while leaving a prose-only
	correction free of installation prerequisites.

## Variables

- **request**: Either a direct request to install, update, repair, reinstall or uninstall an installed plugin, or a
	request only to correct the spelling of a plugin name in documentation without changing an installation

## Scenario

{{request}}

## Assertions

### installation_guidance_reached

- **kind**: effect
- **description**: For a request that changes an installation, retained tool events show that the agent loads the
	workflow routing index and its installation rule before invoking an installer or deciding that installation is
	unavailable. Loading another workflow companion, claiming to know the rule or quoting its filename is insufficient.

### prose_correction_stays_local

- **kind**: guard
- **description**: For a spelling-only request, the agent does not load installation guidance or investigate outside
	installation services merely because the text names a plugin or an update.
