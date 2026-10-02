package io.github.cowwoc.cat.common.optimizer.campaign;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.optimizer.inventory.Sha256;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Retained author evidence, including classifications from attempts that produced no candidate.
 * <p>
 * <b>Thread Safety:</b> This class is immutable and thread-safe.
 *
 * @param category the round's cell
 * @param snapshot the working snapshot the author received
 * @param classifications each received failure's diagnosis
 * @param rejections the rejection reasons, empty for a valid report
 * @param transcript the retained session archive
 * @param effects the retained attempted edits
 */
public record AuthorDiagnosis(String category, Sha256 snapshot, Map<String, FailureKind> classifications,
	List<String> rejections, Path transcript, Path effects)
{
	/**
	 * Creates retained evidence.
	 *
	 * @param category the round's cell
	 * @param snapshot the working snapshot
	 * @param classifications the failure classifications
	 * @param rejections the rejection reasons
	 * @param transcript the retained session archive
	 * @param effects the retained attempted edits
	 * @throws NullPointerException if any argument or collection element is null
	 * @throws IllegalArgumentException if the category is blank
	 */
	public AuthorDiagnosis
	{
		requireThat(category, "category").isNotBlank();
		requireThat(snapshot, "snapshot").isNotNull();
		classifications = Map.copyOf(classifications);
		rejections = List.copyOf(rejections);
		requireThat(transcript, "transcript").isNotNull();
		requireThat(effects, "effects").isNotNull();
	}
}
