package io.github.cowwoc.cat.common.optimizer.campaign;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.optimizer.contract.Cell;
import io.github.cowwoc.cat.common.optimizer.contract.CertificationScope;
import io.github.cowwoc.cat.common.optimizer.contract.Contract;
import io.github.cowwoc.cat.common.optimizer.inventory.FileEntry;
import io.github.cowwoc.cat.common.optimizer.inventory.FileEntry.RegularFile;
import io.github.cowwoc.cat.common.optimizer.inventory.FileInventory;
import io.github.cowwoc.cat.common.optimizer.inventory.Sha256;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

/**
 * What a delivered certification claims and the conditions it was measured under, kept in the project after the
 * campaign's files are removed.
 * <p>
 * The record holds the certification's index, scope, parameters, per-cell counts, per-assertion control and incumbent
 * pairs, the content hash of each delivered prompt file, and every measurement condition that the attempt recorded,
 * which names the prompt sources and the contract by hash. It holds no trial, so its size depends only on the number
 * of cells, assertions, prompt files and conditions.
 * <p>
 * <b>Thread Safety:</b> This class is immutable and thread-safe.
 *
 * @param target the campaign's target, relative to the project
 * @param certificationIndex the certification index {@code i} of the certified attempt
 * @param scope the certification scope that the user confirmed
 * @param parameters the parameters of the certification's claim
 * @param cells the certified attempt's counts of each cell, in cell order
 * @param promptFiles the content hash of each delivered prompt file, keyed by its path relative to the project
 * @param identity the measurement conditions that the attempt was measured under
 */
