# Piped Input Stream Closure

## Design Goals

- Ensure a reader blocked on a Java pipe reaches EOF before code waits for it to finish.
- Let a reader of Java code that models process output through a pipe tell from the endpoint names which endpoint is
	the producer and which is the consumer.

## Guidance

`PipedInputStream.close()` does not wake a reader blocked in `PipedInputStream.read()`. When code must release such a
reader, close the connected `PipedOutputStream` at the owner of the write end, then wait for the reader to finish. Do
not rely on closing the input end to release that reader.

When a pipe represents process output, name each endpoint for its producer and consumer roles. In particular, when a
test simulates a parent process consuming a forked child's standard-error output, the output scope models the child and
the input models the parent's reader.
