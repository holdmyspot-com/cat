package io.github.cowwoc.cat.common.evaluation;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.ToolScope;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.testng.annotations.Test;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Tests certification batch progression.
 */
public final class CertificationTest
{
	/**
	 * Creates the test fixture.
	 */
	public CertificationTest()
	{
	}

	/**
	 * Verifies certification resumes retained work before it allocates a fresh batch.
	 *
	 * @throws IOException if the progression operation fails
	 */
	@Test
	public void resumesRetainedBatchBeforeFreshWork() throws IOException
	{
		try (ToolScope scope = new ToolScope())
		{
			List<String> stages = new ArrayList<>();
		Path retainedBatch = Path.of("retained-batch");
		ObjectNode result = Certification.run(new Certification.Access()
		{
			@Override
			public void publishSemanticControls()
			{
				stages.add("publish-controls");
			}

			@Override
			public void verifySemanticControls()
			{
				stages.add("verify-controls");
			}

			@Override
			public void verifyHeartbeat()
			{
				stages.add("heartbeat");
			}

			@Override
			public void publishBatchSelection()
			{
				stages.add("publish-selection");
			}

			@Override
			public ObjectNode decide()
			{
				stages.add("decide");
				if (stages.contains("resume"))
					return decision(scope.json(), "FAIL");
				return decision(scope.json(), "RUNNING");
			}

			@Override
			public Path resumableBatch()
			{
				stages.add("resumable");
				return retainedBatch;
			}

			@Override
			public ObjectNode resume(Path batch)
			{
				requireThat(batch, "batch").isEqualTo(retainedBatch);
				stages.add("resume");
				return decision(scope.json(), "FAIL");
			}

			@Override
			public int remainingTrials()
			{
				throw new AssertionError("Retained work must resume before checking fresh capacity");
			}

			@Override
			public int maximumConcurrency()
			{
				throw new AssertionError("Retained work must resume before allocating fresh capacity");
			}

			@Override
			public ObjectNode runFresh(int trialCount)
			{
				throw new AssertionError("Retained work must resume before fresh work");
			}
		});

		requireThat(result.path("overall_decision").asString(), "decision").isEqualTo("FAIL");
			requireThat(stages, "stages").containsExactly(List.of("publish-controls", "verify-controls", "heartbeat",
				"publish-selection", "decide", "resumable", "resume", "heartbeat"));
		}
	}

	/**
	 * Creates one retained decision fixture.
	 *
	 * @param json the scope-owned mapper that constructs the decision fixture
	 * @param value the retained overall decision
	 * @return the decision fixture
	 */
	private static ObjectNode decision(JsonMapper json, String value)
	{
		return json.createObjectNode().put("overall_decision", value);
	}
}
