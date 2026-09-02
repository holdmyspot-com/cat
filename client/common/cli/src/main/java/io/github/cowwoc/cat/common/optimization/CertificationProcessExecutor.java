package io.github.cowwoc.cat.common.optimization;

import io.github.cowwoc.cat.common.Fingerprints;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import tools.jackson.databind.json.JsonMapper;

/**
 * Advances a preinitialized certification process through durable local operation receipts.
 */
public final class CertificationProcessExecutor
{
	private final OptimizationHandlers handlers;
	private final OptimizationInputResolver inputResolver;
	private final UUID ownerId;
	private final long processId;

	/**
	 * Creates an executor with one stable local process owner identity.
	 *
	 * @param handlers the complete operation-handler registry
	 * @param inputResolver the typed operation-input resolver
	 * @param ownerId the random owner identity
	 * @param processId the positive operating-system process identifier
	 */
	public CertificationProcessExecutor(OptimizationHandlers handlers, OptimizationInputResolver inputResolver,
		UUID ownerId, long processId)
	{
		this.handlers = Objects.requireNonNull(handlers, "handlers must not be null");
		this.inputResolver = Objects.requireNonNull(inputResolver, "inputResolver must not be null");
		this.ownerId = Objects.requireNonNull(ownerId, "ownerId must not be null");
		if (processId <= 0)
			throw new IllegalArgumentException("processId must be positive");
		this.processId = processId;
	}

	/**
	 * Runs a preinitialized certification process until its registered terminal result is durable.
	 *
	 * @param json the operation-owned JSON codec
	 * @param clock the operation clock
	 * @param certificationProcessDirectory the existing certification-process directory
	 * @param contractFingerprint the expected immutable contract fingerprint
	 * @param environmentFingerprint the expected immutable environment fingerprint
	 * @return the terminal state, sequence, and observed operation receipts
	 * @throws IOException if the process records, operation receipt, claim, input, or handler cannot be verified
	 */
	public ExecutionResult run(JsonMapper json, Clock clock, Path certificationProcessDirectory,
		String contractFingerprint, String environmentFingerprint) throws IOException
	{
		Objects.requireNonNull(json, "json must not be null");
		Objects.requireNonNull(clock, "clock must not be null");
		Objects.requireNonNull(certificationProcessDirectory, "certificationProcessDirectory must not be null");
		contractFingerprint = requireSha256(contractFingerprint, "contractFingerprint");
		environmentFingerprint = requireSha256(environmentFingerprint, "environmentFingerprint");
		Path processDirectory = certificationProcessDirectory.toAbsolutePath().normalize();
		Path manifest = processDirectory.resolve("certification-process.json");
		if (!Files.isRegularFile(manifest, LinkOption.NOFOLLOW_LINKS))
			throw new IOException("Certification process manifest is missing: " + manifest);

		List<OperationDirectory.Receipt> receipts = new ArrayList<>();
		while (true)
		{
			CertificationProcessLedger.Snapshot process = CertificationProcessLedger.read(json, manifest);
			requireIdentity(process, contractFingerprint, environmentFingerprint, manifest);
			if (process.state().terminal())
				return result(process, receipts);
			OperationKind operation = process.state().currentOperation().orElseThrow();
			long transitionSequence = process.state().sequence() + 1;
			try
			{
				OperationDirectory.Receipt retained = advanceOperation(json, clock, processDirectory, manifest, process,
					operation, transitionSequence, contractFingerprint, environmentFingerprint, receipts);
				receipts.add(retained);
			}
			catch (RetryableOperationException _)
			{
				// advanceOperation already retained this failed receipt before asking the process loop to retry.
			}
		}
	}

