package com.holdmyspot.cat.common.optimizer.execution;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.optimizer.contract.RecordedTrial;
import com.holdmyspot.cat.common.optimizer.contract.ToolCall;
import com.holdmyspot.cat.common.optimizer.evidence.EvidenceSection;
import com.holdmyspot.cat.common.optimizer.evidence.SessionEvidence;
import com.holdmyspot.cat.common.optimizer.port.SessionPort;
import com.holdmyspot.cat.common.optimizer.port.SessionResult;

import java.io.IOException;
import java.util.List;
import java.util.Optional;

/**
 * The record of a completed trial that its built-in tools and its judge decide from: every turn's ordered assistant
 * messages, every tool call of the main agent and its children, the copy of the project as it was when the trial
 * started, and the project copy that holds the trial's file effects.
 * <p>
 * Unreported exit statuses remain null. A recorded list with no calls lets a built-in tool decide that the trial
 * never called a tool.
 * <p>
 * Each call that reads a copy of the project reads the copy as it is when the call runs, including any change that
 * another process made to it.
 * <p>
 * <b>Thread Safety:</b> This class is thread-safe.
 */
public final class TrialEvidence extends RecordedTrial
{
	private final SessionResult.Completed trial;

	/**
	 * Creates the record of a trial.
	 *
	 * @param trial    the trial session's result
	 * @param activity the trial's activity, read from its harness events
	 * @throws NullPointerException if any of the arguments are null
	 */
	public TrialEvidence(SessionResult.Completed trial, SessionEvidence activity)
	{
		requireThat(trial, "trial").isNotNull();
		super(trial.responses(), activity, trial.initialProject(), trial.effects());
		this.trial = trial;
	}

	/**
	 * Reads the record of a trial.
	 *
	 * @param sessions the port that ran the trial, which reads its harness events
	 * @param trial    the trial session's result
	 * @return the record
	 * @throws NullPointerException if any of the arguments are null
	 * @throws IOException          if the trial's transcript cannot be read
	 */
	public static TrialEvidence read(SessionPort sessions, SessionResult.Completed trial) throws IOException
	{
		requireThat(sessions, "sessions").isNotNull();
		requireThat(trial, "trial").isNotNull();
		return new TrialEvidence(trial, sessions.readEvidence(trial.transcript()));
	}

	/**
	 * Renders the record as the judge's evidence section.
	 *
	 * @return the section
	 * @throws IOException if either copy of the project cannot be read
	 */
	public String render() throws IOException
	{
		String rendered = EvidenceSection.render(activity(), trial.transcript(), trial.effects(), trial.initialProject());
		if (!trial.endedEarly())
			return rendered;
		StringBuilder note = new StringBuilder(rendered).append("\n\nTrial session: ended after turn ").
			append(trial.responses().size()).append(" of ").append(trial.requestedTurns()).
			append(", every assertion settled. Turns not run: ").
			append(String.join(", ", trial.turnsNotRun().stream().map(String::valueOf).toList())).
			append(". Assertion settlement turns:");
		trial.settledAssertions().forEach((id, turn) -> note.append("\n- ").append(id).append(": turn ").append(turn));
		return note.toString();
	}

	/**
	 * Returns the tool calls that the trial made.
	 *
	 * @return the calls in the order they were made
	 */
	public Optional<List<ToolCall>> toolCalls()
	{
		return Optional.of(trial.toolCalls());
	}
}
