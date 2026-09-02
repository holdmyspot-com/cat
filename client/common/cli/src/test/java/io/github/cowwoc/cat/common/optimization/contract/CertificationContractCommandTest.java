package io.github.cowwoc.cat.common.optimization.contract;

import io.github.cowwoc.cat.common.TemporaryDirectories;
import io.github.cowwoc.cat.common.ToolScope;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import org.testng.annotations.AfterClass;
import org.testng.annotations.Test;

/**
 * Tests the certification-contract command projection.
 */
public final class CertificationContractCommandTest
{
	private final ToolScope scope = new ToolScope();

	/**
	 * Creates the test fixture.
	 */
	public CertificationContractCommandTest()
	{
	}

	/**
	 * Closes the mapper scope after tests finish.
	 */
	@AfterClass(alwaysRun = true)
	public void closeScope()
	{
		scope.close();
	}

	/**
	 * Verifies a blocked contract reports each actionable issue and does not write canonical output.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test
	public void reportsBlockedContract() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("certification-contract-command-"));
			Path input = Files.writeString(root.resolve("input.json"), """
				{
				  "schemaVersion":"certification-contract-v1", "artifacts":[], "sourceSpans":[],
				  "requirements":[], "applicabilityRules":[], "distributions":[], "families":[],
				  "assertions":[], "evaluatorProtocolIds":[], "promptTests":[]
				}
				""");
			Path output = root.resolve("canonical.json");
			StringBuilder report = new StringBuilder();

			CertificationContract.Status status = CertificationContractCommand.validate(scope.json(), input,
				Optional.of(output), report);

			requireThat(status, "status").isEqualTo(CertificationContract.Status.BLOCKED);
			requireThat(report.toString(), "report").contains("status=blocked").contains("EMPTY_REQUIREMENTS").
				contains("EMPTY_FAMILIES");
			requireThat(Files.exists(output), "canonicalOutputExists").isFalse();
		}
	}
}
