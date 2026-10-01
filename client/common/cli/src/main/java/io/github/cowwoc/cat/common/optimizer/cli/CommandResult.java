package io.github.cowwoc.cat.common.optimizer.cli;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.optimizer.campaign.Harness;
import io.github.cowwoc.cat.common.optimizer.execution.DeliveryReport;
import io.github.cowwoc.cat.common.optimizer.report.StatusReport;

/**
 * What a command tells the host agent.
 * <p>
 * <b>Thread Safety:</b> This class is immutable and thread-safe.
 *
 * @param status the campaign's status after the command
 * @param harness the harness that started the campaign
 * @param proposal the rendered proposal that the user must review, or {@code null} if the command presents none
 * @param delivery the delivery that the command attempted, or {@code null} if it attempted none
 * @param message a notice for the user that the status does not carry, or {@code null} if there is none
 */
public record CommandResult(StatusReport status, Harness harness, String proposal, DeliveryReport delivery,
	String message)
{
	/**
	 * Creates a result.
	 *
	 * @param status the campaign's status after the command
	 * @param harness the harness that started the campaign
	 * @param proposal the rendered proposal that the user must review, or {@code null} if the command presents none
	 * @param delivery the delivery that the command attempted, or {@code null} if it attempted none
	 * @param message a notice for the user that the status does not carry, or {@code null} if there is none
	 * @throws NullPointerException if {@code status} or {@code harness} are null
	 * @throws IllegalArgumentException if {@code proposal} or {@code message} are blank
	 */
	public CommandResult
	{
		requireThat(status, "status").isNotNull();
		requireThat(harness, "harness").isNotNull();
		if (proposal != null)
			requireThat(proposal, "proposal").isNotBlank();
		if (message != null)
			requireThat(message, "message").isNotBlank();
	}

	/**
	 * Returns a result that carries only a status.
	 *
	 * @param status the campaign's status
	 * @param harness the harness that started the campaign
	 * @return the result
	 * @throws NullPointerException if any argument is null
	 */
	public static CommandResult of(StatusReport status, Harness harness)
	{
		return new CommandResult(status, harness, null, null, null);
	}
}
