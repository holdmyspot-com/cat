package com.holdmyspot.cat.common.optimizer.cli;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.optimizer.campaign.Campaign;
import com.holdmyspot.cat.common.optimizer.campaign.Choice;
import com.holdmyspot.cat.common.optimizer.contract.CertificationScope;

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
	 * @param ceiling the maximum simultaneous nested sessions of a new campaign, from 1 to 16
	 * @param goal what the user wants the target to achieve, in the user's own words, or {@code null} if the user
	 *   stated no goal
	 */
	record Start(Path target, int ceiling, String goal) implements Command
	{
		/**
		 * Creates the command.
		 *
		 * @param target the prompt file or prompt system, as the user wrote it
		 * @param ceiling the maximum simultaneous nested sessions of a new campaign, from 1 to 16
		 * @param goal what the user wants the target to achieve, in the user's own words, or {@code null} if the
		 *   user stated no goal
		 * @throws NullPointerException if {@code target} is null
		 * @throws IllegalArgumentException if {@code ceiling} is outside [1, {@value Campaign#MAXIMUM_CEILING}] or
		 *   {@code goal} is blank
		 */
		public Start
		{
			requireThat(target, "target").isNotNull();
			requireThat(ceiling, "ceiling").isBetween(1, true, Campaign.MAXIMUM_CEILING, true);
			if (goal != null)
				requireThat(goal, "goal").isNotBlank();
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
	 * Reports a campaign, or the certification history of a target that has no campaign, without changing it.
	 * <p>
	 * <b>Thread Safety:</b> This class is immutable and thread-safe.
	 *
	 * @param subject the campaign's identifier or the target
	 */
	record Status(String subject) implements Command
	{
		/**
		 * Creates the command.
		 *
		 * @param subject the campaign's identifier or the target
		 * @throws NullPointerException if {@code subject} is null
		 */
		public Status
		{
			requireThat(subject, "subject").isNotNull();
		}
	}

	/**
	 * Confirms the campaign's current proposal with the certification scope that the user chose.
	 * <p>
	 * <b>Thread Safety:</b> This class is immutable and thread-safe.
	 *
	 * @param campaign the campaign's identifier
	 * @param scope the certification scope
	 */
	record Confirm(String campaign, CertificationScope scope) implements Command
	{
		/**
		 * Creates the command.
		 *
		 * @param campaign the campaign's identifier
		 * @param scope the certification scope
		 * @throws NullPointerException if any of the arguments are null
		 */
		public Confirm
		{
			requireThat(campaign, "campaign").isNotNull();
			requireThat(scope, "scope").isNotNull();
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
	 */
	record Choose(String campaign, Choice choice, int attempt, int reviewPoint) implements Command
	{
		/**
		 * Creates the command.
		 *
		 * @param campaign the campaign's identifier
		 * @param choice the choice
		 * @param attempt the certification index that {@link Choice#KEEP_IMPROVEMENT} names, otherwise 0
		 * @param reviewPoint the review point that {@link Choice#MORE_EVIDENCE} names, otherwise 0
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

	/**
	 * A contract tool, which a contract author session runs while it writes the proposal file and the draft
	 * directory. A contract tool needs no campaign and no harness.
	 */
	sealed interface ContractTool extends Command permits ContractCheck, ContractWorkspace
	{
	}

	/**
	 * Checks a contract author session's proposal file and draft directory as CAT does when the session ends.
	 * <p>
	 * <b>Thread Safety:</b> This class is immutable and thread-safe.
	 *
	 * @param context the context file that CAT wrote into the session's project
	 */
	record ContractCheck(Path context) implements ContractTool
	{
		/**
		 * Creates the command.
		 *
		 * @param context the context file that CAT wrote into the session's project
		 * @throws NullPointerException if {@code context} is null
		 */
		public ContractCheck
		{
			requireThat(context, "context").isNotNull();
		}
	}

	/**
	 * Writes the workspace files of one test case of a prompt test in a contract author session's draft directory.
	 * <p>
	 * <b>Thread Safety:</b> This class is immutable and thread-safe.
	 *
	 * @param context       the context file that CAT wrote into the session's project
	 * @param testDirectory the prompt test's directory, relative to the draft directory
	 * @param testCase      the test case's number, counted from 1 in the test's values file
	 * @param output        the new directory to write to
	 */
	record ContractWorkspace(Path context, Path testDirectory, int testCase, Path output) implements ContractTool
	{
		/**
		 * Creates the command.
		 *
		 * @param context       the context file that CAT wrote into the session's project
		 * @param testDirectory the prompt test's directory, relative to the draft directory
		 * @param testCase      the test case's number, counted from 1 in the test's values file
		 * @param output        the new directory to write to
		 * @throws NullPointerException     if any of the arguments are null
		 * @throws IllegalArgumentException if {@code testCase} is not positive
		 */
		public ContractWorkspace
		{
			requireThat(context, "context").isNotNull();
			requireThat(testDirectory, "testDirectory").isNotNull();
			requireThat(testCase, "testCase").isPositive();
			requireThat(output, "output").isNotNull();
		}
	}
}
