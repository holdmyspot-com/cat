package io.github.cowwoc.cat.common.optimizer.cli;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.optimizer.campaign.Harness;
import io.github.cowwoc.cat.common.optimizer.execution.DeliveryReport;
import io.github.cowwoc.cat.common.optimizer.report.CertificationStatus;
import io.github.cowwoc.cat.common.optimizer.report.StatusReport;

import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

/**
 * What a command tells the host agent: a campaign's status, or the certification record of a target that has no
 * campaign.
 * <p>
 * <b>Thread Safety:</b> This class is immutable and thread-safe.
 *
 * @param status the campaign's status after the command, or {@code null} if the result reports a certification record
 * @param certification the certification record of a target that has no campaign, or {@code null} if the result
 *   reports a campaign
 * @param harness the harness that started the campaign, or {@code null} if the result reports a certification record
 * @param proposal the rendered proposal that the user must review, or {@code null} if the command presents none
 * @param delivery the delivery that the command attempted, or {@code null} if it attempted none
 * @param message a notice for the user that the status does not carry, or {@code null} if there is none
 * @param notices the lines that follow the status's summary, such as the removal of a campaign's files or a warning
 *   that a removal failed
 */
public record CommandResult(StatusReport status, CertificationStatus certification, Harness harness,
	String proposal, DeliveryReport delivery, String message, List<String> notices)
{
	/**
	 * Creates a result.
	 *
	 * @param status the campaign's status after the command, or {@code null} if the result reports a certification
	 *   record
	 * @param certification the certification record of a target that has no campaign, or {@code null} if the result
	 *   reports a campaign
	 * @param harness the harness that started the campaign, or {@code null} if the result reports a certification
	 *   record
	 * @param proposal the rendered proposal that the user must review, or {@code null} if the command presents none
	 * @param delivery the delivery that the command attempted, or {@code null} if it attempted none
	 * @param message a notice for the user that the status does not carry, or {@code null} if there is none
	 * @param notices the lines that follow the status's summary
	 * @throws NullPointerException if {@code notices} is null or contains null, or if {@code status} is present and
	 *   {@code harness} is null
	 * @throws IllegalArgumentException if {@code status} and {@code certification} are both present or both null; if a
	 *   certification record comes with a harness, a proposal or a delivery; or if {@code proposal},
	 *   {@code message} or a notice is blank
	 */
	public CommandResult
	{
		if ((status == null) == (certification == null))
		{
			throw new IllegalArgumentException("A result reports either a campaign's status or a certification " +
				"record. status: " + status + ", certification: " + certification);
		}
		if (status != null)
			requireThat(harness, "harness").isNotNull();
		else
		{
			requireThat(harness, "harness").isNull();
			requireThat(proposal, "proposal").isNull();
			requireThat(delivery, "delivery").isNull();
		}
		if (proposal != null)
			requireThat(proposal, "proposal").isNotBlank();
		if (message != null)
			requireThat(message, "message").isNotBlank();
		requireThat(notices, "notices").isNotNull();
		for (String notice : notices)
			requireThat(notice, "notice").isNotBlank();
		notices = List.copyOf(notices);
	}

	/**
	 * Creates a result that reports a campaign and carries no notices.
	 *
	 * @param status the campaign's status after the command
	 * @param harness the harness that started the campaign
	 * @param proposal the rendered proposal that the user must review, or {@code null} if the command presents none
	 * @param delivery the delivery that the command attempted, or {@code null} if it attempted none
	 * @param message a notice for the user that the status does not carry, or {@code null} if there is none
	 * @throws NullPointerException if {@code status} or {@code harness} are null
	 * @throws IllegalArgumentException if {@code proposal} or {@code message} are blank
	 */
	public CommandResult(StatusReport status, Harness harness, String proposal, DeliveryReport delivery,
		String message)
	{
		this(requireStatus(status), null, harness, proposal, delivery, message, List.of());
	}

	/**
	 * Returns a campaign's status, which a campaign result requires.
	 *
	 * @param status the status
	 * @return {@code status}
	 * @throws NullPointerException if {@code status} is null
	 */
	private static StatusReport requireStatus(StatusReport status)
	{
		requireThat(status, "status").isNotNull();
		return status;
	}

	/**
	 * Returns a result that carries only a campaign's status.
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

	/**
	 * Returns a result that reports the certification record of a target that has no campaign.
	 *
	 * @param certification the record and its applicability
	 * @return the result
	 * @throws NullPointerException if {@code certification} is null
	 */
	public static CommandResult of(CertificationStatus certification)
	{
		requireThat(certification, "certification").isNotNull();
		return new CommandResult(null, certification, null, null, null, null, List.of());
	}

	/**
	 * Returns this result with more notices after its own. A result that presents a proposal also appends them to
	 * its message, because the host shows the proposal instead of the summary.
	 *
	 * @param added the notices to add
	 * @return the result
	 * @throws NullPointerException if {@code added} is null or contains null
	 * @throws IllegalArgumentException if a notice is blank
	 */
	public CommandResult withNotices(List<String> added)
	{
		requireThat(added, "added").isNotNull();
		if (added.isEmpty())
			return this;
		List<String> combined = new ArrayList<>(notices);
		combined.addAll(added);
		String combinedMessage = message;
		if (proposal != null)
		{
			String lines = String.join("\n", added);
			if (combinedMessage == null)
				combinedMessage = lines;
			else
				combinedMessage += "\n" + lines;
		}
		return new CommandResult(status, certification, harness, proposal, delivery, combinedMessage, combined);
	}

	/**
	 * Returns the summary that the user reads: the status's summary followed by each notice on its own line.
	 *
	 * @param zone the user's time zone, in which the summary shows times
	 * @return the summary text
	 * @throws NullPointerException if {@code zone} is null
	 */
	public String summary(ZoneId zone)
	{
		requireThat(zone, "zone").isNotNull();
		String summary;
		if (status != null)
			summary = status.summary(zone);
		else
			summary = certification.summary();
		if (notices.isEmpty())
			return summary;
		return summary + "\n" + String.join("\n", notices);
	}
}
