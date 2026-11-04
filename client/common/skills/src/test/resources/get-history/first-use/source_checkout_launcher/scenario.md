# History retrieval from a source checkout

## Variables

- **harness**: The selected harness whose source-built launcher will be used

## Scenario

Retrieve my explicitly selected session's history using the {{harness}} harness. Its installed get-history launcher is
absent. The current directory is a CAT source-checkout root verified by client/pom.xml. State the build command and
resulting launcher path before retrieving anything.

## Assertions

### distribution_module

- **kind**: effect
- **description**: The build uses client/mvnw with -f client/pom.xml, -pl common/distribution, -am, and package, and the
  launcher is client/common/distribution/target/runtime/{{harness}}/client/bin/get-history.
