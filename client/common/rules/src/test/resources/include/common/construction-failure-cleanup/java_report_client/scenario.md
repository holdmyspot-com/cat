# Java report client

## Variables

- **client_class**: The name of a Java class that downloads reports over HTTP, such as `ReportClient`
- **cache_class**: The name of a Java class that holds an exclusive lock on a cache directory, such as `CacheDirectory`

## Scenario

Handle each of the following independent requests. Answer them in order and label each answer with its request number.

**Request 1.** Write a Java class `{{client_class}}` that implements `AutoCloseable` and downloads reports with a
`java.net.http.HttpClient`. Tests supply their own client through the constructor
`{{client_class}}(HttpClient client, URI baseUri)`, which throws `IllegalArgumentException` when `baseUri` is not
absolute. Production code has only a base URI, so also give callers a way to get an instance from just a `URI`, using a
new `HttpClient.newHttpClient()`. Add `String download(String name)`, which returns the body of `baseUri` resolved
against `name`, and `close()`, which closes the client. Show the whole class.

**Request 2.** Write a Java class `{{cache_class}}` that implements `AutoCloseable`. Its constructor takes a directory
`Path`, opens a `FileChannel` on the file `cache.lock` inside it, and takes an exclusive lock with `tryLock()`. When
another process already holds the lock, the constructor throws `IllegalStateException`. `close()` releases the lock and
closes the channel. Show the whole class.

## Assertions

### created_client_released_on_failure

- **kind**: effect
- **description**: For request 1: When the code that creates the `HttpClient` from a URI alone passes it to the
	constructor and that constructor throws, that same code closes the client and then lets the constructor's exception
	propagate.

### supplied_client_left_open

- **kind**: guard
- **description**: For request 1: The constructor that receives an `HttpClient` from its caller does not close that
	client when it rejects `baseUri`.

### construction_failure_reported

- **kind**: guard
- **description**: For request 1: When closing the client after a failed construction itself throws, the caller still
	receives the constructor's exception, with the close failure attached to it, such as by `addSuppressed`, instead of
	the close failure.

### successful_client_kept_open

- **kind**: guard
- **description**: For request 1: After the constructor succeeds, the code that created the client returns the new
	object without closing the client.

### own_channel_released_on_failure

- **kind**: effect
- **description**: For request 2: When the constructor cannot take the lock, it closes the `FileChannel` that it opened
	before the failure leaves the constructor.

### guidance_selection

- **kind**: effect
- **description**: The retained events establish that the executor selects
	`rules/include/common/construction-failure-cleanup.md` before its first governed action.
