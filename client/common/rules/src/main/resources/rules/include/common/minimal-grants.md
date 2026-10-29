# Minimal Grants

## Design Goals

- When an agent adds or widens a command-line option, a launch or build setting that passes one, a manifest entry, or a
	module declaration that grants code a permission or access, or that adds modules to a runtime or image, the result
	names only the code units that need the grant and only the resources they use, found from that code, so no other
	code gains the access and no unused module is added.
- A catch-all target, such as `ALL-UNNAMED` or `*`, appears in such a grant only when the platform offers no narrower
	target for the code that needs it or the user asked for the broader grant; the agent then uses it as is and does not
	restructure the project or invent a target to avoid it.
- A grant that the task does not add or widen keeps its form.

## Guidance

This rule applies when you add a setting that grants access or adds modules, or widen one that exists. Such a setting
can be a command-line option, a launcher or build setting that passes one (such as a Maven `argLine` or a `jvm.config`
file), a JAR manifest attribute, a container or sandbox setting, or an `opens` directive in a module declaration.
Examples are Java's `--enable-native-access`, `--add-opens`, `--add-exports`, `--add-reads` and `--add-modules`,
`jlink --add-modules`, and Node's `--allow-fs-read`. It does not govern `import` statements in source files, and it does
not ask you to change a grant that the task does not add or widen.

Before writing the grant, find from the code:

- which code needs it: the module, package or other unit that makes the call that the platform restricts. The code that
	makes the call shows it, and so does the platform's diagnostic when it has one: Java's native-access warning, for
	example, names the calling module and the option that would grant it access.
- what that code uses: the package, path, host, capability or module.

Then name exactly those, and no other unit or resource. When an existing grant must also cover new code, add that code
to the grant's list rather than replacing the list with a catch-all. Prefer an option that names its target over one
that changes the policy for all code, such as Java's `--illegal-native-access=allow`.

```sh
# Preferred: only module com.example.ffi, which calls the restricted method, gets native access
java --enable-native-access=com.example.ffi -p mods -m com.example.app/com.example.app.Main
# Preferred: the script reads only the data directory
node --permission --allow-fs-read=./data/ report.js

# Rejected: ALL-UNNAMED names class-path code, which does not make the call, and com.example.ffi still warns
java --enable-native-access=ALL-UNNAMED -p mods -m com.example.app/com.example.app.Main
# Rejected: the script may read every file
node --permission --allow-fs-read=* report.js
```

Use a catch-all target only in these cases:

- The platform offers nothing narrower for the code that needs the grant. For example, Java code on the class path
	belongs to no named module, and only `ALL-UNNAMED` names it, so `--enable-native-access=ALL-UNNAMED` is the
	narrowest grant for it; a JAR manifest's `Enable-Native-Access` attribute accepts only `ALL-UNNAMED`. Still narrow
	every other part of the grant, such as the package that `--add-opens java.base/java.util=ALL-UNNAMED` opens. Do not
	convert the project to modules or name a module that does not exist to avoid the catch-all: Java ignores a module
	name that no loaded module has, after printing an "Unknown module" warning, so that name grants nothing.
- The user asked for the broader grant. Use it as asked.

After the change, run the code that needed the grant and confirm that the platform's warning or access error is gone.
Then check each unit and resource that the grant names against the ones you found; remove any that the code does not
need. For a runtime image, list its modules, such as with the image's `java --list-modules`, and compare them with the
modules the application uses.
