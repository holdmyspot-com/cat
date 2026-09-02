package io.github.cowwoc.cat.common.optimization;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.TemporaryDirectories;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.testng.annotations.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Tests strict operation-claim acquisition, renewal, and ownership verification.
 */
public final class OperationClaimDocumentTest
{
	private static final String SHA_A = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
	private static final String SHA_B = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";
	private static final String SHA_C = "cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc";
	private static final JsonMapper JSON = new JsonMapper();
	private static final Instant NOW = Instant.parse("2026-09-01T12:00:00Z");
	private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
	private static final UUID FIRST_OWNER = UUID.fromString("11111111-1111-1111-1111-111111111111");
	private static final UUID SECOND_OWNER = UUID.fromString("22222222-2222-2222-2222-222222222222");

	/**
	 * Creates the test fixture.
	 */
	public OperationClaimDocumentTest()
	{
	}

	/**
	 * Writes exact fields and blocks a different live owner.
	 *
	 * @throws IOException if the test fixture cannot be used
	 */
	@Test
	public void writesExactClaimAndBlocksLiveOwner() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			OperationDirectory directory = create(cleanup);

			OperationClaim acquired = OperationClaimDocument.tryAcquire(JSON, CLOCK, directory, FIRST_OWNER, 101).
				orElseThrow();
			JsonNode document = JSON.readTree(directory.claimPath().toFile());

