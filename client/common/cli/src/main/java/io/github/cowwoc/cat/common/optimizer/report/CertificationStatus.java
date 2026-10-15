package io.github.cowwoc.cat.common.optimizer.report;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.optimizer.campaign.Applicability;
import io.github.cowwoc.cat.common.optimizer.campaign.CertificationRecord;
import io.github.cowwoc.cat.common.optimizer.campaign.Outcome;
import io.github.cowwoc.cat.common.optimizer.contract.CertificationScope;

import java.util.ArrayList;
import java.util.List;

/**
 * A delivered certification of a target and whether it still applies.
 * <p>
 * <b>Thread Safety:</b> This class is immutable and thread-safe.
 *
 * @param record the target's certification record
 * @param applicability whether the certification applies to the current prompt files and to the conditions that
 *   CAT knows
 */
public record CertificationStatus(CertificationRecord record, Applicability applicability)
{
	/**
	 * Creates a status.
	 *
	 * @param record the target's certification record
	 * @param applicability whether the certification applies to the current prompt files and to the conditions that
	 *   CAT knows
	 * @throws NullPointerException if any of the arguments are null
	 */
	public CertificationStatus
	{
		requireThat(record, "record").isNotNull();
		requireThat(applicability, "applicability").isNotNull();
	}

	/**
	 * Returns the human summary.
	 *
	 * @return the summary text
	 */
	public String summary()
	{
		List<String> lines = new ArrayList<>();
		CertificationScope scope = record.scope();
		lines.add("Certification record of " + record.target() + ", scope " + scope.id() + " (" + scope.summary() +
			")");
		ReportSummary.attempt(lines, AttemptSummary.of(record.certificationIndex(), Outcome.CERTIFIED.label(),
			applicability));
		return String.join("\n", lines);
	}
}
