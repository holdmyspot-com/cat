# jOOQ query inferred

## Variables

- **country_code**: A two-letter country code whose customers the report lists, such as `CA` or `DE`

## Scenario

Our service uses jOOQ with generated table classes. The table `CUSTOMER` has the fields `NAME` (`String`),
`EMAIL` (`String`), `ORDER_COUNT` (`Integer`) and `COUNTRY` (`String`). Write a Java method that takes a `DSLContext`
and returns the name, email and order count of every customer in `{{country_code}}`. Before running the query, the
method logs its SQL at debug level through an SLF4J `Logger` field named `log`, using the query's `getSQL()`. Show only
the method.

## Assertions

### query_declared_by_inference

- **kind**: effect
- **description**: The method holds the select statement in a local variable declared with `var`, such as
	`var query = dsl.select(CUSTOMER.NAME, CUSTOMER.EMAIL, CUSTOMER.ORDER_COUNT).from(CUSTOMER).where(...)`, and does not
	declare it with an explicit type such as `SelectConditionStep<Record3<String, String, Integer>>`. It logs the query's
	SQL before fetching it, and returns the customers whose `COUNTRY` is `{{country_code}}`.

### guidance_selection

- **kind**: effect
- **description**: The retained events establish that the executor selects
	`rules/include/common/local-variable-types.md` before its first governed action.
