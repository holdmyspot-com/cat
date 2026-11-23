package com.holdmyspot.cat.common.optimizer.evidence;

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
		return paths(transcript, effects) + activity.render() +
			"\nFile changes\n" + FileChanges.render(initialProject, effects);
	}

	/**
	 * Replaces the evidence heading's original paths with the paths offered to the reader. The recorded activity and
	 * file changes remain as recorded.
	 *
	 * @param section the rendered evidence
	 * @param transcript the original transcript
	 * @param effects the original effects directory
	 * @param offeredTranscript the reader's transcript
	 * @param offeredEffects the reader's effects directory
	 * @return the section with the updated heading, or the original section if it has no matching heading
	 */
	public static String withPaths(String section, Path transcript, Path effects, Path offeredTranscript,
		Path offeredEffects)
	{
		String heading = paths(transcript, effects);
		if (!section.startsWith(heading))
			return section;
		return paths(offeredTranscript, offeredEffects) + section.substring(heading.length());
	}

	/**
	 * Removes the evidence heading, which names the trial's own directories, so that two trials with the same activity
	 * and file changes have the same text.
	 *
	 * @param section    the rendered evidence
	 * @param transcript the trial's transcript
	 * @param effects    the trial's effects directory
	 * @return the section without its heading, or the original section if it has no matching heading
	 * @throws NullPointerException if any of the arguments are null
	 */
	public static String withoutPaths(String section, Path transcript, Path effects)
	{
		requireThat(section, "section").isNotNull();
		String heading = paths(transcript, effects);
		if (!section.startsWith(heading))
			return section;
		return section.substring(heading.length());
	}

	/**
	 * Returns the heading for an evidence section with the given paths.
	 *
	 * @param transcript the transcript file
	 * @param effects the effects directory
	 * @return the heading
	 */
	private static String paths(Path transcript, Path effects)
	{
		return "Transcript: " + transcript + "\nEffects directory: " + effects + "\n\n";
	}

	/**
	 * Prevents instantiation.
	 */
	private EvidenceSection()
	{
	}
}
