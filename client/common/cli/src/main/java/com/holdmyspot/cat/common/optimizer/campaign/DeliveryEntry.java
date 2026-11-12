package com.holdmyspot.cat.common.optimizer.campaign;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.PortablePaths;
import com.holdmyspot.cat.common.optimizer.inventory.FileInventory;
import com.holdmyspot.cat.common.optimizer.inventory.Sha256;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * The certification history's entry of a delivery that was not certified: an improvement kept without certification,
 * an explicitly accepted uncertified candidate, or a kept incumbent whose prompt tests alone were delivered.
 * <p>
 * The entry holds what the campaign proved about the delivered prompt, which is nothing unless it was kept as an
 * improvement, the incumbent it was compared with by hash, the content hash of each delivered prompt file and the
 * measurement conditions. It holds no trial and never supports a certification claim.
 * <p>
 * <b>Thread Safety:</b> This class is thread-safe.
 *
 * @param target the campaign's target, relative to the project
 * @param label the delivery's outcome; never {@code certified}
 * @param attempt the certification index of the attempt whose frozen snapshot was delivered, or 0 if none was
 * @param provenBetter the cells in which the delivered candidate is proven better than the incumbent, with none
 *   proven worse, in cell order; empty if the campaign proved nothing about it
 * @param incumbent the snapshot hash of the incumbent that the campaign compared candidates with
 * @param promptFiles the content hash of each delivered prompt file, keyed by its path relative to the project
 * @param identity the measurement conditions of the delivery
 */
public record DeliveryEntry(Path target, DeliveryLabel label, int attempt, List<String> provenBetter,
	Sha256 incumbent, Map<Path, Sha256> promptFiles, MeasurementIdentity identity) implements HistoryEntry
{
	/**
	 * Creates an entry.
	 *
	 * @param target the campaign's target, relative to the project
	 * @param label the delivery's outcome; never {@code certified}
	 * @param attempt the certification index of the attempt whose frozen snapshot was delivered, or 0 if none was
	 * @param provenBetter the cells in which the delivered candidate is proven better than the incumbent, with none
	 *   proven worse, in cell order; empty if the campaign proved nothing about it
	 * @param incumbent the snapshot hash of the incumbent that the campaign compared candidates with
	 * @param promptFiles the content hash of each delivered prompt file, keyed by its path relative to the project
	 * @param identity the measurement conditions of the delivery
	 * @throws NullPointerException if any of the arguments are null, or {@code provenBetter} or {@code promptFiles}
	 *   contain null
	 * @throws IllegalArgumentException if {@code target} or a prompt file's path is absolute or empty, if
	 *   {@code label} is {@code certified}, if {@code attempt} is negative, if an improvement names no attempt or no
	 *   cell proven better, if another label names a cell proven better, or if a kept incumbent names an attempt
	 */
	public DeliveryEntry
	{
		requireThat(target, "target").isNotNull();
		requireThat(PortablePaths.isRooted(target), "isRooted(target)").isFalse();
		requireThat(target.toString(), "target").isNotEmpty();
		requireThat(label, "label").isNotNull().isNotEqualTo(DeliveryLabel.CERTIFIED);
		requireThat(attempt, "attempt").isNotNegative();
		requireThat(provenBetter, "provenBetter").isNotNull();
		requireThat(incumbent, "incumbent").isNotNull();
		requireThat(promptFiles, "promptFiles").isNotNull();
		requireThat(identity, "identity").isNotNull();
		for (Path file : promptFiles.keySet())
		{
			requireThat(PortablePaths.isRooted(file), "promptFiles.isRooted(file)").withContext(file, "file").isFalse();
			requireThat(file.toString(), "promptFiles").isNotEmpty();
		}
		provenBetter = List.copyOf(provenBetter);
		switch (label)
		{
			case IMPROVED_NOT_CERTIFIED ->
			{
				requireThat(attempt, "attempt").isPositive();
				requireThat(provenBetter, "provenBetter").isNotEmpty();
			}
			case NOT_CERTIFIED -> requireThat(provenBetter, "provenBetter").isEmpty();
			case INCUMBENT_KEPT ->
			{
				requireThat(attempt, "attempt").isZero();
				requireThat(provenBetter, "provenBetter").isEmpty();
			}
			case CERTIFIED -> throw new AssertionError("Rejected above");
		}
		promptFiles = Collections.unmodifiableMap(new TreeMap<>(promptFiles));
	}

	/**
	 * Returns the entry of a delivery that was not certified.
	 *
	 * @param campaign the campaign
	 * @param request the delivery request, or {@code null} if the user kept the incumbent
	 * @param snapshot the files of the delivered candidate, or of the incumbent if the user kept it
	 * @param promptSources the participating prompt sources: paths relative to the project, or absolute paths of
	 *   read-only external participants, which the entry omits
	 * @return the entry
	 * @throws NullPointerException if {@code campaign}, {@code snapshot} or {@code promptSources} are null
	 * @throws IllegalArgumentException if {@code request} is labelled {@code certified} or with an outcome that never
	 *   accompanies a delivery, or names an attempt that the campaign does not have
	 */
	public static DeliveryEntry of(Campaign campaign, DeliveryRequest request, FileInventory snapshot,
		Set<Path> promptSources)
	{
		requireThat(campaign, "campaign").isNotNull();
		requireThat(snapshot, "snapshot").isNotNull();
		requireThat(promptSources, "promptSources").isNotNull();
		Header header = campaign.header();
		Path target = header.root().relativize(header.target());
		DeliveryLabel label;
		int index = 0;
		if (request == null)
			label = DeliveryLabel.INCUMBENT_KEPT;
		else
		{
			label = DeliveryLabel.of(request.label());
			index = request.attempt();
		}
		MeasurementIdentity identity = campaign.identity();
		List<String> provenBetter = new ArrayList<>();
		if (index > 0)
		{
			CertificationAttempt attempt = campaign.attempt(index);
			identity = attempt.identity();
			if (label == DeliveryLabel.IMPROVED_NOT_CERTIFIED)
			{
				for (String cell : attempt.procedure().cells())
				{
					if (DecisionTable.isProvenBetter(attempt, cell))
						provenBetter.add(cell);
				}
			}
		}
		return new DeliveryEntry(target, label, index, provenBetter, campaign.incumbent().snapshot(),
			CertificationRecord.promptFilesOf(snapshot, promptSources), identity);
	}

	/**
	 * Returns what the campaign proved about the delivered prompt.
	 *
	 * @return the claim, in words
	 */
	public String claim()
	{
		if (provenBetter.isEmpty())
			return "nothing proven";
		return "proven better than the incumbent in " + String.join(", ", provenBetter) + ", none proven worse";
	}

	/**
	 * Decides whether the entry's conditions apply to the current prompt files and conditions, as a certification
	 * record's would. The entry has no certification parameters to compare.
	 *
	 * @param currentPromptFiles the content hash of each recorded prompt file that exists now, keyed by its path
	 *   relative to the project
	 * @param known the conditions that CAT knows now
	 * @return the applicability, naming each known change and each recorded condition that {@code known} lacks
	 * @throws NullPointerException if any of the arguments are null
	 */
	public Applicability applicability(Map<Path, Sha256> currentPromptFiles, MeasurementIdentity known)
	{
		return CertificationRecord.applicability(this, currentPromptFiles, known, List.of());
	}
}
