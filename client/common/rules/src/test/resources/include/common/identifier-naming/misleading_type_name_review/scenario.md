# Misleading type name review

## Variables

- **store_class**: The name of a class that originally held only a session token and now persists all of a client's
	authentication state, such as `SessionFile`

## Scenario

Handle each of the following independent requests. Answer them in order and label each answer with its request number.

**Request 1.** Review this change to a command-line client before it is merged and report any design concerns.

Before the change, `{{store_class}}` saved and loaded the current session token. The change moves the rest of the
client's sign-in persistence into it, so it now stores the saved credentials, pending device-login attempts, cached
authorization results, and an unfinished logout that must resume on the next start. All of these share one on-disk
lock and one write path, and every caller uses them together while signing in or out.

```java
public final class {{store_class}}
{
	private final QueueFile pendingLogins;

	public Credentials loadCredentials() { ... }
	public void saveCredentials(Credentials credentials) { ... }
	public void addPendingLogin(DeviceLogin login) { ... }
	public Optional<Authorization> cachedAuthorization(Scope scope) { ... }
	public void cacheAuthorization(Scope scope, Authorization authorization) { ... }
	public Optional<LogoutProgress> unfinishedLogout() { ... }
	public void recordLogoutProgress(LogoutProgress progress) { ... }
}

/** A first-in, first-out queue of byte records stored in a file and guarded by a file lock. */
final class QueueFile { ... }

/** Polls the authorization server until a pending device login is approved, denied, or expires. */
public final class DeviceLoginPoller { ... }
```

**Request 2.** Review this change to the same client before it is merged and report any design concerns.

```diff
 public final class SyncManager
 {
 	public void retryUpload(Upload upload)
 	{
-		for (int attempt = 0; attempt <= maxAttempts; ++attempt)
+		for (int attempt = 0; attempt < maxAttempts; ++attempt)
```

## Assertions

### misleading_type_name_flagged

- **kind**: effect
- **description**: For request 1: The review reports that the name `{{store_class}}` no longer communicates the class's
	responsibility of persisting the client's authentication state, and proposes a name based on that responsibility,
	such as an authentication store.

### coherent_responsibility_retained

- **kind**: guard
- **description**: For request 1: The review does not recommend splitting `{{store_class}}` into separate classes as
	the correction for its name; it may raise a split only for a reason other than the name.

### mechanism_name_retained

- **kind**: guard
- **description**: For request 1: The review does not ask to rename `QueueFile`, whose file-backed queue is its
	responsibility.

### clear_name_retained

- **kind**: guard
- **description**: For request 1: The review does not ask to rename `DeviceLoginPoller`.

### unchanged_names_not_swept

- **kind**: guard
- **description**: For request 2: The review does not ask to rename `SyncManager` or any other type that the change
	does not add, rename, or give a different responsibility.

### guidance_selection

- **kind**: effect
- **description**: The retained events establish that the executor selects `rules/include/common/identifier-naming.md`
	before its first governed action.
