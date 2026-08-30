package io.github.cowwoc.cat.common.evaluation;

import java.nio.file.Path;
import java.util.Map;

/**
 * Names direct CAT work directories whose lifecycle is owned by prompt evaluation.
 */
public final class WorkDirectories
{
	public static final String RUNNER_ARTIFACTS = "prompt-evaluation-runner-artifacts";
	public static final String INPUT_ARTIFACTS = "prompt-evaluation-input-artifacts";
	public static final String EVIDENCE_ARTIFACTS = "prompt-evaluation-evidence-artifacts";
	public static final String SUITE_ARTIFACTS = "prompt-evaluation-suite-artifacts";
	public static final String PREFLIGHT = "prompt-evaluation-preflight";
	public static final String ASSEMBLIES = "prompt-evaluation-assemblies";
	public static final String LIFECYCLE_SOAKS = "prompt-evaluation-lifecycle-soaks";
	private static final Map<String, String> CONSUMERS = Map.of(
		RUNNER_ARTIFACTS, "content-addressed runner artifacts",
		INPUT_ARTIFACTS, "content-addressed input artifacts",
		EVIDENCE_ARTIFACTS, "content-addressed execution evidence",
		SUITE_ARTIFACTS, "content-addressed evaluation suites",
		PREFLIGHT, "reusable preflight cache",
		ASSEMBLIES, "reusable assembled runner cache",
		LIFECYCLE_SOAKS, "lifecycle verification receipts");

	/**
	 * Prevents construction of this constants holder.
	 */
	private WorkDirectories()
	{
	}

	/**
	 * Returns the consumer that makes a direct CAT work entry durable.
	 *
	 * @param entry a direct CAT work entry
	 * @return the consumer name, or {@code null} when prompt evaluation does not own the entry
	 */
	public static String consumer(Path entry)
	{
		return CONSUMERS.get(entry.getFileName().toString());
	}
}
