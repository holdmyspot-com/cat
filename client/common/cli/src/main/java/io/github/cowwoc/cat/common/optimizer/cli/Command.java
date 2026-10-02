package io.github.cowwoc.cat.common.optimizer.cli;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.optimizer.campaign.Choice;

import java.nio.file.Path;

/**
 * One command that the {@code prompt-optimizer} launcher accepts, after {@link Commands#parse(java.util.List)} has
 * validated its arguments.
 */
public sealed interface Command
{
	/**
	 * Starts the campaign for a target, or resumes the target's resumable campaign.
	 * <p>
	 * <b>Thread Safety:</b> This class is immutable and thread-safe.
	 *
	 * @param target the prompt file or prompt system, as the user wrote it
	 */
	record Start(Path target) implements Command
	{
		/**
		 * Creates the command.
		 *
		 * @param target the prompt file or prompt system, as the user wrote it
		 * @throws NullPointerException if {@code target} is null
		 */
		public Start
		{
			requireThat(target, "target").isNotNull();
		}
	}

	/**
	 * Advances a campaign for one slice.
	 * <p>
	 * <b>Thread Safety:</b> This class is immutable and thread-safe.
	 *
	 * @param campaign the campaign's identifier
	 */
	record Run(String campaign) implements Command
	{
		/**
		 * Creates the command.
		 *
		 * @param campaign the campaign's identifier
		 * @throws NullPointerException if {@code campaign} is null
		 */
		public Run
		{
			requireThat(campaign, "campaign").isNotNull();
		}
	}

	/**
	 * Reports a campaign without changing it.
	 * <p>
	 * <b>Thread Safety:</b> This class is immutable and thread-safe.
	 *
	 * @param campaign the campaign's identifier
	 */
	record Status(String campaign) implements Command
	{
		/**
		 * Creates the command.
		 *
		 * @param campaign the campaign's identifier
		 * @throws NullPointerException if {@code campaign} is null
		 */
		public Status
		{
			requireThat(campaign, "campaign").isNotNull();
		}
	}

	/**
	 * Confirms the campaign's current proposal.
	 * <p>
	 * <b>Thread Safety:</b> This class is immutable and thread-safe.
	 *
	 * @param campaign the campaign's identifier
	 */
	record Confirm(String campaign) implements Command
	{
		/**
		 * Creates the command.
		 *
		 * @param campaign the campaign's identifier
		 * @throws NullPointerException if {@code campaign} is null
		 */
		public Confirm
		{
			requireThat(campaign, "campaign").isNotNull();
		}
	}

	/**
	 * Regenerates the proposal from the user's feedback.
	 * <p>
	 * <b>Thread Safety:</b> This class is immutable and thread-safe.
	 *
	 * @param campaign the campaign's identifier
	 * @param feedback the user's feedback
	 */
	record Revise(String campaign, String feedback) implements Command
	{
		/**
		 * Creates the command.
		 *
		 * @param campaign the campaign's identifier
		 * @param feedback the user's feedback
		 * @throws NullPointerException if any argument is null
		 * @throws IllegalArgumentException if {@code feedback} is blank
		 */
		public Revise
		{
			requireThat(campaign, "campaign").isNotNull();
			requireThat(feedback, "feedback").isNotBlank();
		}
	}

	/**
	 * Passes the user's answer to a pending decision back to the campaign.
	 * <p>
	 * <b>Thread Safety:</b> This class is immutable and thread-safe.
	 *
	 * @param campaign the campaign's identifier
	 * @param choice the choice
	 * @param attempt the certification index that {@link Choice#KEEP_IMPROVEMENT} names, otherwise 0
	 * @param reviewPoint the review point that {@link Choice#MORE_EVIDENCE} names, otherwise 0
	 * @param target the external target that {@link Choice#AUTHORIZE_TARGET} names, otherwise {@code null}
	 */
	record Choose(String campaign, Choice choice, int attempt, int reviewPoint, Path target) implements Command
	{
		/**
		 * Creates the command.
		 *
		 * @param campaign the campaign's identifier
		 * @param choice the choice
		 * @param attempt the certification index that {@link Choice#KEEP_IMPROVEMENT} names, otherwise 0
		 * @param reviewPoint the review point that {@link Choice#MORE_EVIDENCE} names, otherwise 0
		 * @param target the external target that {@link Choice#AUTHORIZE_TARGET} names, otherwise {@code null}
		 * @throws NullPointerException if {@code campaign} or {@code choice} are null
		 */
		public Choose
		{
			requireThat(campaign, "campaign").isNotNull();
			requireThat(choice, "choice").isNotNull();
		}
	}

	/**
	 * Selects a numbered resolution of the conflict currently awaiting the user's answer.
	 *
	 * @param campaign the campaign's identifier
	 * @param resolution the 1-based resolution position
	 */
	record ResolveConflict(String campaign, int resolution) implements Command
	{
		/**
		 * Creates the command.
		 *
		 * @param campaign the campaign's identifier
		 * @param resolution the 1-based resolution position
		 * @throws NullPointerException if {@code campaign} is null
		 * @throws IllegalArgumentException if {@code resolution} is not positive
		 */
		public ResolveConflict
		{
			requireThat(campaign, "campaign").isNotNull();
			requireThat(resolution, "resolution").isPositive();
		}
	}

	/**
	 * Cancels a campaign, keeping its history and spent allowance.
	 * <p>
	 * <b>Thread Safety:</b> This class is immutable and thread-safe.
	 *
	 * @param campaign the campaign's identifier
	 */
	record Cancel(String campaign) implements Command
	{
		/**
		 * Creates the command.
		 *
		 * @param campaign the campaign's identifier
		 * @throws NullPointerException if {@code campaign} is null
		 */
		public Cancel
		{
			requireThat(campaign, "campaign").isNotNull();
		}
	}
}
