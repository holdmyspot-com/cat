package com.holdmyspot.cat.common.optimizer.execution.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static org.testng.Assert.expectThrows;

import com.holdmyspot.cat.common.TemporaryDirectories;
import com.holdmyspot.cat.common.fakes.test.TestJvmScope;
import com.holdmyspot.cat.common.optimizer.contract.BuiltInTool;
import com.holdmyspot.cat.common.optimizer.execution.CheckProgram;
import com.holdmyspot.cat.common.optimizer.execution.CheckProgram.Launch;
import com.holdmyspot.cat.common.optimizer.execution.CheckProgram.Outcome;
import com.holdmyspot.cat.common.optimizer.execution.CheckProgram.Result;
import com.holdmyspot.cat.common.optimizer.execution.CheckProgramStartFailure;
import com.holdmyspot.cat.common.optimizer.execution.CheckProgramStartFailure.Reason;
import com.holdmyspot.cat.common.optimizer.execution.StartupHistory;
import com.holdmyspot.cat.common.optimizer.execution.test.CheckProgramTest.Fixture;
import com.holdmyspot.cat.common.runner.policy.ParentPolicy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.testng.annotations.Test;

/**
 * Exercises the reports of a check program's run whose source launcher is not ready when the run ends, with the real
 * Bubblewrap and Java runtime of the host.
 */
public final class CheckProgramStartupTest
{
	private static final BuiltInTool.CheckProgram DECLARATION = new BuiltInTool.CheckProgram(CheckProgramTest.PROGRAM,
		List.of());
	private static final String PRINTS_AND_EXITS = """
		final class Check
		{
			public static void main(String[] args)
			{
				System.out.println("done");
			}
		}
		""";

	/**
	 * Creates the test.
	 */
	public CheckProgramStartupTest()
	{
	}

