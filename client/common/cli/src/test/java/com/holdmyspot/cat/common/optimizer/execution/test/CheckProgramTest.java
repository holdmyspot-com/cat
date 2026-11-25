package com.holdmyspot.cat.common.optimizer.execution.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static org.testng.Assert.expectThrows;

import com.holdmyspot.cat.common.Platform;
import com.holdmyspot.cat.common.TemporaryDirectories;
import com.holdmyspot.cat.common.fakes.test.TestJvmScope;
import com.holdmyspot.cat.common.optimizer.contract.BuiltInTool;
import com.holdmyspot.cat.common.optimizer.execution.CheckProgram;
import com.holdmyspot.cat.common.optimizer.execution.CheckProgram.Launch;
import com.holdmyspot.cat.common.optimizer.execution.CheckProgram.Outcome;
import com.holdmyspot.cat.common.optimizer.execution.CheckProgram.Result;
import com.holdmyspot.cat.common.runner.policy.HostAccess;
import com.holdmyspot.cat.common.runner.policy.ParentPolicy;
import com.holdmyspot.cat.common.runner.policy.PolicyRefusal;

import java.io.IOException;
import java.lang.module.ModuleFinder;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;

import org.testng.SkipException;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

/**
 * Exercises check-program execution under Bubblewrap using generated Java sources and files.
 */
public final class CheckProgramTest
{
	private static final String PROGRAM = "checks/Check.java";
	private static final String INPUT_FILE = "project/message.txt";
	private static final Duration NORMAL_TIMEOUT = Duration.ofSeconds(30);
	private static final int NORMAL_OUTPUT_LIMIT = 16 * 1024;
	private static final String SOURCE_MARKER = "private check source marker";

	/**
	 * Creates the test.
	 */
	public CheckProgramTest()
	{
	}

	/**
	 * Reports the required Windows host arrangement before selecting a sandbox executable.
	 */
	@Test
	public void refusesNativeWindowsLaunch()
	{
		// Requirement: R-L21
		IOException failure = expectThrows(IOException.class, () ->
			CheckProgram.ownerLaunch(Platform.WINDOWS_X64, Map.of(), List.of(ParentPolicy.UNSANDBOXED), List.of()));

		requireThat(failure.getMessage(), "message").contains("WSL2");
	}

	/**
	 * Refuses a sandbox executable writable under any authenticated owner reading.
	 *
	 * @throws IOException if the system sandbox cannot be located
	 */
	@Test
	public void rejectsWritableSandboxExecutable() throws IOException
	{
		// Requirement: R-L21
		Launch nativeLaunch = nativeLaunch();
		Path binary = nativeLaunch.bubblewrap();
		Map<String, String> environment = Map.of("PATH", binary.getParent().toString());
		ParentPolicy earlier = new ParentPolicy(true, List.of(binary), List.of(), List.of(), List.of(),
			HostAccess.ALL, true, List.of());
		ParentPolicy current = new ParentPolicy(true, List.of(), List.of(), List.of(), List.of(), HostAccess.ALL,
			true, List.of());

		expectThrows(PolicyRefusal.class, () ->
			CheckProgram.ownerLaunch(Platform.current(), environment, List.of(earlier, current), List.of()));
	}

