package io.github.cowwoc.cat.common.optimization;

import java.io.IOException;
import java.util.Objects;

/**
 * Identifies a non-domain operation failure that the executor can persist in an operation receipt.
 */
public final class OperationFailureException extends IOException
{
	private static final long serialVersionUID = 1L;

	private final OperationDirectory.FailureKind failureKind;
	private final boolean retryable;

	/**
	 * Creates one declared operation failure.
	 *
	 * @param failureKind the durable failure category
	 * @param retryable whether the registry recovery boundary may retry the operation
	 * @param message the nonblank failure diagnostic
	 * @param cause the underlying failure, if any
	 */
	public OperationFailureException(OperationDirectory.FailureKind failureKind, boolean retryable, String message,
		Throwable cause)
	{
		super(requireMessage(message), cause);
		this.failureKind = Objects.requireNonNull(failureKind, "failureKind must not be null");
		this.retryable = retryable;
	}

	/**
	 * Returns the durable failure category.
	 *
	 * @return the failure category
	 */
	public OperationDirectory.FailureKind failureKind()
	{
		return failureKind;
	}

	/**
	 * Returns whether this failure permits a registry-approved retry.
	 *
	 * @return whether the failure is retryable
	 */
	public boolean retryable()
	{
		return retryable;
	}

	/**
	 * Validates one required diagnostic.
	 *
	 * @param message the diagnostic
	 * @return the unchanged diagnostic
	 */
	private static String requireMessage(String message)
	{
		Objects.requireNonNull(message, "message must not be null");
		if (message.isBlank())
			throw new IllegalArgumentException("message must not be blank");
		return message;
	}
}
