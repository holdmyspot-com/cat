package io.github.cowwoc.cat.common.optimizer.cli;

import io.github.cowwoc.cat.common.optimizer.campaign.Campaign;
import io.github.cowwoc.cat.common.optimizer.campaign.Choice;
import io.github.cowwoc.cat.common.optimizer.contract.CertificationScope;

import java.io.IOException;
import java.nio.file.Path;

/**
 * What the commands call. Each method implements one {@link Command} on already validated input.
 * <p>
 * A method that throws {@link CommandRefusedException} has changed nothing.
 */
public interface Core
{
	/**
	 * Starts a campaign with the default ceiling of 8, or resumes the target's resumable campaign.
	 *
	 * @param target the prompt file or prompt system
	 * @return the result, which presents the proposal that awaits confirmation
	 * @throws NullPointerException if {@code target} is null
	 * @throws CommandRefusedException if the current harness cannot deny its sessions reads of held-out content, the
	 *   other harness started the target's campaign, the target is not in the project, or the base worktree has
	 *   uncommitted changes
	 * @throws IOException if the store, the workspace or a session fails
	 * @throws InterruptedException if the thread is interrupted while it waits for a session
	 */
	default CommandResult start(Path target) throws CommandRefusedException, IOException, InterruptedException
	{
		return start(target, Campaign.DEFAULT_CEILING);
	}

	/**
	 * Starts a campaign with the selected ceiling, or resumes the target's resumable campaign with its stored ceiling.
	 *
	 * @param target the prompt file or prompt system
	 * @param ceiling the maximum simultaneous nested sessions of a new campaign, from 1 to 16
	 * @return the result, which presents the proposal that awaits confirmation
	 * @throws NullPointerException if {@code target} is null
	 * @throws IllegalArgumentException if {@code ceiling} is outside [1, {@value Campaign#MAXIMUM_CEILING}]
	 * @throws CommandRefusedException if the current harness cannot deny its sessions reads of held-out content, the
	 *   other harness started the target's campaign, the target is not in the project, or the base worktree has
	 *   uncommitted changes
	 * @throws IOException if the store, the workspace or a session fails
	 * @throws InterruptedException if the thread is interrupted while it waits for a session
	 */
	CommandResult start(Path target, int ceiling) throws CommandRefusedException, IOException, InterruptedException;

	/**
	 * Advances a campaign for one slice.
	 *
	 * @param campaign the campaign's identifier
	 * @return the result
	 * @throws CommandRefusedException if the campaign does not exist or the other harness started it
	 * @throws IOException if the store, the workspace or a session fails
	 * @throws InterruptedException if the thread is interrupted while it waits
	 */
	CommandResult run(String campaign) throws CommandRefusedException, IOException, InterruptedException;

	/**
	 * Reports a campaign without changing it. Either harness may report any campaign.
	 * <p>
	 * {@code subject} names a campaign by its identifier or by its target. A target resolves as {@code start}
	 * resolves it. A target reports its resumable campaign; otherwise its certification record, if any, and whether
	 * the record still applies; otherwise its latest campaign.
	 *
	 * @param subject the campaign's identifier, or a target
	 * @return the result
	 * @throws CommandRefusedException if no campaign has the identifier and the target has neither a campaign nor a
	 *   certification record, or if the target lies outside the project
	 * @throws IOException if the store, the record or the prompt files cannot be read
	 */
	CommandResult status(String subject) throws CommandRefusedException, IOException;

	/**
	 * Confirms the campaign's current proposal with the certification scope that the user chose.
	 *
	 * @param campaign the campaign's identifier
	 * @param scope the certification scope, which confirmation requires
	 * @return the result
	 * @throws CommandRefusedException if {@code scope} is null, which the refusal answers by naming every choice, or
	 *   the campaign does not exist, the other harness started it, it has no proposal to confirm, or the proposal
	 *   cannot be confirmed with {@code scope}
	 * @throws IOException if the store or the workspace fails
	 */
	CommandResult confirm(String campaign, CertificationScope scope) throws CommandRefusedException, IOException;

	/**
	 * Regenerates the proposal from the user's feedback.
	 *
	 * @param campaign the campaign's identifier
	 * @param feedback the user's feedback
	 * @return the result, which presents the regenerated proposal
	 * @throws CommandRefusedException if the campaign does not exist, the other harness started it, or its state does
	 *   not permit a revision
	 * @throws IOException if the store, the workspace or a session fails
	 * @throws InterruptedException if the thread is interrupted while it waits for a session
	 */
	CommandResult revise(String campaign, String feedback)
		throws CommandRefusedException, IOException, InterruptedException;

	/**
	 * Passes the user's answer to a pending decision back to the campaign.
	 *
	 * @param campaign the campaign's identifier
	 * @param choice the choice
	 * @param attempt the certification index that {@link Choice#KEEP_IMPROVEMENT} names, otherwise 0
	 * @param reviewPoint the review point that {@link Choice#MORE_EVIDENCE} names, otherwise 0
	 * @return the result
	 * @throws CommandRefusedException if the campaign does not exist, the other harness started it, or the choice is
	 *   not one that the campaign offers now
	 * @throws IOException if the store or the workspace fails
	 */
	CommandResult choose(String campaign, Choice choice, int attempt, int reviewPoint)
		throws CommandRefusedException, IOException;

	/**
	 * Records a resolution of the current conflict. After the final conflict's choice, regenerates the proposal
	 * from every chosen resolution, awaiting confirmation.
	 *
	 * @param campaign the campaign's identifier
	 * @param resolution the 1-based position among the current conflict's resolutions
	 * @return the next conflict's status, or the revised proposal
	 * @throws CommandRefusedException if the campaign does not offer this resolution now
	 * @throws IOException if the store, workspace or session fails
	 * @throws InterruptedException if derivation is interrupted
	 */
	CommandResult resolveConflict(String campaign, int resolution)
		throws CommandRefusedException, IOException, InterruptedException;

	/**
	 * Cancels a campaign, keeping its history and spent allowance.
	 *
	 * @param campaign the campaign's identifier
	 * @return the result
	 * @throws CommandRefusedException if the campaign does not exist, the other harness started it, or it is not
	 *   active
	 * @throws IOException if the store or the workspace fails
	 */
	CommandResult cancel(String campaign) throws CommandRefusedException, IOException;
}