	/**
	 * Maps ordinary Java exit codes to the check's outcomes and keeps stdout before stderr.
	 *
	 * @param status the program's exit status
	 * @param expected the corresponding check outcome
	 * @throws IOException if a fixture or process cannot be used
	 * @throws InterruptedException if a process wait is interrupted
	 */
	@Test(dataProvider = "exitStatuses")
	public void classifiesExitStatus(int status, Outcome expected) throws IOException, InterruptedException
	{
		// Requirement: R-L21
		Launch launch = nativeLaunch();
		try (TestJvmScope jvm = new TestJvmScope(); TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory(jvm.temporaryDirectory(), "check-exit-"));
			Fixture fixture = fixture(root, """
				final class Check
				{
					public static void main(String[] args)
					{
						System.err.println("stderr line");
						System.out.println("stdout line");
						System.exit(Integer.parseInt(args[0]));
					}
				}
				""");

			Result result = CheckProgram.run(launch, fixture.input(), fixture.scratchParent(),
				new BuiltInTool.CheckProgram(PROGRAM, List.of(String.valueOf(status))), Map.of(),
				NORMAL_TIMEOUT, NORMAL_OUTPUT_LIMIT);

			requireThat(result.outcome(), "outcome").isEqualTo(expected);
			String observed = result.observed();
			requireThat(observed, "observed").contains("stdout line").contains("stderr line");
			requireThat(observed.indexOf("stdout line"), "stdoutOffset").isLessThan(observed.indexOf("stderr line"));
			if (expected == Outcome.ERROR)
				requireThat(observed, "exitError").contains(String.valueOf(status));
			requireScratchClean(fixture.scratchParent());
		}
	}

	/**
	 * Returns a passing, failing and erroneous ordinary exit status.
	 *
	 * @return the data rows
	 */
	@DataProvider
	public static Object[][] exitStatuses()
	{
		return new Object[][]{{0, Outcome.PASS}, {1, Outcome.FAIL}, {2, Outcome.ERROR}};
	}

	/**
	 * Reports a Java compilation failure as a program error with a compiler diagnostic that names the failing line
	 * without quoting the program's source, since the observed value can reach a judge.
	 *
	 * @throws IOException if a fixture or process cannot be used
	 * @throws InterruptedException if a process wait is interrupted
	 */
	@Test
	public void reportsCompilationError() throws IOException, InterruptedException
	{
		// Requirement: R-L21
		Launch launch = nativeLaunch();
		try (TestJvmScope jvm = new TestJvmScope(); TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory(jvm.temporaryDirectory(), "check-compile-"));
			Fixture fixture = fixture(root, """
				final class Check
				{
					public static void main(String[] args)
					{
						missingForCompilationFixture(); // %s
					}
				}
				""".formatted(SOURCE_MARKER));

			Result result = CheckProgram.run(launch, fixture.input(), fixture.scratchParent(),
				new BuiltInTool.CheckProgram(PROGRAM, List.of()), Map.of(), NORMAL_TIMEOUT, NORMAL_OUTPUT_LIMIT);

			requireThat(result.outcome(), "outcome").isEqualTo(Outcome.ERROR);
			requireThat(result.observed(), "compilerDiagnostic").contains(PROGRAM + ":5: error: cannot find symbol").
				contains("missingForCompilationFixture").doesNotContain(SOURCE_MARKER);
			requireScratchClean(fixture.scratchParent());
		}
	}

	/**
	 * Leaves compiler warnings, which quote the program's source, out of the observed value of a program that runs.
	 *
	 * @throws IOException if a fixture or process cannot be used
	 * @throws InterruptedException if a process wait is interrupted
	 */
	@Test
	public void omitsCompilerWarnings() throws IOException, InterruptedException
	{
		// Requirement: R-L21
		Launch launch = nativeLaunch();
		try (TestJvmScope jvm = new TestJvmScope(); TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory(jvm.temporaryDirectory(), "check-warning-"));
			Fixture fixture = fixture(root, """
				final class Check
				{
					public static void main(String[] args)
					{
						int year = new java.util.Date().getYear(); // %s
						System.out.println("checked year " + (year >= 0));
						System.exit(1);
					}
				}
				""".formatted(SOURCE_MARKER));

			Result result = CheckProgram.run(launch, fixture.input(), fixture.scratchParent(),
				new BuiltInTool.CheckProgram(PROGRAM, List.of()), Map.of(), NORMAL_TIMEOUT, NORMAL_OUTPUT_LIMIT);

			requireThat(result.outcome(), "outcome").isEqualTo(Outcome.FAIL);
			requireThat(result.observed(), "observed").contains("checked year true").doesNotContain(SOURCE_MARKER).
				doesNotContain("deprecat");
			requireScratchClean(fixture.scratchParent());
		}
	}

	/**
	 * Counts the bytes from both streams against a single limit and retains bounded UTF-8 output.
	 *
	 * @throws IOException if a fixture or process cannot be used
	 * @throws InterruptedException if a process wait is interrupted
	 */
	@Test
	public void boundsCombinedOutputBytes() throws IOException, InterruptedException
	{
		// Requirement: R-L21
		Launch launch = nativeLaunch();
		try (TestJvmScope jvm = new TestJvmScope(); TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory(jvm.temporaryDirectory(), "check-output-"));
			Fixture fixture = fixture(root, """
				import java.nio.charset.StandardCharsets;
				final class Check
				{
					public static void main(String[] args)
					{
						System.out.writeBytes("é".repeat(24).getBytes(StandardCharsets.UTF_8));
						System.err.writeBytes("ø".repeat(24).getBytes(StandardCharsets.UTF_8));
					}
				}
				""");
			int outputLimit = 64;

			Result result = CheckProgram.run(launch, fixture.input(), fixture.scratchParent(),
				new BuiltInTool.CheckProgram(PROGRAM, List.of()), Map.of(), NORMAL_TIMEOUT, outputLimit);

			requireThat(result.outcome(), "outcome").isEqualTo(Outcome.ERROR);
			requireThat(result.observed().toLowerCase(Locale.ROOT), "outputCause").contains("output");
			long retained = result.observed().codePoints().filter(value -> value == 'é' || value == 'ø').count();
			requireThat(retained, "retainedCharacters").isPositive().isLessThanOrEqualTo(outputLimit / 2L);
			requireScratchClean(fixture.scratchParent());
		}
	}

	/**
	 * Stops a sleeping program at the shortened deadline and retains the output emitted before it slept.
	 *
	 * @throws IOException if a fixture or process cannot be used
	 * @throws InterruptedException if a process wait is interrupted
	 */
	@Test
	public void stopsAtTimeout() throws IOException, InterruptedException
	{
		// Requirement: R-L21
		// Requirement: R-L22
		Launch launch = nativeLaunch();
		try (TestJvmScope jvm = new TestJvmScope(); TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory(jvm.temporaryDirectory(), "check-timeout-"));
			Fixture fixture = fixture(root, """
				final class Check
				{
					public static void main(String[] args) throws InterruptedException
					{
						System.out.println("before-sleep");
						System.out.flush();
						Thread.sleep(10_000);
					}
				}
				""");
			Duration timeout = Duration.ofSeconds(2);

			Result result = CheckProgram.run(launch, fixture.input(), fixture.scratchParent(),
				new BuiltInTool.CheckProgram(PROGRAM, List.of()), Map.of(), timeout, NORMAL_OUTPUT_LIMIT);

			requireThat(result.outcome(), "outcome").isEqualTo(Outcome.ERROR);
			requireThat(result.observed().toLowerCase(Locale.ROOT), "timeoutOutput").contains("time").
				contains("before-sleep");
			requireThat(result.elapsed().compareTo(timeout), "elapsedComparedToTimeout").isGreaterThanOrEqualTo(0);
			requireScratchClean(fixture.scratchParent());
		}
	}

	/**
	 * Reads staged data and writes a scratch file with the fixed environment and literal command-line arguments.
	 *
	 * @throws IOException if a fixture or process cannot be used
	 * @throws InterruptedException if a process wait is interrupted
	 */
	@Test
	public void readsFixtureWithLiteralArguments() throws IOException, InterruptedException
	{
		// Requirement: R-L21
		Launch launch = nativeLaunch();
		try (TestJvmScope jvm = new TestJvmScope(); TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory(jvm.temporaryDirectory(), "check-fixture-"));
			Fixture fixture = fixture(root, """
				import java.io.IOException;
				import java.nio.file.Files;
				import java.nio.file.Path;
				import java.util.Set;
				final class Check
				{
					public static void main(String[] args) throws IOException
					{
						Set<String> expected = Set.of("CAT_CHECK_INPUT", "CAT_CHECK_SCRATCH", "HOME", "TMPDIR",
							"TMP", "TEMP", "TZ", "LC_ALL");
						if (!System.getenv().keySet().equals(expected))
							throw new IllegalStateException("Unexpected check-program environment variables");
						Path input = Path.of(System.getenv("CAT_CHECK_INPUT"));
						String message = Files.readString(input.resolve("project/message.txt"));
						Path copied = Files.writeString(Path.of("copied.txt"), message);
						Path scratch = Path.of(System.getenv("CAT_CHECK_SCRATCH"));
						if (!Files.isSameFile(copied, scratch.resolve("copied.txt")))
							throw new IllegalStateException("The working directory differs from scratch");
						System.out.println(Files.readString(copied));
						System.out.println(String.join("|", args));
					}
				}
				""");
			String message = "Fixture title";
			Files.writeString(fixture.input().resolve(INPUT_FILE), message);
			String label = "two words; [ * ]";
			String atArgument = "@literal-argument";

			Result result = CheckProgram.run(launch, fixture.input(), fixture.scratchParent(),
				new BuiltInTool.CheckProgram(PROGRAM, List.of("{{label}}", atArgument)), Map.of("label", label),
				NORMAL_TIMEOUT, NORMAL_OUTPUT_LIMIT);

			requireThat(result.outcome(), "outcome").isEqualTo(Outcome.PASS);
			requireThat(result.observed(), "observed").contains(message).contains(label + "|" + atArgument);
			requireScratchClean(fixture.scratchParent());
		}
	}

	/**
	 * Denies reads of protected fixture data, writes outside scratch, and connections to a host-only fixture socket.
	 *
	 * @throws IOException if a fixture or process cannot be used
	 * @throws InterruptedException if a process wait is interrupted
	 */
	@Test
	public void isolatesFilesystemAndNetwork() throws IOException, InterruptedException
	{
		// Requirement: R-L21
		Launch nativeLaunch = nativeLaunch();
		try (TestJvmScope jvm = new TestJvmScope(); TemporaryDirectories cleanup = new TemporaryDirectories();
			ServerSocket socket = new ServerSocket(0, 1, InetAddress.getByAddress(new byte[]{127, 0, 0, 1})))
		{
			Path root = cleanup.add(Files.createTempDirectory(jvm.temporaryDirectory(), "check-isolation-"));
			Fixture fixture = fixture(root, """
				import java.io.IOException;
				import java.net.InetSocketAddress;
				import java.net.Socket;
				import java.nio.file.Files;
				import java.nio.file.Path;

				final class Check
				{
					static void requireDenied(IoOperation operation) throws IOException
					{
						try
						{
							operation.run();
						}
						catch (IOException e)
						{
							return;
						}
						throw new IllegalStateException("Sandbox allowed a disallowed fixture operation");
					}

					interface IoOperation
					{
						void run() throws IOException;
					}

					public static void main(String[] args) throws IOException
					{
						Path input = Path.of(System.getenv("CAT_CHECK_INPUT"));
						requireDenied(() -> Files.writeString(input.resolve("new.txt"), "blocked"));
						requireDenied(() -> Files.writeString(Path.of(args[0]), "blocked"));
						requireDenied(() -> Files.readString(Path.of(args[1])));
						requireDenied(() -> Files.readString(Path.of(args[2])));
						try (Socket socket = new Socket())
						{
							requireDenied(() -> socket.connect(new InetSocketAddress("127.0.0.1",
								Integer.parseInt(args[3])), 500));
						}
						System.out.println("Fixture restrictions held");
					}
				}
				""");
			Path outside = root.resolve("outside.txt");
			Path denied = Files.createDirectory(root.resolve("owner-denied"));
			Path held = Files.createDirectory(root.resolve("held-out"));
			Path ownerFixture = Files.writeString(denied.resolve("data.txt"), "owner fixture");
			Path heldFixture = Files.writeString(held.resolve("data.txt"), "held-out fixture");
			ParentPolicy owner = new ParentPolicy(true, List.of(root), List.of(), List.of(denied, held),
				List.of(held), HostAccess.ALL, true, List.of());
			Launch launch = new Launch(nativeLaunch.bubblewrap(), nativeLaunch.javaExecutable(), owner, List.of(held));
			List<String> arguments = List.of(outside.toString(), ownerFixture.toString(), heldFixture.toString(),
				String.valueOf(socket.getLocalPort()));

			Result result = CheckProgram.run(launch, fixture.input(), fixture.scratchParent(),
				new BuiltInTool.CheckProgram(PROGRAM, arguments), Map.of(), NORMAL_TIMEOUT, NORMAL_OUTPUT_LIMIT);

			requireThat(result.outcome(), "outcome").isEqualTo(Outcome.PASS);
			requireThat(result.observed(), "restrictionReceipt").contains("Fixture restrictions held");
			requireThat(Files.exists(outside), "outsideWrite").isFalse();
			requireThat(Files.exists(fixture.input().resolve("new.txt")), "inputWrite").isFalse();
			requireScratchClean(fixture.scratchParent());
		}
	}

	/**
	 * A missing sandbox launcher causes an infrastructure exception and leaves no scratch directory.
	 *
	 * @throws IOException if a fixture cannot be prepared or inspected
	 */
	@Test
	public void missingLauncherIsInfrastructureError() throws IOException
	{
		// Requirement: R-L21
		requireLinux();
		try (TestJvmScope jvm = new TestJvmScope(); TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory(jvm.temporaryDirectory(), "check-launch-"));
			Fixture fixture = fixture(root, """
				final class Check
				{
					public static void main(String[] args)
					{
						System.out.println("unexpected launch");
					}
				}
				""");
			Path missing = root.resolve("missing-bwrap");
			Path java = javaExecutable();
			BuiltInTool.CheckProgram declaration = new BuiltInTool.CheckProgram(PROGRAM, List.of());

			expectThrows(IOException.class, () -> CheckProgram.run(
				new Launch(missing, java, ParentPolicy.UNSANDBOXED, List.of()), fixture.input(),
				fixture.scratchParent(), declaration, Map.of(), NORMAL_TIMEOUT, NORMAL_OUTPUT_LIMIT));

			requireScratchClean(fixture.scratchParent());
		}
	}

	/**
	 * Returns the locally installed Linux sandbox and source-file runtime.
	 *
	 * @return the launch settings for the generated test
	 * @throws SkipException if the required host programs are unavailable
	 * @throws IOException if an executable path cannot be resolved
	 */
	static Launch nativeLaunch() throws IOException
	{
		requireLinux();
		if (ModuleFinder.ofSystem().find("jdk.compiler").isEmpty())
			throw new SkipException("The check-program test needs a Java runtime with jdk.compiler");
		for (Path candidate : List.of(Path.of("/usr/bin/bwrap"), Path.of("/bin/bwrap")))
		{
			if (Files.isRegularFile(candidate) && Files.isExecutable(candidate))
				return new Launch(candidate.toRealPath(), javaExecutable(), ParentPolicy.UNSANDBOXED, List.of());
		}
		throw new SkipException("The check-program test needs a system Bubblewrap executable");
	}

	/**
	 * Requires Linux for the native Bubblewrap launch tests.
	 *
	 * @throws SkipException if the current platform is not Linux
	 */
	private static void requireLinux()
	{
		if (!Platform.current().isLinux())
			throw new SkipException("The check-program test exercises the Linux Bubblewrap launcher");
	}

	/**
	 * Locates Java in the runtime of the test itself.
	 *
	 * @return the real path of the Java executable
	 * @throws IOException if the executable path cannot be resolved
	 */
	private static Path javaExecutable() throws IOException
	{
		return Path.of(System.getProperty("java.home"), "bin", "java").toRealPath();
	}

	/**
	 * Creates a staged input and a scratch parent for one run of a generated check program.
	 *
	 * @param root a temporary directory owned by the test
	 * @param source the Java program source
	 * @return the staged input and scratch parent
	 * @throws IOException if the fixture cannot be written
	 */
	private static Fixture fixture(Path root, String source) throws IOException
	{
		Path input = Files.createDirectory(root.resolve("program input"));
		Files.createDirectories(input.resolve(PROGRAM).getParent());
		Files.writeString(input.resolve(PROGRAM), source);
		Files.createDirectory(input.resolve("project"));
		Files.createDirectory(input.resolve("initial"));
		Files.writeString(input.resolve("replies.json"), "[]");
		Files.writeString(input.resolve("activity.json"), """
			{"format_version": 1, "entries": [], "received": []}
			""");
		return new Fixture(input, Files.createDirectory(root.resolve("scratch parent")));
	}

	/**
	 * Verifies cleanup before the test's outer temporary directory is removed.
	 *
	 * @param scratchParent the directory in which a run creates its scratch
	 * @throws IOException if the scratch parent cannot be listed
	 */
	private static void requireScratchClean(Path scratchParent) throws IOException
	{
		try (Stream<Path> entries = Files.list(scratchParent))
		{
			requireThat(entries.count(), "scratchEntries").isEqualTo(0L);
		}
	}

	/**
	 * The paths of a generated check program and its scratch parent.
	 *
	 * @param input the staged input
	 * @param scratchParent holds temporary run directories
	 */
	private record Fixture(Path input, Path scratchParent)
	{
	}
}