public record CertificationRecord(Path target, int certificationIndex, CertificationScope scope,
	CertificationParameters parameters, List<CellResult> cells, Map<Path, Sha256> promptFiles,
	MeasurementIdentity identity)
{
	/**
	 * Creates a record.
	 *
	 * @param target the campaign's target, relative to the project
	 * @param certificationIndex the certification index {@code i} of the certified attempt
	 * @param scope the certification scope that the user confirmed
	 * @param parameters the parameters of the certification's claim
	 * @param cells the certified attempt's counts of each cell, in cell order
	 * @param promptFiles the content hash of each delivered prompt file, keyed by its path relative to the project
	 * @param identity the measurement conditions that the attempt was measured under
	 * @throws NullPointerException if any of the arguments are null, or {@code cells} or {@code promptFiles} contain
	 *   null
	 * @throws IllegalArgumentException if {@code target} or a prompt file's path is absolute or empty, or if
	 *   {@code certificationIndex} is not positive
	 */
	public CertificationRecord
	{
		requireThat(target, "target").isNotNull();
		requireThat(target.isAbsolute(), "target.isAbsolute()").isFalse();
		requireThat(target.toString(), "target").isNotEmpty();
		requireThat(certificationIndex, "certificationIndex").isPositive();
		requireThat(scope, "scope").isNotNull();
		requireThat(parameters, "parameters").isNotNull();
		requireThat(cells, "cells").isNotNull();
		requireThat(promptFiles, "promptFiles").isNotNull();
		requireThat(identity, "identity").isNotNull();
		for (Path file : promptFiles.keySet())
		{
			requireThat(file.isAbsolute(), "promptFiles.isAbsolute(" + file + ")").isFalse();
			requireThat(file.toString(), "promptFiles").isNotEmpty();
		}
		cells = List.copyOf(cells);
		promptFiles = Collections.unmodifiableMap(new TreeMap<>(promptFiles));
	}

	/**
	 * Returns the record of a certified attempt.
	 *
	 * @param campaign the campaign
	 * @param attempt the certified attempt
	 * @param snapshot the files of the delivered candidate
	 * @param promptSources the participating prompt sources: paths relative to the project, or absolute paths of
	 *   read-only external participants, which the record omits
	 * @return the record
	 * @throws NullPointerException if any of the arguments are null
	 * @throws IllegalArgumentException if the campaign has no lineage of the attempt, or that lineage's contract has
	 *   no cell that the attempt counted
	 */
	public static CertificationRecord of(Campaign campaign, CertificationAttempt attempt, FileInventory snapshot,
		Set<Path> promptSources)
	{
		requireThat(campaign, "campaign").isNotNull();
		requireThat(attempt, "attempt").isNotNull();
		requireThat(snapshot, "snapshot").isNotNull();
		requireThat(promptSources, "promptSources").isNotNull();
		Header header = campaign.header();
		Path target = header.root().relativize(header.target());
		Contract contract = contractOf(campaign, attempt);

		List<CellResult> cells = new ArrayList<>();
		for (Entry<String, CellCounts> entry : new TreeMap<>(attempt.counts()).entrySet())
		{
			String cell = entry.getKey();
			String test = contract.findCell(cell).map(Cell::testId).orElseThrow(() ->
				new IllegalArgumentException("The contract of lineage " + attempt.lineage() + " has no cell " + cell));
			CellCounts counts = entry.getValue();
			cells.add(new CellResult(test, cell, counts.passes(), counts.failures(),
				attempt.pairing(Reference.CONTROL, cell).comparisons(),
				attempt.pairing(Reference.INCUMBENT, cell).comparisons()));
		}

		Map<Path, Sha256> promptFiles = new TreeMap<>();
		for (Entry<Path, FileEntry> entry : snapshot.entries().entrySet())
		{
			Path path = entry.getKey();
			if (!path.isAbsolute() && entry.getValue() instanceof RegularFile file &&
				promptSources.stream().anyMatch(path::startsWith))
			{
				promptFiles.put(path, file.contentHash());
			}
		}
		return new CertificationRecord(target, attempt.index(), contract.profiles().scope(), attempt.parameters(),
			cells, promptFiles, attempt.identity());
	}

	/**
	 * Returns the contract that an attempt was certified under.
	 *
	 * @param campaign the campaign
	 * @param attempt the attempt
	 * @return the contract of the attempt's lineage
	 */
	private static Contract contractOf(Campaign campaign, CertificationAttempt attempt)
	{
		for (Lineage lineage : campaign.lineages())
		{
			if (lineage.number() == attempt.lineage())
				return lineage.contract();
		}
		throw new IllegalArgumentException("The campaign has no lineage " + attempt.lineage());
	}

	/**
	 * Returns the harness that measured the certification.
	 *
	 * @return the model provider that the measurement conditions record, which names the harness, or empty if they
	 *   record none
	 */
	public Optional<String> harness()
	{
		return Optional.ofNullable(identity.values().get(MeasurementCondition.PROVIDER));
	}

	/**
	 * Decides whether the certification applies to the current prompt files and conditions.
	 *
	 * @param currentPromptFiles the content hash of each recorded prompt file that exists now, keyed by its path
	 *   relative to the project
	 * @param known the conditions that CAT knows now
	 * @param minimumPassRate the minimum pass rate {@code p_min} that a certification must prove now
	 * @return the applicability, naming each known change and each recorded condition that {@code known} lacks. If
	 *   {@code known} records another provider than the record, the harness-specific conditions are unchecked rather
	 *   than changed.
	 * @throws NullPointerException if any of the arguments are null
	 */
	public Applicability applicability(Map<Path, Sha256> currentPromptFiles, MeasurementIdentity known,
		BigDecimal minimumPassRate)
	{
		requireThat(currentPromptFiles, "currentPromptFiles").isNotNull();
		requireThat(known, "known").isNotNull();
		boolean promptContentChanged = !promptFiles.equals(currentPromptFiles);
		String recordedProvider = identity.values().get(MeasurementCondition.PROVIDER);
		String knownProvider = known.values().get(MeasurementCondition.PROVIDER);
		// Another harness cannot observe what the certifying harness recorded, so its values are unchecked
		if (recordedProvider != null && knownProvider != null && !recordedProvider.equals(knownProvider))
			known = known.withoutHarnessSpecific();
		List<MeasurementCondition> changed = identity.differences(known);
		List<MeasurementCondition> unchecked = identity.unobservedBy(known);
		List<CertificationParameter> notImplied = parameters.notImplying(
			CertificationParameters.current(certificationIndex, minimumPassRate));
		Applicability.Status status = Applicability.Status.CURRENT;
		if (promptContentChanged || !changed.isEmpty() || !notImplied.isEmpty())
			status = Applicability.Status.HISTORICAL;
		return new Applicability(status, promptContentChanged, changed, notImplied, unchecked);
	}

	/**
	 * The certified attempt's counts of one cell.
	 * <p>
	 * <b>Thread Safety:</b> This class is immutable and thread-safe.
	 *
	 * @param test the cell's prompt test
	 * @param cell the cell's identifier
	 * @param passes the passing outcomes in certification evidence
	 * @param failures the failing outcomes in certification evidence
	 * @param controlComparisons the pairs of each assertion's control comparison, keyed by the assertion's identifier
	 *   in contract order
	 * @param incumbentComparisons the pairs of each assertion's incumbent comparison, keyed by the assertion's
	 *   identifier in contract order, or empty if the certified candidate is the incumbent
	 */
	public record CellResult(String test, String cell, int passes, int failures,
		Map<String, PairCounts> controlComparisons, Map<String, PairCounts> incumbentComparisons)
	{
		/**
		 * Creates a cell's counts.
		 *
		 * @param test the cell's prompt test
		 * @param cell the cell's identifier
		 * @param passes the passing outcomes in certification evidence
		 * @param failures the failing outcomes in certification evidence
		 * @param controlComparisons the pairs of each assertion's control comparison, keyed by the assertion's
		 *   identifier in contract order
		 * @param incumbentComparisons the pairs of each assertion's incumbent comparison, keyed by the assertion's
		 *   identifier in contract order, or empty if the certified candidate is the incumbent
		 * @throws NullPointerException if any of the arguments are null, or {@code controlComparisons} or
		 *   {@code incumbentComparisons} contain null
		 * @throws IllegalArgumentException if {@code test} or {@code cell} are blank, or {@code passes} or
		 *   {@code failures} are negative
		 */
		public CellResult
		{
			requireThat(test, "test").isNotBlank();
			requireThat(cell, "cell").isNotBlank();
			requireThat(passes, "passes").isNotNegative();
			requireThat(failures, "failures").isNotNegative();
			controlComparisons = copyOf(controlComparisons, "controlComparisons");
			incumbentComparisons = copyOf(incumbentComparisons, "incumbentComparisons");
		}

		/**
		 * Returns an unmodifiable copy of the pairs of each assertion's comparison.
		 *
		 * @param comparisons the pairs, keyed by the assertion's identifier
		 * @param name the name of the parameter
		 * @return the copy, in the same order
		 * @throws NullPointerException if {@code comparisons} is null or contains null
		 */
		private static Map<String, PairCounts> copyOf(Map<String, PairCounts> comparisons, String name)
		{
			requireThat(comparisons, name).isNotNull();
			for (Entry<String, PairCounts> comparison : comparisons.entrySet())
			{
				requireThat(comparison.getKey(), name + ".key").isNotNull();
				requireThat(comparison.getValue(), name + ".value").isNotNull();
			}
			return Collections.unmodifiableMap(new LinkedHashMap<>(comparisons));
		}
	}
}
