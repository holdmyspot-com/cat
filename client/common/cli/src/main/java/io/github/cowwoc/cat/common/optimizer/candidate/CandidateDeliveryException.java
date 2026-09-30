package io.github.cowwoc.cat.common.optimizer.candidate;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** A classified candidate transaction failure with exact affected-path context. */
public final class CandidateDeliveryException extends IOException
{
	private static final long serialVersionUID = 1L;
	private final Reason reason;
	private final String transactionId;
	private final String[] affectedPathNames;

	/**
	 * Creates an immutable classified delivery failure.
	 *
	 * @param reason constrained failure category
	 * @param message precise failure context
	 * @param transactionId durable transaction identity when one exists
	 * @param affectedPaths ordered project-relative targets
	 */
	public CandidateDeliveryException(Reason reason, String message, Optional<String> transactionId,
		List<Path> affectedPaths)
	{
		this(reason, message, transactionId, affectedPaths, null);
	}

	/**
	 * Preserves a filesystem failure while classifying its delivery consequence.
	 *
	 * @param reason constrained failure category
	 * @param message precise failure context
	 * @param transactionId durable transaction identity when one exists
	 * @param affectedPaths ordered project-relative targets
	 * @param cause underlying filesystem failure
	 */
	CandidateDeliveryException(Reason reason, String message, Optional<String> transactionId,
		List<Path> affectedPaths, IOException cause)
	{
		super(message, cause);
		this.reason = Objects.requireNonNull(reason, "reason");
		this.transactionId = Objects.requireNonNull(transactionId, "transactionId").orElse(null);
		this.affectedPathNames = Objects.requireNonNull(affectedPaths, "affectedPaths").stream().map(Path::toString).
			toArray(String[]::new);
		if (message == null || message.isBlank())
			throw new IllegalArgumentException("Delivery failure message must be non-blank");
	}

	/**
	 * Returns the constrained failure category.
	 *
	 * @return failure category
	 */
	public Reason reason()
	{
		return reason;
	}

	/**
	 * Returns the durable transaction identity when one exists.
	 *
	 * @return optional transaction identity
	 */
	public Optional<String> transactionId()
	{
		return Optional.ofNullable(transactionId);
	}

	/**
	 * Returns the ordered affected targets.
	 *
	 * @return immutable project-relative paths
	 */
	public List<Path> affectedPaths()
	{
		return Arrays.stream(affectedPathNames).map(Path::of).toList();
	}

	/** Constrained delivery-failure classifications. */
	public enum Reason
	{
		CONCURRENT_MODIFICATION,
		RECOVERY_REQUIRED,
		CORRUPT_TRANSACTION,
		UNSUPPORTED_ATOMIC_PATH_OPERATION,
		IO_FAILURE
	}
}
