package io.github.cowwoc.cat.common.optimizer.evidence;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import java.io.IOException;
import java.nio.file.Path;

/**
 * Renders a trial's record as the evidence section that the judge reads: where the trial's transcript and files are,
 * the activity of every turn, and the files that the trial changed.
 */
public final class EvidenceSection
{
	/**
	 * Renders a trial's record.
	 *
	 * @param activity       the trial's activity, read from its harness events
	 * @param transcript     the file that holds the trial's harness events, including each tool's complete output
	 * @param effects        the directory that holds the files after the trial
	 * @param initialProject the directory that holds the files as they were when the trial started
	 * @return the evidence section, without tool output text, injected instructions, streaming fragments or telemetry
	 * @throws NullPointerException if any of the arguments are null
	 * @throws IOException          if either directory cannot be read
	 */
	public static String render(SessionEvidence activity, Path transcript, Path effects, Path initialProject)
		throws IOException
	{
		requireThat(activity, "activity").isNotNull();
		requireThat(transcript, "transcript").isNotNull();
		requireThat(effects, "effects").isNotNull();
		requireThat(initialProject, "initialProject").isNotNull();
		return "Transcript: " + transcript + "\nEffects directory: " + effects + "\n\n" + activity.render() +
			"\nFile changes\n" + FileChanges.render(initialProject, effects);
	}

	/**
	 * Prevents instantiation.
	 */
	private EvidenceSection()
	{
	}
}
