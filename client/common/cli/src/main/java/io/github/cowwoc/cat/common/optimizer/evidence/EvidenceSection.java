package io.github.cowwoc.cat.common.optimizer.evidence;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import java.io.IOException;
import java.nio.file.Path;

/**
 * Renders a trial's record as the evidence section that the judge reads: the activity of every turn, followed by the
 * files that the trial changed.
 */
public final class EvidenceSection
{
	/**
	 * Renders a trial's record.
	 *
	 * @param activity the trial's activity, read from its harness events
	 * @param effects  the directory that holds the files after the trial
	 * @param source   the staged source copied at the start of the trial
	 * @return the complete evidence section, without injected instructions, streaming fragments or telemetry
	 * @throws NullPointerException if any of the arguments are null
	 * @throws IOException          if the source or effects cannot be read
	 */
	public static String render(SessionEvidence activity, Path effects, Path source) throws IOException
	{
		requireThat(activity, "activity").isNotNull();
		requireThat(effects, "effects").isNotNull();
		requireThat(source, "source").isNotNull();
		return "Effects directory: " + effects + "\n\n" + activity.render() + "\nFile changes\n" +
			FileChanges.render(source, effects);
	}

	/**
	 * Prevents instantiation.
	 */
	private EvidenceSection()
	{
	}
}
