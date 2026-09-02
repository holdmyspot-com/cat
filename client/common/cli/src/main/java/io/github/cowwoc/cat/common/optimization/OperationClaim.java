package io.github.cowwoc.cat.common.optimization;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * Identifies the local process that currently owns an operation.
 *
 * @param operationId the immutable operation identifier
 * @param ownerId the random identifier for the owning process instance
 * @param processId the positive operating-system process identifier
 * @param acquiredAt the first acquisition time
 * @param renewedAt the most recent renewal time
 * @param expiresAt the time at which another owner may take the claim
 */
public record OperationClaim(String operationId, UUID ownerId, long processId, Instant acquiredAt, Instant renewedAt,
	Instant expiresAt)
{
	static final Duration LEASE_DURATION = Duration.ofSeconds(30);
	private static final Duration RENEWAL_INTERVAL = Duration.ofSeconds(10);

	/**
	 * Validates one immutable claim.
	 */
	public OperationClaim
	{
		Objects.requireNonNull(operationId, "operationId must not be null");
		Objects.requireNonNull(ownerId, "ownerId must not be null");
		Objects.requireNonNull(acquiredAt, "acquiredAt must not be null");
		Objects.requireNonNull(renewedAt, "renewedAt must not be null");
		Objects.requireNonNull(expiresAt, "expiresAt must not be null");
		if (operationId.isBlank())
			throw new IllegalArgumentException("operationId must not be blank");
		if (processId <= 0)
			throw new IllegalArgumentException("processId must be positive");
		if (renewedAt.isBefore(acquiredAt))
			throw new IllegalArgumentException("renewedAt must not precede acquiredAt");
		if (!expiresAt.equals(renewedAt.plus(LEASE_DURATION)))
			throw new IllegalArgumentException("expiresAt must equal renewedAt plus 30 seconds");
	}

	/**
	 * Keeps a claim alive while its owner executes an operation.
	 */
	public static final class Lease implements AutoCloseable
	{
		private final JsonMapper json;
		private final Clock clock;
		private final OperationDirectory directory;
		private final ScheduledExecutorService renewer;
		private final AtomicReference<OperationClaim> claim;
		private final AtomicReference<IOException> renewalFailure = new AtomicReference<>();

		/**
		 * Creates and starts one owned lease.
		 *
		 * @param json the JSON codec
		 * @param clock the lease clock
		 * @param directory the claimed operation directory
		 * @param ownerId the random owner identifier
		 * @param processId the positive operating-system process identifier
		 * @return the acquired lease, or empty when another live owner holds it
		 * @throws IOException if the operation or claim record is invalid, terminal, or inaccessible
		 */
		public static Optional<Lease> acquire(JsonMapper json, Clock clock, OperationDirectory directory,
			UUID ownerId, long processId) throws IOException
		{
			Objects.requireNonNull(json, "json must not be null");
			Objects.requireNonNull(clock, "clock must not be null");
			Objects.requireNonNull(directory, "directory must not be null");
			Objects.requireNonNull(ownerId, "ownerId must not be null");
			if (processId <= 0)
				throw new IllegalArgumentException("processId must be positive");
			Optional<OperationClaim> acquired = OperationClaimDocument.tryAcquire(json, clock, directory, ownerId,
				processId);
			if (acquired.isEmpty())
				return Optional.empty();
			try
			{
				return Optional.of(new Lease(json, clock, directory, acquired.orElseThrow()));
			}
			catch (RuntimeException e)
			{
				throw new IOException("Cannot create operation-claim renewal task", e);
			}
		}

		/**
		 * Creates a lease and starts its independent daemon renewer.
		 *
		 * @param json the JSON codec
		 * @param clock the lease clock
		 * @param directory the claimed operation directory
		 * @param initialClaim the already acquired claim
		 */
		private Lease(JsonMapper json, Clock clock, OperationDirectory directory, OperationClaim initialClaim)
		{
			this.json = json;
			this.clock = clock;
			this.directory = directory;
			claim = new AtomicReference<>(initialClaim);
			renewer = Executors.newSingleThreadScheduledExecutor(new DaemonThreadFactory());
			renewer.scheduleAtFixedRate(this::renewInBackground, RENEWAL_INTERVAL.toSeconds(),
				RENEWAL_INTERVAL.toSeconds(), TimeUnit.SECONDS);
		}

		/**
		 * Returns the most recently acquired or renewed claim.
		 *
		 * @return the current claim
		 */
		public OperationClaim claim()
		{
			return claim.get();
		}

		/**
		 * Confirms that this lease has not failed, expired, or been replaced.
		 *
		 * @throws IOException if renewal failed or this process no longer owns the claim
		 */
		public void verifyOwned() throws IOException
		{
			IOException failure = renewalFailure.get();
			if (failure != null)
				throw failure;
			OperationClaim expected = claim.get();
			OperationClaim persisted = OperationClaimDocument.read(json, directory).orElseThrow(() ->
				new IOException("Operation claim is absent: " + directory.claimPath()));
			if (!sameOwner(expected, persisted) || persisted.expiresAt().compareTo(clock.instant()) <= 0)
			{
				throw new IOException("Operation claim is no longer owned by this process: " + directory.claimPath());
			}
		}

		/**
		 * Renews the lease synchronously for deterministic tests and local recovery.
		 *
		 * @throws IOException if the claim cannot be renewed
		 */
		void renewNow() throws IOException
		{
			try
			{
				claim.set(OperationClaimDocument.renew(json, clock, directory, claim.get()));
			}
			catch (IOException e)
			{
				renewalFailure.compareAndSet(null, e);
				renewer.shutdown();
				throw e;
			}
		}

		/**
		 * Stops renewal without deleting the durable claim.
		 */
		@Override
		public void close()
		{
			renewer.shutdownNow();
		}

		/**
		 * Renews independently from the owner thread.
		 */
		private void renewInBackground()
		{
			try
			{
				renewNow();
			}
			catch (IOException _)
			{
				// renewNow retains the failure and stops later scheduling.
			}
		}

		/**
		 * Returns whether two claims have the same owner identity.
		 *
		 * @param expected the retained claim
		 * @param actual the persisted claim
		 * @return whether both claims identify the same owner and operation
		 */
		private static boolean sameOwner(OperationClaim expected, OperationClaim actual)
		{
			return expected.operationId().equals(actual.operationId()) && expected.ownerId().equals(actual.ownerId()) &&
				expected.processId() == actual.processId();
		}

		/** Creates daemon threads that cannot keep a stopped harness process alive. */
		private static final class DaemonThreadFactory implements ThreadFactory
		{
			@Override
			public Thread newThread(Runnable runnable)
			{
				Thread result = new Thread(runnable, "cat-operation-claim-renewer");
				result.setDaemon(true);
				return result;
			}
		}
	}
}
