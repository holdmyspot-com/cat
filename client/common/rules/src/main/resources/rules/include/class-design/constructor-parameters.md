# Constructor Parameters

## Design Goals

- When every call made on an instance passes a method parameter equal values, the instance receives that value once,
  when it is constructed, and its methods no longer accept it, so a caller cannot pass a different value by mistake.
  This applies only while every caller can change with the class, the value cannot change during the instance's
  lifetime, and it is known when the instance is constructed; any other parameter stays a method parameter.
- When work that does not change a class reveals such a parameter, the reader learns of it from a report instead of
  receiving an unrequested refactoring.

## Guidance

Evaluate a method parameter when defining or changing it, when adding a call that passes it the same value as the
method's other calls, and when reviewing or auditing its class. Group the method's calls by the instance they are made
on, and compare the values by equality, not identity: two equal strings or two equal records are the same value even
when they are different objects. When the method implements or overrides another, or several methods of the class
receive the same value in the same role, evaluate that whole group: every implementation, every method and every call.

Move the parameter into the constructor only when all of these hold:

- Every caller is in the code being changed and can change with it. A method that code outside the project can call,
  such as a public method of an exported or published API, keeps its parameter, because a caller you cannot see may
  pass different values.
- For each instance, every call passes equal values. Different instances may receive different values.
- The value cannot change during the instance's lifetime: it is immutable, or the instance keeps its own copy. A mutable
  value that callers update between calls, such as a list that gains elements or the caller's current state, stays a
  method parameter, because storing it would freeze or share that state.
- The value is known when the instance is constructed, and the code that constructs the instance can supply it. Keep
  the parameter when the value is computed only after construction, or when a framework constructs the instance and
  cannot supply the value.
- No shared instance, such as a singleton or a stateless service reused across sessions, serves callers that pass
  different values.

To move it, add a constructor parameter, store the value in a field, remove the parameter from every method in the
evaluated group, and pass the value at every place that constructs the class instead of at every call. When every call
on every instance passes the same value and the class can compute or name that value itself, make it a constant or a
field initializer instead of a constructor parameter.

When you notice such a parameter while doing work that does not otherwise change its class, do not change the class.
Report the class, the method and parameter, the calls that pass the equal values, and the conditions above that hold.

Verify a move by building and running the tests of every changed caller and constructor, and by confirming that no
call still passes the removed parameter.
