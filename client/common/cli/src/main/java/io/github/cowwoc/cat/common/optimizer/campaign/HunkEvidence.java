package io.github.cowwoc.cat.common.optimizer.campaign;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.optimizer.inventory.FileInventory;
import io.github.cowwoc.cat.common.optimizer.inventory.Hunk;

import java.nio.file.Path;
import java.util.List;

/**
 * The failures and generalization cases that a prompt author's manifest gives for one changed hunk.
 * <p>
 * <b>Thread Safety:</b> This class is immutable and thread-safe.
 *
 * @param id the prompt author's identifier for the hunk, which the manifest's kinds name
 * @param path the changed file's resolved path
 * @param beforeStart the first annotated working line, from zero
 * @param beforeEnd the end of the working range, exclusive
 * @param afterStart the first annotated prompt author line, from zero
 * @param afterEnd the end of the prompt author range, exclusive
 * @param failures the supplied failure identifiers
 * @param analogous a case requiring the same guidance
 * @param dissimilar the identifier of the contract test whose case must not receive the guidance
 */
public record HunkEvidence(String id, Path path, int beforeStart, int beforeEnd, int afterStart, int afterEnd,
	List<String> failures, String analogous, String dissimilar)
{
	/**
	 * Creates evidence for one hunk.
	 *
	 * @param id the prompt author's identifier for the hunk
	 * @param path the changed file's resolved path
	 * @param beforeStart the first working line
	 * @param beforeEnd the working range end
	 * @param afterStart the first prompt author line
	 * @param afterEnd the prompt author range end
	 * @param failures the supplied failure identifiers
	 * @param analogous a case requiring the same guidance
	 * @param dissimilar the identifier of the contract test whose case is excluded from the guidance
	 * @throws NullPointerException if an argument or a collection element is null
	 * @throws IllegalArgumentException if a path is invalid, a range is invalid, the citations are empty,
	 *   or any text is blank
	 */
	public HunkEvidence
	{
		requireThat(id, "id").isNotBlank();
		FileInventory.requireInventoryPath(path, "path");
		requireThat(beforeStart, "beforeStart").isNotNegative();
		requireThat(beforeEnd, "beforeEnd").isGreaterThanOrEqualTo(beforeStart);
		requireThat(afterStart, "afterStart").isNotNegative();
		requireThat(afterEnd, "afterEnd").isGreaterThanOrEqualTo(afterStart);
		requireThat(failures, "failures").isNotEmpty();
		failures = List.copyOf(failures);
		for (String failure : failures)
			requireThat(failure, "failure").isNotBlank();
		requireThat(analogous, "analogous").isNotBlank();
		requireThat(dissimilar, "dissimilar").isNotBlank();
	}

	/**
	 * Indicates whether this annotation contains an actual hunk's replaced and replacement spans.
	 *
	 * @param hunk the actual hunk
	 * @return whether both complete spans are covered
	 */
	public boolean covers(Hunk hunk)
	{
		return path.equals(hunk.path()) && beforeStart <= hunk.beforeStart() && beforeEnd >= hunk.beforeEnd() &&
			afterStart <= hunk.afterStart() && afterEnd >= hunk.afterEnd();
	}
}
