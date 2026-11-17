package com.holdmyspot.cat.common.build.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.TemporaryDirectories;
import com.holdmyspot.cat.common.Platform;
import com.holdmyspot.cat.common.build.BuildAdmission;
import com.holdmyspot.cat.common.build.BuildAdmissionCli;
import com.holdmyspot.cat.common.build.BuildObservations;
import com.holdmyspot.cat.common.fakes.test.TestJvmScope;
import com.holdmyspot.cat.common.secrets.BuildAdmissionCliSecrets;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.testng.annotations.Test;
import org.testng.SkipException;

/**
 * Tests build-admission CLI request validation, composition and result mapping.
 */
public final class BuildAdmissionCliTest
{
	/**
	 * Creates the test suite.
	 */
	public BuildAdmissionCliTest()
	{
	}

	/**
	 * Rejects an unknown operation with infrastructure status.
	 */
	@Test
	public void unknownOperationFailsClosed()
	{
		BuildAdmissionCli.Result result = BuildAdmissionCli.execute(null, null, null, new String[] {"unknown"});
		requireThat(result.exitCode(), "exitcode").isEqualTo(2);
	}

	/**
	 * Requires help to be the exact no-option command.
	 */
	@Test
	public void helpRejectsExtraArguments()
	{
		BuildAdmissionCli.Result result = BuildAdmissionCli.execute(null, null, null,
			new String[] {"help", "--unexpected"});
		requireThat(result.exitCode(), "helpExitCode").isEqualTo(2);
	}

	/**
	 * Returns a stable structured error for duplicate options before any context is loaded.
	 */
	@Test
	public void duplicateOptionsReturnStructuredError()
	{
		BuildAdmissionCli.Result result = BuildAdmissionCli.execute(null, null, null,
			new String[] {"run", "--context", "first", "--plan", "plan", "--evidence", "evidence",
				"--context", "second"});
		requireThat(result.exitCode(), "invalidRequestExitCode").isEqualTo(2);
		Object error = result.response().get("error");
		requireThat(error instanceof Map<?, ?>, "structuredError").isTrue();
		Map<?, ?> fields = (Map<?, ?>) error;
		requireThat(fields.size(), "errorFields").isEqualTo(4);
		requireThat(fields.get("kind"), "errorKind").isEqualTo("INVALID_REQUEST");
		requireThat(fields.get("message"), "errorMessage").isNotNull();
		requireThat(fields.get("path"), "pathAbsent").isNull();
		requireThat(fields.get("job_id"), "jobAbsent").isNull();
	}

