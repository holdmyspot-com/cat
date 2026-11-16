package com.holdmyspot.cat.common.delegation.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.fakes.test.TestJvmScope;
import com.holdmyspot.cat.common.TemporaryDirectories;
import com.holdmyspot.cat.common.delegation.DelegationCli;
import com.holdmyspot.cat.common.delegation.Plan;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.testng.annotations.Test;

/**
 * Tests the public delegation command boundary.
 */
public final class DelegationCliTest
{
	/**
	 * Creates the test fixture.
	 */
	public DelegationCliTest()
	{
	}

	/**
	 * Verifies an operational failure retains per-work identity and state.
	 *
	 * @throws IOException if the temporary fixture cannot be created
	 */
	@Test
	public void operationFailureHasWorkContext() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("delegation-cli-failure-"));
			try (TestJvmScope scope = new TestJvmScope(root))
			{
				Path cohort = Files.createDirectory(root.resolve("cohort"));
				Path project = Files.createDirectory(root.resolve("project"));
				DelegationFixture fixture = new DelegationFixture(root);
				fixture.admission(scope);
				Path context = fixture.contextPath();
				Files.writeString(cohort.resolve("plan.json"), """
					{"format_version":1,"project_path":".","source_paths":["client/mvnw"],
					"excluded_roots":[".git",".cat/sessions",".cat-tmp"],"work":[{"work_id":"runtime",
					"assignment_id":"assignment-1","write_paths":["client/runtime.txt"],"verification":{
					"gate_kind":"project_maven","modules":["common/cli"],"tests":["DelegationTest"],
					"scope_authority":{"assignment_id":"assignment-1","review_id":"review-1",
					"reviewer":"parent","definition_paths":["client/mvnw"],"project_output_roots":[],
					"permitted_owned_roots":["home","repository","temporary","diagnostics"]}}}],
					"cleanup_owner":"parent"}
					""");
				DelegationCli.Result result = DelegationCli.execute(scope, project, Map.of(),
					new String[]{"audit", "--cohort", cohort.toString(), "--work", "runtime", "--build-context",
						context.toString()});

				requireThat(result.work().size(), "failedWorkContext").isEqualTo(1);
				requireThat(result.work().getFirst().assignmentId(), "assignmentContext").isEqualTo("assignment-1");
				requireThat(result.work().getFirst().state(), "failureState").isEqualTo("io_failed");
				requireThat(result.toJson(scope), "snakeCaseWorkFields").contains("\"changed_paths\"");
			}
		}
	}

	/**
	 * Verifies an unknown internal operation fails before creating cohort state.
	 *
	 * @throws IOException if the temporary fixture cannot be created
	 */
	@Test
	public void unknownCommandCreatesNothing() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("delegation-cli-unknown-"));
			try (TestJvmScope scope = new TestJvmScope(root))
			{
				Path project = Files.createDirectory(root.resolve("project"));
				DelegationCli.Result result = DelegationCli.execute(scope, project, Map.of(), new String[]{"claim"});

				requireThat(result.result(), "result").isEqualTo("invalid_request");
				requireThat(result.exitCode(), "exitCode").isEqualTo(2);
				try (DirectoryStream<Path> children = Files.newDirectoryStream(project))
				{
					requireThat(children.iterator().hasNext(), "projectIsUnchanged").isFalse();
				}
			}
		}
	}

	/**
	 * Verifies grouped help is available without creating state.
	 *
	 * @throws IOException if the temporary fixture cannot be opened
	 */
	@Test
	public void groupedHelpCreatesNothing() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("delegation-cli-help-"));
			try (TestJvmScope scope = new TestJvmScope(root))
			{
				Path project = Files.createDirectory(root.resolve("project"));
				DelegationCli.Result result = DelegationCli.execute(scope, project, Map.of(), new String[]{"help"});

				requireThat(result.result(), "result").isEqualTo("available");
				requireThat(result.exitCode(), "exitCode").isEqualTo(0);
				requireThat(result.nextAction(), "usage").contains("prepare --plan");
				try (DirectoryStream<Path> children = Files.newDirectoryStream(project))
				{
					requireThat(children.iterator().hasNext(), "projectIsUnchanged").isFalse();
				}
			}
		}
	}

	/**
	 * Verifies malformed JSON identifies the request path and parser line.
	 *
	 * @throws IOException if the temporary fixture cannot be created
	 */
	@Test
	public void malformedRequestNamesLocation() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("delegation-cli-malformed-"));
			try (TestJvmScope scope = new TestJvmScope(root))
			{
				Path request = root.resolve("delegation-plan.json");
				Files.writeString(request, "{\n  \"format_version\": }\n");
				IOException failure = null;
				try
				{
					Plan.read(scope.json(), request);
				}
				catch (IOException e)
				{
					failure = e;
				}

				requireThat(failure, "failure").isNotNull();
				requireThat(failure.getMessage(), "message").contains(request.toString());
				requireThat(failure.getMessage(), "message").contains("line 2");
			}
		}
	}
}