	/**
	 * A sandbox that cannot start the program that it was given stops before the launcher starts, and the report is
	 * the sandbox program's message and exit status. The held-out file that the sandbox masks is {@code env}, which
	 * Bubblewrap is then not allowed to execute.
	 *
	 * @throws IOException if a fixture cannot be prepared or inspected
	 */
	@Test
	public void reportsSandboxThatCannotRunItsProgram() throws IOException
	{
		// Requirement: R-L23
		Launch real = CheckProgramTest.nativeLaunch();
		try (TestJvmScope jvm = new TestJvmScope(); TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory(jvm.temporaryDirectory(), "check-mount-"));
			Path env = Path.of("/usr/bin/env").toRealPath();
			Fixture fixture = CheckProgramTest.fixture(root, PRINTS_AND_EXITS);
			Launch launch = new Launch(real.sandbox(), real.javaExecutable(), ParentPolicy.UNSANDBOXED,
				List.of(env), new StartupHistory());

			CheckProgramStartFailure failure = expectThrows(CheckProgramStartFailure.class, () -> CheckProgram.run(launch,
				fixture.input(), fixture.scratchParent(), DECLARATION, Map.of(), CheckProgramTest.NORMAL_TIMEOUT,
				CheckProgramTest.NORMAL_OUTPUT_LIMIT));

			requireThat(failure.reason(), "reason").isEqualTo(Reason.SANDBOX_CANNOT_START);
			requireThat(failure.getMessage(), "message").contains("bwrap:").contains("/usr/bin/env").
				contains("status 1");
			CheckProgramTest.requireScratchClean(fixture.scratchParent());
		}
	}

	/**
	 * A Java executable that exists but cannot run, because its interpreter is gone, makes the launcher exit before it
	 * is ready; the report is the exit status and output of the program that tried to run it.
	 *
	 * @throws IOException if a fixture cannot be prepared or inspected
	 */
	@Test
	public void reportsLauncherThatCannotRun() throws IOException
	{
		// Requirement: R-L23
		Launch real = CheckProgramTest.nativeLaunch();
		try (TestJvmScope jvm = new TestJvmScope(); TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory(jvm.temporaryDirectory(), "check-damaged-"));
			Fixture fixture = CheckProgramTest.fixture(root, PRINTS_AND_EXITS);
			Path java = runtime(root, "#!/nonexistent/interpreter\n");
			Launch launch = new Launch(real.sandbox(), java, ParentPolicy.UNSANDBOXED, List.of(), new StartupHistory());

			CheckProgramStartFailure failure = expectThrows(CheckProgramStartFailure.class, () -> CheckProgram.run(launch,
				fixture.input(), fixture.scratchParent(), DECLARATION, Map.of(), CheckProgramTest.NORMAL_TIMEOUT,
				CheckProgramTest.NORMAL_OUTPUT_LIMIT));

			requireThat(failure.reason(), "reason").isEqualTo(Reason.LAUNCHER_UNUSABLE);
			requireThat(failure.getMessage(), "message").contains("launcher exited with status 127").
				contains(java.toString());
			CheckProgramTest.requireScratchClean(fixture.scratchParent());
		}
	}

	/**
	 * A Java executable that is missing, a runtime without its module image and a sandbox program that is missing are
	 * found before the run starts, and the report names the path.
	 *
	 * @throws IOException if a fixture cannot be prepared or inspected
	 */
	@Test
	public void reportsMissingFilesBeforeTheRunStarts() throws IOException
	{
		// Requirement: R-L23
		Launch real = CheckProgramTest.nativeLaunch();
		try (TestJvmScope jvm = new TestJvmScope(); TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory(jvm.temporaryDirectory(), "check-missing-"));
			Fixture fixture = CheckProgramTest.fixture(root, PRINTS_AND_EXITS);
			Path missingJava = root.resolve("absent runtime").resolve("bin").resolve("java");
			Path noImage = runtime(root, "#!/nonexistent/interpreter\n");
			Files.delete(noImage.getParent().getParent().resolve("lib").resolve("modules"));
			Path missingSandbox = root.resolve("absent-bwrap");

			CheckProgramStartFailure java = expectThrows(CheckProgramStartFailure.class, () -> run(
				new Launch(real.sandbox(), missingJava, ParentPolicy.UNSANDBOXED, List.of(), new StartupHistory()),
				fixture, CheckProgramTest.NORMAL_TIMEOUT));
			CheckProgramStartFailure image = expectThrows(CheckProgramStartFailure.class, () -> run(
				new Launch(real.sandbox(), noImage, ParentPolicy.UNSANDBOXED, List.of(), new StartupHistory()), fixture,
				CheckProgramTest.NORMAL_TIMEOUT));
			CheckProgramStartFailure sandbox = expectThrows(CheckProgramStartFailure.class, () -> run(
				new Launch(missingSandbox, real.javaExecutable(), ParentPolicy.UNSANDBOXED, List.of(),
					new StartupHistory()), fixture, CheckProgramTest.NORMAL_TIMEOUT));

			requireThat(java.reason(), "javaReason").isEqualTo(Reason.LAUNCHER_UNUSABLE);
			requireThat(java.getMessage(), "javaMessage").contains(missingJava.toString());
			requireThat(image.reason(), "imageReason").isEqualTo(Reason.LAUNCHER_UNUSABLE);
			requireThat(image.getMessage(), "imageMessage").contains("lib/modules");
			requireThat(sandbox.reason(), "sandboxReason").isEqualTo(Reason.SANDBOX_CANNOT_START);
			requireThat(sandbox.getMessage(), "sandboxMessage").contains(missingSandbox.toString());
			CheckProgramTest.requireScratchClean(fixture.scratchParent());
		}
	}

	/**
	 * A time limit that ends before the launcher is ready is an infrastructure failure that gives the limit, the time
	 * taken and the last stage that CAT saw, and, once an earlier run on the same launcher has reached ready, how long
	 * that took.
	 *
	 * @throws IOException          if a fixture or process cannot be used
	 * @throws InterruptedException if a process wait is interrupted
	 */
	@Test
	public void reportsLimitElapsedStageAndNormalTime() throws IOException,
		InterruptedException
	{
		// Requirement: R-L23
		Launch real = CheckProgramTest.nativeLaunch();
		try (TestJvmScope jvm = new TestJvmScope(); TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory(jvm.temporaryDirectory(), "check-limit-"));
			Fixture fixture = CheckProgramTest.fixture(root, PRINTS_AND_EXITS);
			Launch launch = new Launch(real.sandbox(), real.javaExecutable(), ParentPolicy.UNSANDBOXED, List.of(),
				new StartupHistory());
			Duration tooShort = Duration.ofMillis(500);

			CheckProgramStartFailure withoutHistory = expectThrows(CheckProgramStartFailure.class, () -> run(launch,
				fixture, tooShort));
			Result earlier = run(launch, fixture, CheckProgramTest.NORMAL_TIMEOUT);
			CheckProgramStartFailure withHistory = expectThrows(CheckProgramStartFailure.class, () -> run(launch,
				fixture, tooShort));

			requireThat(earlier.outcome(), "earlierOutcome").isEqualTo(Outcome.PASS);
			requireThat(withoutHistory.reason(), "reason").isEqualTo(Reason.NOT_READY);
			requireThat(withoutHistory.isInfrastructure(), "isInfrastructure").isTrue();
			requireThat(withoutHistory.getMessage(), "withoutHistoryMessage").contains("time limit of 0.5 s").
				contains("Last start-up stage reached: ").contains("normal start time is not known");
			requireThat(withHistory.getMessage(), "withHistoryMessage").contains("time limit of 0.5 s").
				contains("median").contains("(1 run)").contains("shorter than the longest");
			CheckProgramTest.requireScratchClean(fixture.scratchParent());
		}
	}

	/**
	 * A launcher that starts and then waits without using processor time is stalled. The Java runtime of the sandbox
	 * is replaced by a program that does that, and the report names the stage that it stalled in.
	 *
	 * @throws IOException if a fixture cannot be prepared or inspected
	 */
	@Test
	public void reportsLauncherThatStartsAndThenWaits() throws IOException
	{
		// Requirement: R-L23
		Launch real = CheckProgramTest.nativeLaunch();
		try (TestJvmScope jvm = new TestJvmScope(); TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory(jvm.temporaryDirectory(), "check-stalled-"));
			Fixture fixture = CheckProgramTest.fixture(root, PRINTS_AND_EXITS);
			// Stands for a Java runtime that hangs from a bug: it starts, then waits for something that never comes
			String waits = "#!" + real.javaExecutable() + " --source " + Runtime.version().feature() + """

				final class Waits
				{
					public static void main(String[] args) throws InterruptedException
					{
						Thread.sleep(600_000);
					}
				}
				""";
			Path java = runtime(root, waits);
			Launch launch = new Launch(real.sandbox(), java, ParentPolicy.UNSANDBOXED, List.of(), new StartupHistory());

			CheckProgramStartFailure failure = expectThrows(CheckProgramStartFailure.class, () -> run(launch, fixture,
				Duration.ofSeconds(10)));

			requireThat(failure.reason(), "reason").isEqualTo(Reason.LAUNCHER_STALLED);
			requireThat(failure.getMessage(), "message").contains("launcher is stalled").contains("launcher starting").
				contains("time limit of 10 s");
			CheckProgramTest.requireScratchClean(fixture.scratchParent());
		}
	}

	/**
	 * Runs the fixture's program.
	 *
	 * @param launch  the launch conditions
	 * @param fixture the staged input
	 * @param limit   the time limit
	 * @return the result
	 * @throws IOException          if the run could not start
	 * @throws InterruptedException if a process wait is interrupted
	 */
	private static Result run(Launch launch, Fixture fixture, Duration limit) throws IOException, InterruptedException
	{
		return CheckProgram.run(launch, fixture.input(), fixture.scratchParent(), DECLARATION, Map.of(), limit,
			CheckProgramTest.NORMAL_OUTPUT_LIMIT);
	}

	/**
	 * Creates a Java runtime folder whose executable has the given content.
	 *
	 * @param root    a temporary directory owned by the test
	 * @param content the executable's content
	 * @return the path of the executable
	 * @throws IOException if the runtime cannot be written
	 */
	private static Path runtime(Path root, String content) throws IOException
	{
		Path runtime = Files.createDirectories(root.resolve("fake runtime"));
		Path java = Files.writeString(Files.createDirectories(runtime.resolve("bin")).resolve("java"), content);
		if (!java.toFile().setExecutable(true))
			throw new IOException("Cannot make " + java + " executable");
		Files.writeString(Files.createDirectories(runtime.resolve("lib")).resolve("modules"), "modules");
		return java;
	}
}
