## Java Declaration Order

## Design Goals

- Let a reader read each Java type top-down, with each new member placed where the existing order predicts.

## Guidance

Order declarations for top-down reading: type description, static fields, `main(String[] args)`, other public static
methods, non-static fields, constructors regardless of visibility, other public instance methods, then protected,
package-private, and private helpers. Order public methods by reader-facing conceptual or lifecycle flow. Order helpers
as a depth-first implementation narrative: place a caller before the helper that it introduces. Keep helpers shared by
multiple call paths after the implementation section they support rather than promoting them by call count. Keep related
overloads together.

Preserve existing compliant order. Insert a new public method at the relevant API or lifecycle point, and a new helper
after the first caller that introduces it, without reordering unrelated existing declarations. If an existing helper
gains another caller, retain its location unless it clearly interrupts the implementation narrative. Preserve the order
of existing peer methods and shared helpers. Use directional call frequency only when creating a new helper group with
no established order; never use it alone to reorder an established group.