	/**
	 * Recovers or executes the operation selected by the replayed certification-process state.
	 *
	 * @param json the JSON codec
	 * @param clock the operation clock
	 * @param processDirectory the certification-process directory
	 * @param manifest the certification-process manifest
	 * @param process the replayed process state
	 * @param operation the selected operation
	 * @param transitionSequence the selected one-based transition sequence
	 * @param contractFingerprint the contract fingerprint
	 * @param environmentFingerprint the environment fingerprint
	 * @param receipts durable receipts observed by this run
	 * @return the durable completed receipt
	 * @throws IOException if recovery or execution fails
	 */
	private OperationDirectory.Receipt advanceOperation(JsonMapper json, Clock clock, Path processDirectory,
		Path manifest, CertificationProcessLedger.Snapshot process, OperationKind operation, long transitionSequence,
		String contractFingerprint, String environmentFingerprint, List<OperationDirectory.Receipt> receipts)
		throws IOException
	{
		OperationDirectory directory = latestDirectory(json, clock, processDirectory, transitionSequence, operation);
		int attempt = 1;
		if (directory != null)
		{
			Optional<OperationDirectory.Receipt> terminal = directory.verifiedTerminalReceipt();
			if (terminal.isPresent())
			{
				OperationDirectory.Receipt receipt = terminal.orElseThrow();
				if (receipt.state() == OperationState.COMPLETED)
				{
					CertificationProcessLedger.recordReceipt(json, manifest, process.state().sequence(), directory);
					return receipt;
				}
				receipts.add(receipt);
				if (!mayRetry(operation, receipt))
					throw failed(receipt, receipts, null);
				attempt = receipt.attempt() + 1;
				directory = null;
			}
		}

		if (directory != null)
			attempt = directory.manifest().attempt();
		Path predicted = OperationDirectory.path(processDirectory, transitionSequence, operation, attempt);
		OptimizationContext context = new OptimizationContext(json, clock, processDirectory, predicted, operation,
			contractFingerprint, environmentFingerprint);
		OptimizationInput input = resolveInput(context, transitionSequence, processDirectory, manifest, process);
		if (directory == null)
			directory = OperationDirectory.create(json, clock, context, transitionSequence, attempt, input);
		else if (!directory.manifest().inputFingerprint().equals(input.inputFingerprint()))
			throw new IOException("Recovered operation input fingerprint changed: " + directory.root());

		try (OperationClaim.Lease lease = acquireLease(json, clock, directory))
		{
			if (directory.state().state() == OperationState.PENDING)
				directory.start(input.inputFingerprint());
			OptimizationResult result;
			try
			{
				result = handlers.execute(context, input);
			}
			catch (OperationFailureException e)
			{
				OperationDirectory.Receipt receipt = directory.fail(e.failureKind(), diagnostic(e), e.retryable());
				if (mayRetry(operation, receipt))
					throw new RetryableOperationException();
				receipts.add(receipt);
				throw failed(receipt, receipts, e);
			}
			catch (IOException e)
			{
				OperationDirectory.Receipt receipt = directory.fail(OperationDirectory.FailureKind.INFRASTRUCTURE_FAILURE,
					diagnostic(e), true);
				if (mayRetry(operation, receipt))
					throw new RetryableOperationException();
				receipts.add(receipt);
				throw failed(receipt, receipts, e);
			}
			catch (RuntimeException e)
			{
				OperationDirectory.Receipt receipt = directory.fail(OperationDirectory.FailureKind.PROTOCOL_FAILURE,
					e.toString(), false);
				receipts.add(receipt);
				throw failed(receipt, receipts, e);
			}
			OperationDirectory.Receipt receipt = directory.complete(result);
			lease.verifyOwned();
			CertificationProcessLedger.recordReceipt(json, manifest, process.state().sequence(), directory);
			return receipt;
		}
	}

	/**
	 * Resolves and validates typed input before any operation directory is created or claimed.
	 *
	 * @param context the operation context
	 * @param transitionSequence the positive transition sequence
	 * @param processDirectory the certification-process directory
	 * @param manifest the certification-process manifest
	 * @param process the replayed process state
	 * @return the validated typed input
	 * @throws IOException if resolution or validation fails
	 */
	private OptimizationInput resolveInput(OptimizationContext context, long transitionSequence, Path processDirectory,
		Path manifest, CertificationProcessLedger.Snapshot process) throws IOException
	{
		OptimizationInput input = inputResolver.resolve(context, transitionSequence,
			predecessor(context.json(), processDirectory, manifest, process));
		if (input == null || input.operationKind() != context.operationKind() || !isSha256(input.inputFingerprint()))
			throw new IOException("Operation input does not match the selected operation: " + context.operationKind());
		return input;
	}

