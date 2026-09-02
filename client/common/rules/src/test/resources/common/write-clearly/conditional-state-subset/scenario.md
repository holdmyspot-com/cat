## Scenario

Make this release note precise without adding new requirements:

> If a tenant completed the data migration, its archived records are available through the new endpoint. Every tenant
> can retrieve its archived records through that endpoint. Tenants that have not completed the migration must continue
> using the existing endpoint.

## Assertions

### conditioned-subset-retained

The revised release note limits availability through the new endpoint to tenants that completed the migration and does
not claim that every tenant can use it.
