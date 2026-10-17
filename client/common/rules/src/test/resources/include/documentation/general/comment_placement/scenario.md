# Comment placement

## Variables

- **input**: The external input that the Java method validates, such as `an external input` or
  `a user-supplied file path`

## Scenario

Handle each of the following independent requests. Answer them in order and label each answer with its request number.

**Request 1.** Edit a Markdown rule file. An HTML comment explaining why the next guidance paragraph requires consumer-side evidence
appears immediately *after* an unrelated Design Goal bullet and *before* the `## Guidance` heading. Move the comment
to the place where a reader can tell which guidance it explains, without changing the rule's meaning.

**Request 2.** Edit a Java source file. A standalone `//` comment explaining why a specific method validates {{input}} is
currently placed immediately after that method and before the next unrelated method. Put it where the intended
method's readers will find it without changing either method's behavior.

**Request 3.** Review a source-only Markdown rule that contains a valid `<!-- cat:include ../include/common/owned.md -->` directive
where the rule assembler expects it. It also has a same-line source comment describing the expression beside it.
Should either be relocated solely because explanatory comments normally belong above their subject?

## Assertions

### correct_attachment

- **kind**: effect
- **description**: For request 1: The response puts the explanatory HTML comment immediately above the guidance
  paragraph it explains, rather than leaving it below the preceding Design Goal or floating above an intervening
  heading.

### preserve_rule

- **kind**: guard
- **description**: For request 1: The response preserves the guidance and goal; it does not drop the comment or change
  product behavior to satisfy style.

### comment_follows_subject

- **kind**: effect
- **description**: For request 2: The comment moves directly above the method declaration it explains (including its
  annotations), rather than remaining after it or being mistaken for an explanation of the next method.

### no_mechanical_wide_move

- **kind**: guard
- **description**: For request 2: The response identifies the comment's actual subject instead of moving every comment
  in the file indiscriminately.

### preserve_syntax_bound_positions

- **kind**: guard
- **description**: For request 3: The response does not relocate the include directive or a clear same-line comment
  merely to impose the position rule for standalone explanatory comments. It distinguishes syntax-controlled placement
  from prose explaining a following paragraph or declaration.

### guidance_selection

- **kind**: effect
- **description**: The retained events establish that the executor selects `rules/include/documentation/general.md`
  before its first governed action.
