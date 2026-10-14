# Construction Failure Cleanup

## Design Goals

- When code creates a resource that must be released and passes it to a constructor that fails, the code that created
  the resource releases it; after the constructor succeeds, the new object owns the resource and the caller does not
  release it separately.
- When a constructor fails, it releases each resource it created itself and leaves every resource its caller supplied
  unreleased for the caller to release.
- When releasing a resource after a construction failure also fails, the caller of the creating code still receives the
  construction failure, with the release failure attached to it.

## Guidance

This rule covers a resource that the program must release explicitly, such as an open file, network client,
connection, process, or lock. It treats a function that creates and returns a new object, such as a factory function
that returns an error instead of the object, the same as a constructor.

When code creates such a resource and passes it to a constructor, the new object owns the resource only after the
constructor returns successfully. If the constructor fails, do not assume that it released the resource or did anything
else: the code that created the resource still owns it and must release it before reporting the failure. After the
constructor succeeds, release the resource through the new object only.

Keep the resource's creation and its release after a failed construction in the same function. When the language
requires another constructor call to come first, such as a call to another constructor of the same class or to a
superclass constructor, and the resource is created as that call's argument, the calling constructor cannot release
the resource when the call fails. Create the resource and the object in a separate function that callers use instead.

When releasing the resource also fails, report the construction failure and attach the release failure to it, for
example as a suppressed exception. Do not replace the construction failure with the release failure or discard the
release failure.

A constructor that receives a resource from its caller must not release it when the constructor fails, even after
storing it or passing it to another constructor; the caller releases it. A constructor that creates a resource itself
owns it until construction succeeds: when the constructor fails, it releases each resource it created before reporting
the failure.

This rule does not govern a resource that the new object only borrows, such as a shared client whose owner keeps
releasing it, or a resource that the language moves into the call so the caller can no longer use it; for those, follow
the owner's or the language's ownership rules.
