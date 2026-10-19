# Maven Project Properties

## Design Goals

- Maven builds locate the project's root directory through a publicly supported property, so they keep working when
	Maven changes its internal properties.

## Guidance

Do not use `${maven.multiModuleProjectDirectory}`. It is an internal, deprecated Maven property. Use
`${project.rootDirectory}`, its official, publicly supported replacement added in Maven 3.10.0. When a project's Maven
version, such as the one its Maven wrapper pins, is older than 3.10.0, upgrade Maven rather than falling back to the
internal property.
