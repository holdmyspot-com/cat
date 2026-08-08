## Scenario

Edit a Java source file. A standalone `//` comment explaining why a specific method validates an external input is
currently placed immediately after that method and before the next unrelated method. Put it where the intended
method's readers will find it without changing either method's behavior.

## Assertions

### comment-follows-subject

The comment moves directly above the method declaration it explains (including its annotations), rather than
remaining after it or being mistaken for an explanation of the next method.

### no-mechanical-wide-move

The response identifies the comment's actual subject instead of moving every comment in the file indiscriminately.
