package io.github.cowwoc.cat.common.optimizer.execution;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.optimizer.inventory.FileInventory;

import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;

/**
 * A reported conflict in confirmed requirements, with the user's possible resolutions.
 *
 * @param failures the supplied failure identifiers explained by this conflict
 * @param requirementIds the distinct contract requirement identifiers that cannot hold together
 * @param quotations the exact confirmed prompt text causing the conflict
 * @param resolutions the distinct possible requirement revisions, in presentation order
 * @param recommended the 1-based position of the recommended resolution
 * @param reason why that resolution best preserves the prompt's intent
 */
public record PromptAuthorConflict(List<String> failures, List<String> requirementIds, List<Quotation> quotations,
	List<String> resolutions, int recommended, String reason)
{
	/**
	 * Creates a conflict; manifest validation checks the recommendation against its resolutions.
	 *
	 * @param failures the supplied failure identifiers
	 * @param requirementIds the distinct requirement identifiers
	 * @param quotations the exact confirmed prompt text
	 * @param resolutions the distinct possible requirement revisions
	 * @param recommended the 1-based recommended position
	 * @param reason the recommendation's rationale
	 * @throws NullPointerException if an argument or collection element is null
	 * @throws IllegalArgumentException if a required collection is too small, text is blank, or requirements or
	 *   resolutions repeat
	 */
	public PromptAuthorConflict
	{
		failures = List.copyOf(failures);
		requirementIds = List.copyOf(requirementIds);
		quotations = List.copyOf(quotations);
		resolutions = List.copyOf(resolutions);
		requireThat(failures, "failures").isNotEmpty();
		requireThat(quotations, "quotations").isNotEmpty();
		requireThat(requirementIds.size(), "requirementIds.size()").isGreaterThanOrEqualTo(2);
		requireThat(resolutions.size(), "resolutions.size()").isGreaterThanOrEqualTo(2);
		requireThat(new HashSet<>(requirementIds).size(), "distinctRequirementIds").isEqualTo(requirementIds.size());
		requireThat(new HashSet<>(resolutions).size(), "distinctResolutions").isEqualTo(resolutions.size());
		for (String failure : failures)
			requireThat(failure, "failure").isNotBlank();
		for (String requirement : requirementIds)
			requireThat(requirement, "requirement").isNotBlank();
		for (String resolution : resolutions)
			requireThat(resolution, "resolution").isNotBlank();
		requireThat(reason, "reason").isNotBlank();
	}

	/**
	 * Exact prompt text and the confirmed file that contains it.
	 *
	 * @param path the normalized prompt-file path
	 * @param text the non-blank text copied exactly from that file
	 */
	public record Quotation(Path path, String text)
	{
		/**
		 * Creates a quotation.
		 *
		 * @param path the normalized prompt-file path
		 * @param text the exact non-blank text
		 * @throws NullPointerException if an argument is null
		 * @throws IllegalArgumentException if the path is invalid or text is blank
		 */
		public Quotation
		{
			FileInventory.requireInventoryPath(path, "path");
			requireThat(text, "text").isNotBlank();
		}
	}
}
