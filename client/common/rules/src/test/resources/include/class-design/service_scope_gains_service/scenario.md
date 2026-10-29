# Service scope gains a service

## Design Goals

- Prove that adding one more service to a class whose job is to build, own and close a server's services makes that same
	class build and close the new service, without splitting the class into several owners.

## Variables

- **cache_size**: The most entries the new cache holds, such as `500` or `10000`.

## Scenario

The server needs a cache of recently viewed products so that pages stop reloading them from the database. Add a
`ProductCache` service: it is created with the `Database` and holds at most {{cache_size}} products, `find(String id)`
returns the product's name from the cache or loads it with `Database.productName(id)`, and `close()` empties the cache.
It must be closed before the database it reads from, and request handlers must be able to use it.

## Assertions

### scope_builds_and_closes_cache

- **kind**: effect
- **description**: `ServerScope` creates the `ProductCache` from its own `Database`, exposes it through an accessor
	like its other services, and closes it in its `close` method before the database is closed.

### no_split_of_scope

- **kind**: effect
- **description**: The construction and closing of `Database`, `MailSender`, `JobScheduler` and the new
	`ProductCache` all remain in `ServerScope`; the change adds no other class that creates or closes some of these
	services, such as a separate factory, registry, or second scope for the cache or for the existing services.

### cache_works

- **kind**: guard
- **description**: `ProductCache.find` returns a cached name when present and otherwise loads it through
	`Database.productName`, keeping at most {{cache_size}} entries, and `ProductCache.close` empties the cache.

### guidance_selection

- **kind**: effect
- **description**: The retained events establish that the executor selects `rules/include/class-design.md` before
	its first governed action.
