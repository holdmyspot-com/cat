package com.holdmyspot.cat.common.optimizer.cli;

/**
 * Thrown when a command is well-formed but the campaign's state or binding does not permit it. A command that throws
 * this exception has changed nothing.
 */
public final class CommandRefusedException extends Exception
{
	@java.io.Serial
	private static final long serialVersionUID = 1L;

	/**
	 * A refusal that a caller can act on without interpreting the message.
	 */
	enum Reason
	{
		/**
		 * The target has neither a campaign nor delivered certification history.
		 */
		NO_CAMPAIGN_OR_CERTIFICATION_RECORD,
		/**
		 * A selected evaluator or its trial configuration cannot be used.
		 */
		EVALUATOR_UNAVAILABLE,
		/**
		 * A selected evaluator's loader, profile or runtime changed after the proposal.
		 */
		EVALUATOR_CONDITIONS_CHANGED
	}

	private final Reason reason;

	/**
	 * Creates the exception.
	 *
	 * @param message why the command is refused and what the user can do instead
	 */
	public CommandRefusedException(String message)
	{
		this(message, null, null);
	}

	/**
	 * Creates the exception.
	 *
	 * @param message why the command is refused and what the user can do instead
	 * @param cause the failure that makes the command impossible
	 */
	public CommandRefusedException(String message, Throwable cause)
	{
		this(message, cause, null);
	}

	/**
	 * Creates a refusal with an optional machine-readable reason.
	 *
	 * @param message why the command is refused and what the user can do instead
	 * @param cause the underlying failure, or `null` if there is none
	 * @param reason the reason that the caller can act on, or `null` if there is none
	 */
	CommandRefusedException(String message, Throwable cause, Reason reason)
	{
		super(message, cause);
		this.reason = reason;
	}

	/**
	 * Returns the machine-readable reason, or `null` for an ordinary refusal.
	 *
	 * @return the reason
	 */
	Reason reason()
	{
		return reason;
	}
}
