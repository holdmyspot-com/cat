package io.github.cowwoc.cat.common.optimizer.campaign;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.optimizer.inventory.Sha256;

import java.nio.file.Path;
import java.util.List;

/**
 * Retained prompt author evidence: the manifest of an attempt whose report CAT could read, including attempts that
 * produced no candidate.
 * <p>
 * <b>Thread Safety:</b> This class is immutable and thread-safe.
 *
 * @param number the prompt author attempt's number within the campaign, from 1
 * @param lineage the number of the lineage whose contract the prompt author received
 * @param cell the round's cell
 * @param snapshot the working snapshot the prompt author received
 * @param kinds the kinds into which the prompt author grouped the received failures
 * @param hunks the evidence that the prompt author gave for each changed hunk
 * @param rejections the rejection reasons, empty for a valid report
 * @param transcript the retained session archive
 * @param effects the retained attempted edits
 */
public record PromptAuthorDiagnosis(int number, int lineage, String cell, Sha256 snapshot, List<PromptAuthorKind> kinds,
	List<HunkEvidence> hunks, List<String> rejections, Path transcript, Path effects)
{
	/**
	 * Creates retained evidence.
	 *
	 * @param number the prompt author attempt's number within the campaign, from 1
	 * @param lineage the number of the lineage whose contract the prompt author received
	 * @param cell the round's cell
	 * @param snapshot the working snapshot
	 * @param kinds the kinds into which the prompt author grouped the received failures
	 * @param hunks the evidence for each changed hunk
	 * @param rejections the rejection reasons
	 * @param transcript the retained session archive
	 * @param effects the retained attempted edits
	 * @throws NullPointerException if any argument or collection element is null
	 * @throws IllegalArgumentException if {@code cell} is blank, or {@code number} or {@code lineage} are not positive
	 */
	public PromptAuthorDiagnosis
	{
		requireThat(number, "number").isPositive();
		requireThat(lineage, "lineage").isPositive();
		requireThat(cell, "cell").isNotBlank();
		requireThat(snapshot, "snapshot").isNotNull();
		kinds = List.copyOf(kinds);
		hunks = List.copyOf(hunks);
		rejections = List.copyOf(rejections);
		requireThat(transcript, "transcript").isNotNull();
		requireThat(effects, "effects").isNotNull();
	}
}
