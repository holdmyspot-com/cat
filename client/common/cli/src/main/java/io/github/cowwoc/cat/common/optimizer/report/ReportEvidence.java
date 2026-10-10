package io.github.cowwoc.cat.common.optimizer.report;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.optimizer.campaign.AssumptionCheck;

import java.nio.file.Path;
import java.util.List;

/**
 * The observations that a status report needs and that the campaign state does not hold.
 * <p>
 * <b>Thread Safety:</b> This class is immutable and thread-safe.
 *
 * @param observations the adopted outcomes of the reported phase, each with the concurrency level it ran at
 * @param accessedPaths the absolute paths that trials accessed
 * @param protectedRoots the absolute roots of state that no trial may access, such as another trial's records
 * @param symbolicLinks {@code true} if staging replaced symbolic links with copies of their targets
 * @param interrupted {@code true} if work stopped and can resume
 * @param infrastructureFailure the precise failure that stopped work and is not a pass or fail outcome, or
 *   {@code null} if none did
 */
public record ReportEvidence(List<AssumptionCheck.Observation> observations,
	List<Path> accessedPaths, List<Path> protectedRoots,
	boolean symbolicLinks, boolean interrupted, String infrastructureFailure)
{
	/**
	 * No observations beyond the campaign state.
	 */
	public static final ReportEvidence NONE = new ReportEvidence(List.of(), List.of(), List.of(), false,
		false, null);

	/**
	 * Creates evidence.
	 *
	 * @param observations the adopted outcomes of the reported phase, each with the concurrency level it ran at
	 * @param accessedPaths the absolute paths that trials accessed
	 * @param protectedRoots the absolute roots of state that no trial may access
	 * @param symbolicLinks {@code true} if staging replaced symbolic links with copies of their targets
	 * @param interrupted {@code true} if work stopped and can resume
	 * @param infrastructureFailure the precise failure that stopped work, or {@code null} if none did
	 * @throws NullPointerException if a component other than {@code infrastructureFailure} is null
	 * @throws IllegalArgumentException if {@code infrastructureFailure} is blank
	 */
	public ReportEvidence
	{
		requireThat(observations, "observations").isNotNull();
		requireThat(accessedPaths, "accessedPaths").isNotNull();
		requireThat(protectedRoots, "protectedRoots").isNotNull();
		if (infrastructureFailure != null)
			requireThat(infrastructureFailure, "infrastructureFailure").isNotBlank();
		observations = List.copyOf(observations);
		accessedPaths = List.copyOf(accessedPaths);
		protectedRoots = List.copyOf(protectedRoots);
	}
}
