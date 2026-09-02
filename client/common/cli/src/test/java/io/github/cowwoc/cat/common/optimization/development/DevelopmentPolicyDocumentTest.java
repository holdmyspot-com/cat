package io.github.cowwoc.cat.common.optimization.development;

import io.github.cowwoc.cat.common.TemporaryDirectories;
import io.github.cowwoc.cat.common.ToolScope;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.testng.annotations.AfterClass;
import org.testng.annotations.Test;

/**
 * Tests the strict versioned development-policy document.
 */
public final class DevelopmentPolicyDocumentTest
{
	private final ToolScope scope = new ToolScope();

	/**
	 * Creates the test fixture.
	 */
	public DevelopmentPolicyDocumentTest()
	{
	}

	/**
	 * Closes the JSON scope after all tests.
	 */
	@AfterClass(alwaysRun = true)
	public void closeScope()
	{
		scope.close();
	}

	/**
	 * Verifies every executable threshold survives a document round trip.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test
	public void roundTripsEveryThreshold() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("development-policy-document-"));
			Path document = root.resolve("policy.json");
			DevelopmentPolicy original = new DevelopmentPolicy("development-v1", 6, 4, 18, 2, 3, 2, 12, 1, 3, 0.01);

			DevelopmentPolicyDocument.write(scope.json(), document, original);
			DevelopmentPolicy restored = DevelopmentPolicyDocument.read(scope.json(), document);

			requireThat(restored.identifier(), "identifier").isEqualTo(original.identifier());
			requireThat(restored.maximumPairs(), "maximumPairs").isEqualTo(original.maximumPairs());
			requireThat(restored.maximumSurvivors(), "maximumSurvivors").isEqualTo(original.maximumSurvivors());
			requireThat(restored.readinessTrials(), "readinessTrials").isEqualTo(original.readinessTrials());
			requireThat(restored.plateauMinimumImprovement(), "plateauMinimumImprovement").isEqualTo(
				original.plateauMinimumImprovement());
		}
	}

	/**
	 * Verifies an unknown field is rejected rather than ignored.
	 *
	 * @throws IOException for the expected malformed document
	 */
	@Test(expectedExceptions = IOException.class,
		expectedExceptionsMessageRegExp = ".*unsupported or missing fields.*")
	public void rejectsUnknownField() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("development-policy-document-"));
			Path document = root.resolve("policy.json");
			Files.writeString(document, """
				{"schema_version":"development-policy-v1","identifier":"development-v1","initial_pairs":6,
				"additional_pairs":4,"maximum_pairs":18,"maximum_survivors":2,"promotion_net_wins":3,
				"material_regression_net_losses":2,"readiness_trials":12,"readiness_maximum_failures":1,
				"plateau_rounds":3,"plateau_minimum_improvement":0.01,"surprise":true}
				""");

			DevelopmentPolicyDocument.read(scope.json(), document);
		}
	}
}
