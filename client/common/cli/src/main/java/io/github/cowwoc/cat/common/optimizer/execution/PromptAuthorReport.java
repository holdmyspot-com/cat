package io.github.cowwoc.cat.common.optimizer.execution;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import java.util.List;

/**
 * The complete final report of a prompt author session.
 *
 * @param neededEdits the edits requiring further authority
 * @param manifest the prompt author's classifications and change evidence
 * @param conflicts the conflicts requiring the user's resolution
 */
public record PromptAuthorReport(List<NeededEdit> neededEdits, PromptAuthorManifest manifest,
	List<PromptAuthorConflict> conflicts)
{
	/**
	 * Creates the report.
	 *
	 * @param neededEdits the edits requiring further authority
	 * @param manifest the classifications and change evidence
	 * @param conflicts the conflicts requiring the user's resolution
	 * @throws NullPointerException if an argument or a collection element is null
	 */
	public PromptAuthorReport
	{
		neededEdits = List.copyOf(neededEdits);
		conflicts = List.copyOf(conflicts);
		requireThat(manifest, "manifest").isNotNull();
	}
}