	/**
	 * Rejects a decimal version even when numeric coercion would turn it into version one.
	 *
	 * @throws IOException if the serialized fixture cannot be created
	 */
	@Test
	public void serializedPlanRequiresIntegralVersion() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("build-cli-invalid-plan-")).toRealPath();
			try (TestJvmScope scope = new TestJvmScope(root.resolve("scope")))
			{
				Path project = Files.createDirectories(root.resolve("project"));
				Path context = writeContext(root, UUID.randomUUID());
				Path plan = root.resolve("plan.json");
				Files.writeString(plan, "{\"format_version\":1.0,\"jobs\":[]}");
				Path evidence = root.resolve("evidence");
				BuildObservations observations = unusedObservations(root);
				BuildAdmissionCli.Result result = BuildAdmissionCliSecrets.getBuildAdmissionCliAccess().execute(scope,
					project, Map.of("PATH", "/bin"), contextValue ->
						new BuildAdmission(scope, contextValue, observations, scope.clock(), Map.of("PATH", "/bin")),
					new String[] {"run", "--context", context.toString(), "--plan", plan.toString(), "--evidence",
						evidence.toString()});
				requireThat(result.exitCode(), "invalidPlanExitCode").isEqualTo(2);
				requireThat(result.response().get("outcome"), "invalidPlanOutcome").
					isEqualTo("infrastructure_failed");
				requireThat(Files.exists(evidence), "invalidPlanCreatesNoEvidenceState").isFalse();
			}
		}
	}

	/**
	 * Exercises a valid serialized plan through the production controller CLI operation.
	 *
	 * @throws IOException if the serialized fixture cannot be created
	 */
	@Test
	public void serializedEmptyPlanUsesCli() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("build-cli-real-operation-")).toRealPath();
			try (TestJvmScope scope = new TestJvmScope(root.resolve("scope")))
			{
				Path project = Files.createDirectories(root.resolve("project"));
				Path context = writeContext(root, UUID.randomUUID());
				Path plan = root.resolve("plan.json");
				Files.writeString(plan, "{\"format_version\":1,\"jobs\":[]}");
				Path evidence = root.resolve("evidence");
				BuildObservations observations = unusedObservations(root);
				BuildAdmissionCli.Result result = BuildAdmissionCliSecrets.getBuildAdmissionCliAccess().execute(scope,
					project, Map.of("PATH", "/bin"), contextValue ->
						new BuildAdmission(scope, contextValue, observations, scope.clock(), Map.of("PATH", "/bin")),
					new String[] {"run", "--context", context.toString(), "--plan", plan.toString(), "--evidence",
						evidence.toString()});
				requireThat(result.exitCode(), "emptyPlanExitCode").isEqualTo(0);
				requireThat(result.response().get("outcome"), "emptyPlanOutcome").isEqualTo("passed");
				requireThat(Files.isDirectory(evidence), "controllerCliOwnsEvidenceDirectory").isTrue();
			}
		}
	}

	/**
	 * Rejects duplicate jobs in the complete serialized batch before CLI evidence or ledger state is created.
	 *
	 * @throws IOException if the serialized fixture cannot be created
	 */
	@Test
	public void invalidBatchCreatesNoCliState() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("build-cli-invalid-batch-")).toRealPath();
			try (TestJvmScope scope = new TestJvmScope(root.resolve("scope")))
			{
				Path project = Files.createDirectories(root.resolve("project"));
				Path context = writeContext(root, UUID.randomUUID());
				Path plan = root.resolve("plan.json");
				String job = "{\"job_id\":\"duplicate\",\"assignment_id\":\"assignment\"," +
					"\"project_path\":\"project\",\"invocation_path\":\"evidence/invocation\"," +
					"\"source_manifest_id\":\"source\",\"excluded_roots\":[],\"output_roots\":[]," +
					"\"gate\":{\"modules\":[\"common/cli\"],\"tests\":[\"BuildAdmissionCliTest\"]," +
					"\"resource_definition_paths\":[],\"resource_review_id\":\"review\"," +
					"\"resource_reviewer\":\"test\"}}";
				Files.writeString(plan, "{\"format_version\":1,\"jobs\":[" + job + "," + job + "]}");
				Path evidence = root.resolve("evidence");
				BuildObservations observations = unusedObservations(root);
				BuildAdmissionCli.Result result = BuildAdmissionCliSecrets.getBuildAdmissionCliAccess().execute(scope,
					project, Map.of("PATH", "/bin"), contextValue ->
						new BuildAdmission(scope, contextValue, observations, scope.clock(), Map.of("PATH", "/bin")),
					new String[] {"run", "--context", context.toString(), "--plan", plan.toString(), "--evidence",
						evidence.toString()});
				requireThat(result.exitCode(), "duplicateBatchCliExitCode").isEqualTo(2);
				requireThat(Files.exists(evidence), "invalidBatchEvidenceAbsent").isFalse();
				requireThat(Files.exists(root.resolve("ledger")), "invalidBatchLedgerAbsent").isFalse();
			}
		}
	}

	/**
	 * Runs the actual modular CLI entry point on Linux against a valid serialized request.
	 * <p>
	 * The case starts a real child JVM through the module path and waits for it to exit, which takes seconds. The
	 * test keeps it because the modular entry point is the boundary it checks.
	 *
	 * @throws IOException if the child process or serialized fixture fails
	 * @throws InterruptedException if the child process wait is interrupted
	 */
	@Test
	public void modularMainRunsControllerOperation() throws IOException, InterruptedException
	{
		if (!Platform.current().isLinux())
			throw new SkipException("The controller's system observation provider requires Linux");
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("build-cli-main-")).toRealPath();
			String modulePath = System.getProperty("jdk.module.path");
			if (modulePath == null || modulePath.isBlank())
				throw new IllegalStateException("The test JVM did not expose jdk.module.path");
			Files.createDirectories(root.resolve("project"));
			Path context = writeContext(root, UUID.fromString(Files.readString(
				Path.of("/proc/sys/kernel/random/boot_id")).strip()));
			Path plan = root.resolve("plan.json");
			Files.writeString(plan, "{\"format_version\":1,\"jobs\":[]}");
			Path evidence = root.resolve("evidence");
			Path temporary = Files.createDirectories(root.resolve("temporary"));
			Path home = Files.createDirectories(root.resolve("home"));
			Path output = root.resolve("cli-output.txt");
			Path javaExecutable = Path.of(System.getProperty("java.home"), "bin", "java");
			ProcessBuilder builder = new ProcessBuilder(javaExecutable.toString(),
				"-Djava.io.tmpdir=" + temporary,
				"-Duser.home=" + home,
				"--enable-native-access=com.holdmyspot.cat.common.cli",
				"--module-path", modulePath,
				"--module", "com.holdmyspot.cat.common.cli/com.holdmyspot.cat.common.build.BuildAdmissionCli",
				"run", "--context", context.toString(), "--plan", plan.toString(), "--evidence", evidence.toString());
			builder.directory(root.toFile());
			builder.redirectErrorStream(true);
			builder.redirectOutput(output.toFile());
			builder.environment().put("HOME", home.toString());
			builder.environment().put("TMPDIR", temporary.toString());
			builder.environment().put("TMP", temporary.toString());
			builder.environment().put("TEMP", temporary.toString());
			builder.environment().put("PATH", "/bin");
			try (Process process = builder.start())
			{
				try
				{
					boolean exited = process.waitFor(30, TimeUnit.SECONDS);
					String response = Files.readString(output);
					requireThat(exited, "modularControllerCliExited").isTrue();
					if (process.exitValue() != 0)
						throw new AssertionError("Modular CLI returned " + process.exitValue() + ": " + response);
					requireThat(response.contains("\"outcome\":\"passed\""), "modularControllerCliResponse").
						isTrue();
					requireThat(Files.isDirectory(evidence), "modularControllerCliEvidenceCreated").isTrue();
				}
			finally
			{
				if (process.isAlive())
					stopProcess(process);
			}
		}
	}
	}

	/**
	 * Maps a failed batch result to CLI exit one without invoking a child build.
	 *
	 * @throws IOException if the owned evidence fixture cannot be created
	 */
	@Test
	public void failedBatchMapsToExitOne() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("build-cli-failed-batch-")).toRealPath();
			Path evidence = Files.createDirectory(root.resolve("evidence"));
			BuildAdmission.JobResult failed = new BuildAdmission.JobResult("failing-child", "failed", 7,
				Path.of("diagnostics/maven.log"), "admission", "terminal", Path.of("terminal.json"));
			BuildAdmission.BatchResult batch = new BuildAdmission.BatchResult(List.of(failed));
			BuildAdmissionCli.Result result = BuildAdmissionCliSecrets.getBuildAdmissionCliAccess().
				batchResult(batch, evidence);
			requireThat(result.exitCode(), "failedBatchExitCode").isEqualTo(1);
			requireThat(result.response().get("outcome"), "failedBatchOutcome").isEqualTo("failed");
		}
	}

	/**
	 * Force-stops a live CLI fixture and waits up to thirty seconds for its exit.
	 *
	 * @param process owned CLI process
	 * @throws IOException if the process remains live after the bounded wait
	 */
	private static void stopProcess(Process process) throws IOException
	{
		process.destroyForcibly();
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
		boolean interrupted = false;
		try
		{
			while (process.isAlive())
			{
				long remaining = deadline - System.nanoTime();
				if (remaining <= 0)
					throw new IOException("Modular CLI remained live during fixture cleanup");
				try
				{
					process.waitFor(remaining, TimeUnit.NANOSECONDS);
				}
				catch (InterruptedException _)
				{
					interrupted = true;
				}
			}
		}
		finally
		{
			if (interrupted)
				Thread.currentThread().interrupt();
		}
	}

	/**
	 * Writes an explicit test host context with one reviewed authority artifact.
	 *
	 * @param root fixture root
	 * @param boot expected host boot identity
	 * @return context file path
	 * @throws IOException if the fixture cannot be written
	 */
	private static Path writeContext(Path root, UUID boot) throws IOException
	{
		Files.writeString(root.resolve("authority.txt"), "reviewed test authority");
		Path context = root.resolve("context.json");
		Files.writeString(context, "{\"format_version\":1,\"context_id\":\"" + UUID.randomUUID() +
			"\",\"host_boot_id\":\"" + boot + "\",\"ledger_path\":\"ledger\",\"authority_id\":\"test\"," +
			"\"authority_actor\":\"test\",\"authority_evidence_paths\":[\"authority.txt\"]," +
			"\"cleanup_owner\":\"test\"}");
		return context;
	}

	/**
	 * Creates host observations that fail if CLI validation samples host or process state.
	 *
	 * @param root owned fixture root
	 * @return observation dependencies that reject every use
	 */
	private static BuildObservations unusedObservations(Path root)
	{
		BuildObservations.Metrics metrics = new BuildObservations.Metrics()
		{
			@Override
			public double cpuLoad()
			{
				throw new AssertionError("CLI validation must not sample host metrics");
			}

			@Override
			public int logicalProcessors()
			{
				throw new AssertionError("CLI validation must not sample host metrics");
			}

			@Override
			public int availableProcessors()
			{
				throw new AssertionError("CLI validation must not sample host metrics");
			}

			@Override
			public Optional<BuildObservations.RawProcess> process(long pid)
			{
				throw new AssertionError("CLI validation must not inspect processes");
			}

			@Override
			public Set<Long> descendants(long pid)
			{
				throw new AssertionError("CLI validation must not inspect processes");
			}
		};
		return new BuildObservations(root.resolve("proc"), metrics, pid ->
		{
			throw new AssertionError("CLI validation must not look up process identities");
		});
	}
}