	/**
	 * Returns the preceding completed operation result when this transition has one.
	 *
	 * @param json the JSON codec
	 * @param processDirectory the certification-process directory
	 * @param manifest the certification-process manifest
	 * @param process the replayed process state
	 * @return the verified preceding result, or empty for the initial operation
	 * @throws IOException if predecessor evidence cannot be recovered
	 */
	private static Optional<OptimizationResult> predecessor(JsonMapper json, Path processDirectory, Path manifest,
		CertificationProcessLedger.Snapshot process) throws IOException
	{
		if (process.state().sequence() == 0)
			return Optional.empty();
		CertificationProcessLedger.Entry entry = CertificationProcessLedger.entry(json, manifest,
			process.state().sequence());
		Path operationPath = OperationDirectory.path(processDirectory, entry.sequence(), entry.operation(),
			entry.attempt());
		OperationDirectory directory = OperationDirectory.open(json, Clock.systemUTC(), operationPath);
		OperationDirectory.Receipt receipt = directory.
			verifiedTerminalReceipt().orElseThrow(() -> new IOException("Predecessor receipt is not terminal: " +
				operationPath));
		if (receipt.state() != OperationState.COMPLETED || receipt.resultKind().isEmpty() ||
			receipt.evidencePath().isEmpty() || receipt.evidenceFingerprint().isEmpty() ||
			!Fingerprints.ofFile(directory.receiptPath()).equals(entry.evidenceFingerprint()))
		{
			throw new IOException("Predecessor receipt does not match the certification-process ledger: " + operationPath);
		}
		return Optional.of(new OptimizationResult(receipt.operationKind(), receipt.resultKind().orElseThrow(),
			receipt.evidencePath().orElseThrow(), receipt.evidenceFingerprint().orElseThrow(),
			receipt.successorKind()));
	}

	/**
	 * Returns whether the registry permits retrying one retained failed operation.
	 *
	 * @param operation the failed operation kind
	 * @param receipt the retained failed receipt
	 * @return whether a new attempt may be created
	 */
	private static boolean mayRetry(OperationKind operation, OperationDirectory.Receipt receipt)
	{
		if (!receipt.retryable().orElse(false))
			return false;
		OptimizationOperationRegistry.RecoveryBoundary boundary = OptimizationOperationRegistry.definitions().get(
			operation).recoveryBoundary();
		return boundary != OptimizationOperationRegistry.RecoveryBoundary.TERMINAL_PREPARATION &&
			boundary != OptimizationOperationRegistry.RecoveryBoundary.TERMINAL_CERTIFICATION_PROCESS;
	}

	/**
	 * Returns a nonblank durable failure diagnostic.
	 *
	 * @param exception the caught failure
	 * @return a nonblank diagnostic
	 */
	private static String diagnostic(Exception exception)
	{
		String message = exception.getMessage();
		if (message == null || message.isBlank())
			return exception.getClass().getSimpleName();
		return message;
	}

	/**
	 * Acquires a durable lease or returns the active competing claim in a typed exception.
	 *
	 * @param json the JSON codec
	 * @param clock the operation clock
	 * @param directory the operation directory
	 * @return the acquired lease
	 * @throws IOException if no lease can be acquired or verified
	 */
	private OperationClaim.Lease acquireLease(JsonMapper json, Clock clock, OperationDirectory directory)
		throws IOException
	{
		Optional<OperationClaim.Lease> lease = OperationClaim.Lease.acquire(json, clock, directory, ownerId, processId);
		if (lease.isPresent())
			return lease.orElseThrow();
		OperationClaim active = OperationClaimDocument.read(json, directory).orElseThrow(() ->
			new IOException("Operation claim disappeared before it could be reported: " + directory.claimPath()));
		throw new CertificationProcessClaimUnavailableException("Operation is claimed by another live process", active);
	}

