package io.github.cowwoc.cat.common.optimizer.campaign;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.optimizer.inventory.FileInventory;
import io.github.cowwoc.cat.common.optimizer.inventory.Hunk;

import java.nio.file.Path;
import java.util.List;

/**
 * The failing instances and generalization cases that an author's manifest gives for one changed hunk.
 * <p>
 * <b>Thread Safety:</b> This class is immutable and thread-safe.
 *
 * @param id the author's identifier for the hunk, which the manifest's kinds name
 * @param path the changed file's resolved path
 * @param beforeStart the first annotated working line, from zero
 * @param beforeEnd the end of the working range, exclusive
 * @param afterStart the first annotated author line, from zero
 * @param afterEnd the end of the author range, exclusive
 * @param instances the supplied failing-instance identifiers
 * @param analogous a case requiring the same guidance
 * @param dissimilar the identifier of the contract test whose case must not receive the guidance
 */
public record HunkEvidence(String id, Path path, int beforeStart, int beforeEnd, int afterStart, int afterEnd,
	List<String> instances, String analogous, String dissimilar)
{
	/**
	 * Creates evidence for one hunk.
	 *
	 * @param id the author's identifier for the hunk
	 * @param path the changed file's resolved path
	 * @param beforeStart the first working line
	 * @param beforeEnd the working range end
	 * @param afterStart the first author line
	 * @param afterEnd the author range end
	 * @param instances the supplied failing-instance identifiers
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
		requireThat(instances, "instances").isNotEmpty();
		instances = List.copyOf(instances);
		for (String instance : instances)
			requireThat(instance, "instance").isNotBlank();
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
