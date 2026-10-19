# Java Module Exports

## Design Goals

- Every type that an exported Java API element names in its signature is accessible to each module that can read the
	element, so that module can use the element without an access error or a "not exported from module" warning.
- Resolving an inaccessible type reference makes accessible only the types meant to be supported API, after their
	security and compatibility consequences have been evaluated, and makes no other type accessible as a side effect.

## Guidance

An API element is exported when its module's `module-info.java` exports its package, to every module or to the modules
that a qualified export names, and the element is public or protected in a public type. When you add or change such an
element, or the compiler or IDE reports that a type is not exported from a module, list every type that the element's
signature names: its superclass, implemented interfaces, type parameters and bounds, field and record component types,
return type, parameter types, and thrown exceptions. Each listed type must be in a package that its module exports to at
least every module that can read the element. When the type belongs to another module, this module must also declare
`requires transitive` for that module. A non-public element, or any element of a package that the module does not
export, needs no such check.

Resolve each inaccessible type with the first applicable change:

1. When no module outside this one needs the element, reduce its visibility or move it to a non-exported package.
2. When callers need only a capability of the type, name an accessible type that provides it instead, such as an
	exported interface that the type implements or a Java platform type. Add a new exported interface only when it is
	a narrower, stable contract that the callers need.
3. When callers need the type itself, export it. Move it to an exported package whose responsibility it fits, or, more
	rarely, export its current package. Use a qualified export when only named modules need it. A type that is not
	public must also become public.

Before making a type public or exporting a type or a package, list each type that the change makes accessible outside the module, including
the other public types of an exported package. Keep a type that callers are not meant to use out of the exported
package. For each type that will become accessible, check every member that callers can then reach: none may let a
caller bypass an invariant, read or replace a secret or credential, mutate shared internal state, or reach a file,
process, or network resource that the module means to control. Narrow those members, or choose another change, first.
Treat each newly exported type and its accessible members as a supported contract that later changes must preserve, and
check their own exported signatures under this rule.

Do not add `opens` to resolve this warning: it grants reflective access to non-public members, not the type access the
signature needs. Do not suppress the warning.

Verify the change by building every module that reads the changed API and confirming that the warning no longer
appears. Then compare the types accessible outside the module before and after the change with the types you intended
to make accessible.