			requireThat(fieldNames(document), "fields").containsExactly(List.of("schema_version", "operation_id",
				"owner_id", "process_id", "acquired_at", "renewed_at", "expires_at"));
			requireThat(document.get("owner_id").stringValue(), "ownerId").isEqualTo(FIRST_OWNER.toString());
			requireThat(document.get("process_id").longValue(), "processId").isEqualTo(101L);
			requireThat(acquired.expiresAt(), "expiresAt").isEqualTo(NOW.plusSeconds(30));
			requireThat(OperationClaimDocument.tryAcquire(JSON, CLOCK, directory, SECOND_OWNER, 202), "otherOwner").
				isEmpty();
		}
	}

	/**
	 * Lets the same owner renew and a different process replace an expired claim.
	 *
	 * @throws IOException if the test fixture cannot be used
	 */
	@Test
	public void replacesExpiredClaimAndRenewsOwnedClaim() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			OperationDirectory directory = create(cleanup);
			OperationClaim first = OperationClaimDocument.tryAcquire(JSON, CLOCK, directory, FIRST_OWNER, 101).
				orElseThrow();
			OperationClaim reacquired = OperationClaimDocument.tryAcquire(JSON,
				Clock.fixed(NOW.plusSeconds(10), ZoneOffset.UTC), directory, FIRST_OWNER, 101).orElseThrow();
			Clock later = Clock.fixed(NOW.plusSeconds(41), ZoneOffset.UTC);

			OperationClaim replacement = OperationClaimDocument.tryAcquire(JSON, later, directory, SECOND_OWNER, 202).
				orElseThrow();
			OperationClaim renewed = OperationClaimDocument.renew(JSON, Clock.fixed(NOW.plusSeconds(50), ZoneOffset.UTC),
				directory, replacement);

			requireThat(replacement.ownerId(), "replacementOwner").isEqualTo(SECOND_OWNER);
			requireThat(reacquired.acquiredAt(), "reacquiredAt").isEqualTo(first.acquiredAt());
			requireThat(reacquired.renewedAt(), "reacquiredRenewedAt").isEqualTo(NOW.plusSeconds(10));
			requireThat(replacement.acquiredAt(), "replacementAcquiredAt").isEqualTo(NOW.plusSeconds(41));
			requireThat(renewed.acquiredAt(), "renewedAcquiredAt").isEqualTo(replacement.acquiredAt());
			requireThat(renewed.renewedAt(), "renewedAt").isEqualTo(NOW.plusSeconds(50));
			requireThat(renewed.expiresAt(), "renewedExpiresAt").isEqualTo(NOW.plusSeconds(80));
			requireThat(first.ownerId(), "firstOwner").isEqualTo(FIRST_OWNER);
		}
	}

	/**
	 * Rejects malformed claims and claims for terminal operations.
	 *
	 * @throws IOException if the test fixture cannot be used
	 */
	@Test
	public void rejectsMalformedAndTerminalClaims() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			OperationDirectory malformed = create(cleanup);
			Files.writeString(malformed.claimPath(), "{\"schema_version\":1}");

			requireIo(() -> OperationClaimDocument.read(JSON, malformed));

			OperationDirectory terminal = create(cleanup);
			terminal.start(SHA_A);
			terminal.fail(OperationDirectory.FailureKind.PROTOCOL_FAILURE, "failed", false);
			requireIo(() -> OperationClaimDocument.tryAcquire(JSON, CLOCK, terminal, FIRST_OWNER, 101));
		}
	}

	/**
	 * Keeps a durable claim after closing and detects a later takeover.
	 *
	 * @throws IOException if the test fixture cannot be used
	 */
	@Test
	public void retainsClaimAfterCloseAndDetectsTakeover() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			OperationDirectory directory = create(cleanup);
			try (OperationClaim.Lease lease = OperationClaim.Lease.acquire(JSON, CLOCK, directory, FIRST_OWNER, 101).
				orElseThrow())
			{
				requireThat(OperationClaimDocument.tryAcquire(JSON, Clock.fixed(NOW.plusSeconds(31), ZoneOffset.UTC),
					directory, SECOND_OWNER, 202), "takeover").isPresent();

				requireIo(lease::verifyOwned);
			}
			requireThat(Files.isRegularFile(directory.claimPath()), "retainedClaim").isTrue();
		}
	}

	/**
	 * Lets exactly one of two simultaneous owners acquire the same durable operation claim.
	 *
	 * @throws IOException if the fixture cannot be created or an acquisition fails unexpectedly
	 * @throws InterruptedException if the test thread is interrupted
	 */
	@Test
	public void letsOnlyOneConcurrentOwnerAcquire() throws IOException, InterruptedException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			OperationDirectory directory = create(cleanup);
			CountDownLatch ready = new CountDownLatch(2);
			CountDownLatch start = new CountDownLatch(1);
			CountDownLatch complete = new CountDownLatch(2);
			AtomicInteger acquisitions = new AtomicInteger();
			AtomicReference<IOException> failure = new AtomicReference<>();
			startConcurrentAcquire(directory, FIRST_OWNER, 101, ready, start, complete, acquisitions, failure);
			startConcurrentAcquire(directory, SECOND_OWNER, 202, ready, start, complete, acquisitions, failure);

			requireThat(ready.await(10, TimeUnit.SECONDS), "ownersReady").isTrue();
			start.countDown();
			requireThat(complete.await(10, TimeUnit.SECONDS), "ownersComplete").isTrue();
			if (failure.get() != null)
				throw failure.get();
			requireThat(acquisitions.get(), "acquisitions").isEqualTo(1);
		}
	}

	/**
	 * Enforces immutable claim value invariants before persistence.
	 */
	@Test
	public void enforcesClaimValueInvariants()
	{
		requireRuntime(IllegalArgumentException.class, () -> new OperationClaim("operation", FIRST_OWNER, 1, NOW,
			NOW, NOW.plusSeconds(29)));
		requireRuntime(IllegalArgumentException.class, () -> new OperationClaim("operation", FIRST_OWNER, 0, NOW,
			NOW, NOW.plusSeconds(30)));
		requireRuntime(IllegalArgumentException.class, () -> new OperationClaim("operation", FIRST_OWNER, 1,
			NOW.plusSeconds(1), NOW, NOW.plusSeconds(30)));
	}

	/**
	 * Creates one pending operation directory.
	 *
	 * @param cleanup the test cleanup scope
	 * @return the pending operation directory
	 * @throws IOException if the fixture cannot be created
	 */
	private static OperationDirectory create(TemporaryDirectories cleanup) throws IOException
	{
		Path process = cleanup.add(Files.createTempDirectory("operation-claim-"));
		OperationKind kind = OperationKind.DISCOVER_PROMPT_SYSTEM;
		Path operation = OperationDirectory.path(process, 1, kind, 1);
		OptimizationContext context = new OptimizationContext(JSON, CLOCK, process, operation, kind, SHA_B, SHA_C);
		return OperationDirectory.create(JSON, CLOCK, context, 1, 1, new OptimizationInput()
		{
			@Override
			public OperationKind operationKind()
			{
				return kind;
			}

			@Override
			public String inputFingerprint()
			{
				return SHA_A;
			}
		});
	}

	/**
	 * Starts one concurrent claim attempt after both owners are ready.
	 *
	 * @param directory the shared pending operation
	 * @param ownerId the contender's owner identifier
	 * @param processId the contender's process identifier
	 * @param ready counts down after this contender is ready
	 * @param start releases both contenders
	 * @param complete counts down after this contender finishes
	 * @param acquisitions records successful claim acquisitions
	 * @param failure retains any unexpected I/O failure
	 */
	private static void startConcurrentAcquire(OperationDirectory directory, UUID ownerId, long processId,
		CountDownLatch ready, CountDownLatch start, CountDownLatch complete, AtomicInteger acquisitions,
		AtomicReference<IOException> failure)
	{
		Thread.startVirtualThread(() ->
		{
			ready.countDown();
			try
			{
				start.await();
				if (OperationClaimDocument.tryAcquire(JSON, CLOCK, directory, ownerId, processId).isPresent())
					acquisitions.incrementAndGet();
			}
			catch (IOException e)
			{
				failure.compareAndSet(null, e);
			}
			catch (InterruptedException e)
			{
				Thread.currentThread().interrupt();
				failure.compareAndSet(null, new IOException("Concurrent claim test was interrupted", e));
			}
			finally
			{
				complete.countDown();
			}
		});
	}

	/**
	 * Returns JSON field names in document order.
	 *
	 * @param document the JSON document
	 * @return its field names
	 */
	private static List<String> fieldNames(JsonNode document)
	{
		return new ArrayList<>(document.propertyNames());
	}

	/**
	 * Verifies that a call fails with an I/O error.
	 *
	 * @param operation the checked operation
	 * @throws IOException if the expected error is not thrown
	 */
	private static void requireIo(IoOperation operation) throws IOException
	{
		try
		{
			operation.run();
		}
		catch (IOException _)
		{
			return;
		}
		throw new IOException("Expected IOException");
	}

	/**
	 * Verifies that a call fails with the expected runtime type.
	 *
	 * @param type the expected exception type
	 * @param operation the checked operation
	 */
	private static void requireRuntime(Class<? extends RuntimeException> type, Runnable operation)
	{
		try
		{
			operation.run();
		}
		catch (RuntimeException expected)
		{
			requireThat(type.isInstance(expected), "exceptionType").isTrue();
			return;
		}
		throw new AssertionError("Expected " + type.getSimpleName());
	}

	/**
	 * Declares one operation that may fail with I/O.
	 */
	@FunctionalInterface
	private interface IoOperation
	{
		/**
		 * Runs the operation.
		 *
		 * @throws IOException if the operation fails
		 */
		void run() throws IOException;
	}
}
