package io.github.cowwoc.cat.common.optimizer.campaign;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.optimizer.contract.Cell;
import io.github.cowwoc.cat.common.optimizer.contract.CertificationScope;
import io.github.cowwoc.cat.common.optimizer.contract.Contract;
import io.github.cowwoc.cat.common.optimizer.inventory.FileEntry;
import io.github.cowwoc.cat.common.optimizer.inventory.FileEntry.RegularFile;
import io.github.cowwoc.cat.common.optimizer.inventory.FileInventory;
import io.github.cowwoc.cat.common.optimizer.inventory.Sha256;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.TreeMap;

/**
 * What a delivered certification claims and the conditions it was measured under, kept in the project after the
 * campaign's files are removed.
 * <p>
 * The record holds the certification's index, scope, parameters and per-cell counts, the content hash of each
 * delivered prompt file, and every measurement condition that the attempt recorded, which names the prompt sources and
 * the contract by hash. It holds no trial, so its size depends only on the number of cells, prompt files and
 * conditions.
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
	 *   external targets, which the record omits
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
			String category = contract.findCell(cell).map(Cell::categoryId).orElseThrow(() ->
				new IllegalArgumentException("The contract of lineage " + attempt.lineage() + " has no cell " + cell));
			CellCounts counts = entry.getValue();
			cells.add(new CellResult(category, cell, counts.passes(), counts.failures()));
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
	 * Returns the file that holds the record of a target.
	 *
	 * @param target the target, relative to the project
	 * @return {@code .cat/certifications/<target without its extension>.json}, relative to the project
	 * @throws NullPointerException if {@code target} is null
	 */
	public static Path location(Path target)
	{
		requireThat(target, "target").isNotNull();
		String name = target.getFileName().toString();
		// A name that starts with its only dot, such as ".cursorrules", has no extension
		int dot = name.lastIndexOf('.');
		if (dot > 0)
			name = name.substring(0, dot);
		Path relative = Path.of(name + ".json");
		Path parent = target.getParent();
		if (parent != null)
			relative = parent.resolve(relative);
		return Path.of(".cat/certifications").resolve(relative);
	}

	/**
	 * Decides whether the certification applies to the current prompt files and conditions.
	 *
	 * @param currentPromptFiles the content hash of each recorded prompt file that exists now, keyed by its path
	 *   relative to the project
	 * @param known the conditions that CAT knows now
	 * @return the applicability, naming each known change and each recorded condition that {@code known} lacks
	 * @throws NullPointerException if any of the arguments are null
	 */
	public Applicability applicability(Map<Path, Sha256> currentPromptFiles, MeasurementIdentity known)
	{
		requireThat(currentPromptFiles, "currentPromptFiles").isNotNull();
		requireThat(known, "known").isNotNull();
		boolean promptContentChanged = !promptFiles.equals(currentPromptFiles);
		List<MeasurementCondition> changed = identity.differences(known);
		List<MeasurementCondition> unchecked = identity.unobservedBy(known);
		List<CertificationParameter> notImplied = parameters.notImplying(
			CertificationParameters.current(certificationIndex));
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
	 * @param category the cell's requirement category
	 * @param cell the cell's identifier
	 * @param passes the passing outcomes in certification evidence
	 * @param failures the failing outcomes in certification evidence
	 */
	public record CellResult(String category, String cell, int passes, int failures)
	{
		/**
		 * Creates a cell's counts.
		 *
		 * @param category the cell's requirement category
		 * @param cell the cell's identifier
		 * @param passes the passing outcomes in certification evidence
		 * @param failures the failing outcomes in certification evidence
		 * @throws NullPointerException if {@code category} or {@code cell} are null
		 * @throws IllegalArgumentException if {@code category} or {@code cell} are blank, or {@code passes} or
		 *   {@code failures} are negative
		 */
		public CellResult
		{
			requireThat(category, "category").isNotBlank();
			requireThat(cell, "cell").isNotBlank();
			requireThat(passes, "passes").isNotNegative();
			requireThat(failures, "failures").isNotNegative();
		}
	}
}
