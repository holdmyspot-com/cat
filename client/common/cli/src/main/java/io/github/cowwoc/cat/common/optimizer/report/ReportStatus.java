package io.github.cowwoc.cat.common.optimizer.report;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.optimizer.campaign.Outcome;

/**
 * What the host agent does next, or how the campaign ended.
 * <p>
 * The values are: {@code running}, {@code awaiting_user},
 * {@code busy}, or the label of a terminal outcome.
 * <p>
 * <b>Thread Safety:</b> Implementations are immutable and thread-safe.
 */
public sealed interface ReportStatus
{
	/**
	 * Returns the text that the JSON status and the summary show.
	 *
	 * @return the label
	 */
	String label();

	/**
	 * The campaign has not ended.
	 * <p>
	 * <b>Thread Safety:</b> This class is immutable and thread-safe.
	 *
	 * @param status {@code running}, {@code awaiting_user} or {@code busy}
	 */
	record Active(Status status) implements ReportStatus
	{
		/**
		 * Creates a status.
		 *
		 * @param status {@code running}, {@code awaiting_user} or {@code busy}
		 * @throws NullPointerException if {@code status} is null
		 */
		public Active
		{
			requireThat(status, "status").isNotNull();
		}

		@Override
		public String label()
		{
			return status.label();
		}
	}

	/**
	 * The campaign ended with an outcome or with an uncertified delivery.
	 * <p>
	 * <b>Thread Safety:</b> This class is immutable and thread-safe.
	 *
	 * @param outcome the outcome or delivery label
	 */
	record Ended(Outcome outcome) implements ReportStatus
	{
		/**
		 * Creates a status.
		 *
		 * @param outcome the outcome or delivery label
		 * @throws NullPointerException if {@code outcome} is null
		 */
		public Ended
		{
			requireThat(outcome, "outcome").isNotNull();
		}

		@Override
		public String label()
		{
			return outcome.label();
		}
	}

	/**
	 * The campaign ended with neither an outcome nor a delivery.
	 * <p>
	 * <b>Thread Safety:</b> This class is immutable and thread-safe.
	 */
	record Interrupted() implements ReportStatus
	{
		@Override
		public String label()
		{
			return StatusReport.INTERRUPTED;
		}
	}
}
