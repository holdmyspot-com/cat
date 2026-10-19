# Field Initialization

## Design Goals

- When code declares an object's field or a constructor that assigns one, the field receives its initial value in its
	declaration whenever the language evaluates that declaration for each new object, every constructor would assign the
	same value, and computing it needs no constructor input, value the constructor computes, or earlier constructor
	statement; otherwise its assignment stays in the constructor.
- Moving an existing constructor assignment into a field declaration leaves each new object's field values and the
	order of the constructor's observable effects unchanged.

## Guidance

When declaring an object's field, or writing or editing a constructor that assigns one, initialize the field in its
declaration when all of the following hold:

- The language evaluates the declaration's initializer separately for each new object. A value that the language stores
	once and shares among every object, such as a class-level attribute in some languages, does not qualify when each
	object must own its value, such as a mutable collection.
- Every constructor would assign the field the same value.
- Computing the value needs no constructor parameter, no value that the constructor computes, and no statement that must
	run first, such as parameter validation.

Write the value in the declaration's own initializer, not in a separate initialization block.

When any of those conditions fails, keep the assignment in the constructor, including when computing the value needs
multi-step setup. For example, a field assigned from a constructor parameter, such as an owner's name, stays in the
constructor.

You may also move an existing constructor assignment that meets these conditions into its field's declaration while
editing that type, even when the task does not otherwise change the constructor. Before moving it, verify that the
field's value and the order of any side effects of computing it, relative to the other constructor statements, stay
the same.
