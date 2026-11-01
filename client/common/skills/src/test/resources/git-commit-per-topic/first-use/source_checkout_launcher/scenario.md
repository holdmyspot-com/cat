# Topic replay from a source checkout

## Variables

- **harness**: The selected harness whose source-built launcher will be used

## Scenario

I want to organize this branch by topic using the {{harness}} harness. The installed git-topic-replay launcher is
absent. We are at a CAT source-checkout root verified by client/pom.xml. Tell me the build command and resulting
launcher path before starting review.

## Assertions

### distribution_module

- **kind**: effect
- **description**: The build uses client/mvnw with -f client/pom.xml, -pl common/distribution, -am, and package, and the
  launcher is client/common/distribution/target/jlink/{{harness}}/bin/git-topic-replay.
