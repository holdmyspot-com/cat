package io.github.cowwoc.cat.common.evaluation;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.TemporaryDirectories;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.testng.annotations.Test;
import tools.jackson.databind.node.ObjectNode;

/**
 * Tests the package-local lifecycle for opaque validator evidence.
 */
public final class ValidatorLifecycleTest
{
	/**
	 * Creates the test fixture.
	 */
	public ValidatorLifecycleTest()
	{
	}

	/**
	 * Verifies the lifecycle groups opaque evidence, validates its returned verdicts, and records them in stable order.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test
	public void recordsOpaqueEvidenceInStableOrder() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path workspace = cleanup.add(Files.createTempDirectory("validator-lifecycle"));
			List<String> recorded = new ArrayList<>();
			ValidatorLifecycle.recordRound(new ValidatorLifecycle.Access<>()
			{
				@Override
				public boolean requiresSemanticReview(String evidence)
				{
					return true;
				}

				@Override
				public void recordDeterministicVerdict(String evidence)
				{
					throw new AssertionError("All fixture evidence requires semantic review");
				}

				@Override
				public String key(String evidence)
				{
					return evidence;
				}

				@Override
				public Set<String> assertionIds(String evidence)
				{
					return Set.of("result");
				}

				@Override
				public void publishActiveGroup(int ordinal)
				{
				}

				@Override
				public ValidatorLifecycle.GroupResult<String> execute(ValidatorSchedule.Group<String> group,
					Path groupWorkspace) throws IOException
				{
					ObjectNode verdicts = ValidatorLifecycle.newVerdicts(group.evidence(), this::key, this::assertionIds);
					for (String evidence : group.evidence())
					{
						ObjectNode verdict = (ObjectNode) verdicts.path(evidence);
						verdict.put("status", "passed");
						ObjectNode assertion = (ObjectNode) verdict.path("assertions").path("result");
						assertion.put("status", "passed");
						assertion.put("evidence", "fixture");
					}
					return new ValidatorLifecycle.GroupResult<>(group, verdicts, groupWorkspace.resolve("verdicts.json"), 1, 2);
				}

				@Override
				public void recordFailure(List<String> evidence, Path groupWorkspace, IOException failure)
				{
					throw new AssertionError(failure);
				}

				@Override
				public void recordVerdict(String evidence, ObjectNode verdicts, Path result, long startedAtNanos,
					long completedAtNanos, long mergedAtNanos)
				{
					recorded.add(evidence);
				}
			}, List.of("trial-2/b", "trial-1/a"), workspace, 2);

			requireThat(recorded, "recorded").containsExactly(List.of("trial-1/a", "trial-2/b"));
		}
	}

	/**
	 * Verifies a malformed runner verdict is recorded as a controlled group failure without recording a trial verdict.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test
	public void recordsMalformedVerdictAsGroupFailure() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path workspace = cleanup.add(Files.createTempDirectory("validator-lifecycle"));
			List<String> failures = new ArrayList<>();
			List<String> recorded = new ArrayList<>();
			ValidatorLifecycle.recordCrossTrial(new ValidatorLifecycle.Access<>()
			{
				@Override
				public boolean requiresSemanticReview(String evidence)
				{
					return true;
				}

				@Override
				public void recordDeterministicVerdict(String evidence)
				{
					throw new AssertionError("All fixture evidence requires semantic review");
				}

				@Override
				public String key(String evidence)
				{
					return evidence;
				}

				@Override
				public Set<String> assertionIds(String evidence)
				{
					return Set.of("result");
				}

				@Override
				public void publishActiveGroup(int ordinal)
				{
				}

				@Override
				public ValidatorLifecycle.GroupResult<String> execute(ValidatorSchedule.Group<String> group,
					Path groupWorkspace) throws IOException
				{
					return new ValidatorLifecycle.GroupResult<>(group, ValidatorLifecycle.newVerdicts(group.evidence(),
						this::key, this::assertionIds), groupWorkspace.resolve("verdicts.json"), 1, 2);
				}

				@Override
				public void recordFailure(List<String> evidence, Path groupWorkspace, IOException failure)
				{
					failures.addAll(evidence);
				}

				@Override
				public void recordVerdict(String evidence, ObjectNode verdicts, Path result, long startedAtNanos,
					long completedAtNanos, long mergedAtNanos)
				{
					recorded.add(evidence);
				}
			}, List.of("trial-1/a"), workspace);

			requireThat(failures, "failures").containsExactly(List.of("trial-1/a"));
			requireThat(recorded, "recorded").isEmpty();
		}
	}
}