	/**
	 * Finds the latest uninterrupted persisted attempt for one operation transition.
	 *
	 * @param json the JSON codec
	 * @param clock the operation clock
	 * @param processDirectory the certification-process directory
	 * @param transitionSequence the transition sequence
	 * @param operation the operation kind
	 * @return the latest attempt, or {@code null} when none exists
	 * @throws IOException if a persisted operation is invalid
	 */
	private static OperationDirectory latestDirectory(JsonMapper json, Clock clock, Path processDirectory,
		long transitionSequence, OperationKind operation) throws IOException
	{
		OperationDirectory result = null;
		for (int attempt = 1; ; ++attempt)
		{
			Path path = OperationDirectory.path(processDirectory, transitionSequence, operation, attempt);
			if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS))
				return result;
			result = OperationDirectory.open(json, clock, path);
		}
	}

	/**
	 * Validates the immutable process identity against this invocation.
	 *
	 * @param process the replayed process state
	 * @param contractFingerprint the expected contract fingerprint
	 * @param environmentFingerprint the expected environment fingerprint
	 * @param manifest the manifest used in diagnostics
	 * @throws IOException if the immutable identity differs
	 */
	private static void requireIdentity(CertificationProcessLedger.Snapshot process, String contractFingerprint,
		String environmentFingerprint, Path manifest) throws IOException
	{
		if (!process.contractFingerprint().equals(contractFingerprint) ||
			!process.environmentFingerprint().equals(environmentFingerprint))
		{
			throw new IOException("Certification process contract or environment differs from invocation: " + manifest);
		}
	}

	/**
	 * Converts a durable process terminal result to the public terminal process state.
	 *
	 * @param process the replayed terminal process state
	 * @param receipts observed operation receipts
	 * @return the public terminal result
	 */
	private static ExecutionResult result(CertificationProcessLedger.Snapshot process,
		List<OperationDirectory.Receipt> receipts)
	{
		OperationResultKind terminal = process.state().terminalResult().orElseThrow();
		CertificationProcessState state = switch (terminal)
		{
			case CERTIFIED -> CertificationProcessState.CERTIFIED;
			case NOT_CERTIFIED -> CertificationProcessState.NOT_CERTIFIED;
			default -> throw new IllegalStateException("Certification process ended with unsupported result: " + terminal);
		};
		return new ExecutionResult(state, process.state().sequence(), receipts);
	}

	/**
	 * Creates the typed failure used when a retained failed operation cannot continue.
	 *
	 * @param failedReceipt the retained failed receipt
	 * @param receipts observed receipts
	 * @param cause the underlying failure, if any
	 * @return the typed execution failure
	 */
	private static CertificationProcessExecutionException failed(OperationDirectory.Receipt failedReceipt,
		List<OperationDirectory.Receipt> receipts, Throwable cause)
	{
		return new CertificationProcessExecutionException("Certification operation failed: " +
			failedReceipt.operationId(), failedReceipt, receipts, cause);
	}

	/**
	 * Validates one lower-case SHA-256 value.
	 *
	 * @param value the candidate fingerprint
	 * @param name the parameter name
	 * @return the unchanged fingerprint
	 */
	private static String requireSha256(String value, String name)
	{
		Objects.requireNonNull(value, name + " must not be null");
		if (!isSha256(value))
			throw new IllegalArgumentException(name + " must be a lowercase SHA-256 fingerprint");
		return value;
	}

	/**
	 * Returns whether one value is a lower-case SHA-256 fingerprint.
	 *
	 * @param value the candidate fingerprint
	 * @return whether the value has the required spelling
	 */
	private static boolean isSha256(String value)
	{
		return value != null && value.matches("[0-9a-f]{64}");
	}

	/**
	 * Holds the terminal result of one complete certification-process execution.
	 *
	 * @param terminalState the certified or not-certified terminal state
	 * @param finalSequence the nonnegative final transition sequence
	 * @param receipts observed durable operation receipts
	 */
	public record ExecutionResult(CertificationProcessState terminalState, long finalSequence,
		List<OperationDirectory.Receipt> receipts)
	{
		/**
		 * Validates and snapshots one execution result.
		 *
		 * @param terminalState the terminal process state
		 * @param finalSequence the final transition sequence
		 * @param receipts observed durable operation receipts
		 */
		public ExecutionResult
		{
			if (terminalState != CertificationProcessState.CERTIFIED &&
				terminalState != CertificationProcessState.NOT_CERTIFIED)
			{
				throw new IllegalArgumentException("terminalState must be certified or not certified");
			}
			if (finalSequence < 0)
				throw new IllegalArgumentException("finalSequence must not be negative");
			Objects.requireNonNull(receipts, "receipts must not be null");
			receipts = List.copyOf(receipts);
		}
	}

	/**
	 * Reports a retained failed operation that cannot safely continue.
	 */
	public static class CertificationProcessExecutionException extends IOException
	{
		private static final long serialVersionUID = 1L;
		private final transient OperationDirectory.Receipt failedReceipt;
		private final transient List<OperationDirectory.Receipt> receipts;

		/**
		 * Creates one execution failure.
		 *
		 * @param message the nonblank diagnostic
		 * @param failedReceipt the failed durable receipt
		 * @param receipts observed durable receipts including the failed receipt
		 * @param cause the underlying failure, if any
		 */
		public CertificationProcessExecutionException(String message, OperationDirectory.Receipt failedReceipt,
			List<OperationDirectory.Receipt> receipts, Throwable cause)
		{
			super(requireMessage(message), cause);
			this.failedReceipt = Objects.requireNonNull(failedReceipt, "failedReceipt must not be null");
			this.receipts = copyReceipts(receipts);
			if (!this.receipts.contains(failedReceipt))
				throw new IllegalArgumentException("receipts must contain failedReceipt");
		}

		/**
		 * Returns the failed durable receipt.
		 *
		 * @return the failed receipt
		 */
		public OperationDirectory.Receipt failedReceipt()
		{
			return failedReceipt;
		}

		/**
		 * Returns observed durable receipts in encounter order.
		 *
		 * @return immutable receipts
		 */
		public List<OperationDirectory.Receipt> receipts()
		{
			return receipts;
		}
	}

	/**
	 * Reports that another live local process owns the selected operation.
	 */
	public static final class CertificationProcessClaimUnavailableException extends IOException
	{
		private static final long serialVersionUID = 1L;
		private final transient OperationClaim activeClaim;

		/**
		 * Creates one unavailable-claim failure.
		 *
		 * @param message the nonblank diagnostic
		 * @param activeClaim the competing live claim
		 */
		public CertificationProcessClaimUnavailableException(String message, OperationClaim activeClaim)
		{
			super(requireMessage(message));
			this.activeClaim = Objects.requireNonNull(activeClaim, "activeClaim must not be null");
		}

		/**
		 * Returns the competing live claim.
		 *
		 * @return the active claim
		 */
		public OperationClaim activeClaim()
		{
			return activeClaim;
		}
	}

	/**
	 * Reports a completed atomic operation after its durable receipt and ledger transition were published.
	 */
	public static final class CertificationProcessInterruptedException extends IOException
	{
		private static final long serialVersionUID = 1L;
		private final long durableSequence;
		private final transient List<OperationDirectory.Receipt> receipts;

		/**
		 * Creates one durable interruption result.
		 *
		 * @param message the nonblank diagnostic
		 * @param durableSequence the nonnegative durable transition sequence
		 * @param receipts observed durable receipts
		 */
		public CertificationProcessInterruptedException(String message, long durableSequence,
			List<OperationDirectory.Receipt> receipts)
		{
			super(requireMessage(message));
			if (durableSequence < 0)
				throw new IllegalArgumentException("durableSequence must not be negative");
			this.durableSequence = durableSequence;
			this.receipts = copyReceipts(receipts);
		}

		/**
		 * Returns the durable transition sequence.
		 *
		 * @return the sequence
		 */
		public long durableSequence()
		{
			return durableSequence;
		}

		/**
		 * Returns observed durable receipts in encounter order.
		 *
		 * @return immutable receipts
		 */
		public List<OperationDirectory.Receipt> receipts()
		{
			return receipts;
		}
	}

	/**
	 * Validates one exception message.
	 *
	 * @param message the candidate message
	 * @return the unchanged message
	 */
	private static String requireMessage(String message)
	{
		Objects.requireNonNull(message, "message must not be null");
		if (message.isBlank())
			throw new IllegalArgumentException("message must not be blank");
		return message;
	}

	/**
	 * Snapshots receipts while rejecting null elements.
	 *
	 * @param receipts the candidate receipts
	 * @return the immutable receipt list
	 */
	private static List<OperationDirectory.Receipt> copyReceipts(List<OperationDirectory.Receipt> receipts)
	{
		Objects.requireNonNull(receipts, "receipts must not be null");
		return List.copyOf(receipts);
	}

	/**
	 * Returns control to the process loop after publishing one retryable failed attempt.
	 */
	private static final class RetryableOperationException extends IOException
	{
		private static final long serialVersionUID = 1L;

		/**
		 * Creates one internal retry signal.
		 */
		private RetryableOperationException()
		{
			super("Retryable certification operation failure");
		}
	}
}
