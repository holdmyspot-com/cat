package io.github.cowwoc.cat.common.optimizer.report;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.optimizer.campaign.Applicability;
import io.github.cowwoc.cat.common.optimizer.campaign.CertificationHistory;
import io.github.cowwoc.cat.common.optimizer.campaign.CertificationRecord;
import io.github.cowwoc.cat.common.optimizer.campaign.MeasurementIdentity;
import io.github.cowwoc.cat.common.optimizer.campaign.Outcome;
import io.github.cowwoc.cat.common.optimizer.contract.CertificationScope;
import io.github.cowwoc.cat.common.optimizer.inventory.Sha256;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * The delivered certifications of a target and whether each harness's latest one still applies.
 * <p>
 * <b>Thread Safety:</b> This class is immutable and thread-safe.
 *
 * @param latest the latest record of each harness with its applicability, in the order of the history; not empty
 * @param earlier the records that a later record of the same harness followed, in the order of the history
 */
public record CertificationStatus(List<Latest> latest, List<CertificationRecord> earlier)
{
	/**
	 * Creates a status.
	 *
	 * @param latest the latest record of each harness with its applicability, in the order of the history
	 * @param earlier the records that a later record of the same harness followed, in the order of the history
	 * @throws NullPointerException if any of the arguments are null or contain null
	 * @throws IllegalArgumentException if {@code latest} is empty
	 */
	public CertificationStatus
	{
		requireThat(latest, "latest").isNotEmpty();
		latest = List.copyOf(latest);
		earlier = List.copyOf(earlier);
	}

	/**
	 * Compares the latest record of each harness with what CAT knows now.
	 *
	 * @param history the target's certification history
	 * @param currentPromptFiles the content hash of each prompt file that exists now, keyed by its path relative to
	 *   the project; it must cover every prompt file that a latest record lists and that still exists
	 * @param known the conditions that CAT knows now
	 * @param minimumPassRate the minimum pass rate {@code p_min} that a certification must prove now, for a record
	 * @return the status
	 * @throws NullPointerException if any of the arguments are null
	 * @throws IllegalArgumentException if {@code history} holds no record
	 */
	public static CertificationStatus of(CertificationHistory history, Map<Path, Sha256> currentPromptFiles,
		MeasurementIdentity known, Function<CertificationRecord, BigDecimal> minimumPassRate)
	{
		requireThat(history, "history").isNotNull();
		requireThat(currentPromptFiles, "currentPromptFiles").isNotNull();
		requireThat(known, "known").isNotNull();
		requireThat(minimumPassRate, "minimumPassRate").isNotNull();
		List<Latest> latest = new ArrayList<>();
		for (CertificationRecord record : history.latest())
		{
			Map<Path, Sha256> recordedFiles = new LinkedHashMap<>();
			for (Path path : record.promptFiles().keySet())
			{
				Sha256 hash = currentPromptFiles.get(path);
				if (hash != null)
					recordedFiles.put(path, hash);
			}
			latest.add(new Latest(record, record.applicability(recordedFiles, known, minimumPassRate.apply(record))));
		}
		return new CertificationStatus(latest, history.earlier());
	}

	/**
	 * Returns the target that the certifications are of.
	 *
	 * @return the target, relative to the project
	 */
	public Path target()
	{
		return latest.getFirst().record().target();
	}

	/**
	 * Returns the human summary.
	 *
	 * @return the summary text
	 */
	public String summary()
	{
		List<String> lines = new ArrayList<>();
		lines.add("Certification history of " + target());
		for (Latest entry : latest)
		{
			CertificationRecord record = entry.record();
			CertificationScope scope = record.scope();
			lines.add("Latest certification in " + record.harness().orElse("an unrecorded harness") + ": scope " +
				scope.id() + " (" + scope.summary() + ")");
			ReportSummary.attempt(lines, AttemptSummary.of(record.certificationIndex(), Outcome.CERTIFIED.label(),
				entry.applicability()));
		}
		if (!earlier.isEmpty())
		{
			lines.add("Earlier certifications:");
			for (CertificationRecord record : earlier)
			{
				lines.add("  Attempt " + record.certificationIndex() + " in " +
					record.harness().orElse("an unrecorded harness") + ": certified, scope " + record.scope().id());
			}
		}
		return String.join("\n", lines);
	}

	/**
	 * A harness's latest certification and whether it still applies.
	 *
	 * @param record the harness's latest certification record
	 * @param applicability indicates whether the certification applies to the current prompt files and to the
	 *   conditions that CAT knows
	 */
	public record Latest(CertificationRecord record, Applicability applicability)
	{
		/**
		 * Creates a latest certification.
		 *
		 * @param record the harness's latest certification record
		 * @param applicability whether the certification applies to the current prompt files and to the conditions
		 *   that CAT knows
		 * @throws NullPointerException if any of the arguments are null
		 */
		public Latest
		{
			requireThat(record, "record").isNotNull();
			requireThat(applicability, "applicability").isNotNull();
		}
	}
}
