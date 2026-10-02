package io.github.cowwoc.cat.common.optimizer.execution;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import java.util.List;

/**
 * The complete final report of an author session.
 *
 * @param neededEdits the edits requiring further authority
 * @param manifest the author's classifications and change evidence
 */
public record AuthorReport(List<NeededEdit> neededEdits, AuthorManifest manifest)
{
	/**
	 * Creates the report.
	 *
	 * @param neededEdits the edits requiring further authority
	 * @param manifest the classifications and change evidence
	 * @throws NullPointerException if an argument or a collection element is null
	 */
	public AuthorReport
	{
		neededEdits = List.copyOf(neededEdits);
		requireThat(manifest, "manifest").isNotNull();
	}
}
