package com.holdmyspot.cat.common.optimizer.execution.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

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
import com.holdmyspot.cat.common.runner.policy.SeatbeltProfile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.testng.SkipException;
import org.testng.annotations.Test;

/**
 * Runs real check programs under the real Seatbelt profile, started outside any sandbox as the runner and the request
 * server start them. The tests need macOS, where {@code sandbox-exec} exists, and are skipped on other operating
 * systems for that reason alone; the profile's text is tested on every operating system by
 * {@code SeatbeltProfileTest}.
 */
public final class CheckProgramSeatbeltTest
{
	private static final String PROGRAM = "checks/Check.java";
	private static final Duration TIMEOUT = Duration.ofSeconds(30);
	private static final int OUTPUT_LIMIT = 16 * 1024;
	private static final String MESSAGE = "message from the input directory";
	private static final String SECRET = "secret held outside the program's reach";

	/**
	 * Creates the test.
	 */
	public CheckProgramSeatbeltTest()
	{
	}

	/**
	 * Verifies that the launch selects the system's {@code sandbox-exec} and CAT's own runtime.
	 *
	 * @throws IOException if the launch cannot be resolved
	 * @throws PolicyRefusal if the policy refuses a launch executable
	 */
	@Test
	public void launchUsesSandboxExec() throws IOException, PolicyRefusal
	{
		// Requirement: R-L21
		requireMacos();

		Launch launch = CheckProgram.ownerLaunch(Platform.current(), Map.of(), List.of(ParentPolicy.UNSANDBOXED),
			List.of());

		requireThat(launch.sandbox(), "sandbox").isEqualTo(SeatbeltProfile.SANDBOX_EXECUTABLE.toRealPath());
		requireThat(launch.javaExecutable(), "java").
			isEqualTo(Path.of(System.getProperty("java.home"), "bin", "java").toRealPath());
	}

	/**
	 * Verifies that a program reads its input directory and prints what it reads.
	 *
	 * @throws IOException if a fixture or process cannot be used
	 * @throws InterruptedException if a process wait is interrupted
	 * @throws PolicyRefusal if the policy refuses a launch executable
	 */
	@Test
	public void programPrintsItsInput() throws IOException, InterruptedException, PolicyRefusal
	{
		// Requirement: R-L21
		requireMacos();
		try (TestJvmScope jvm = new TestJvmScope(); TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory(jvm.temporaryDirectory(), "check-seatbelt-"));
			Fixture fixture = fixture(root, """
				import java.nio.file.Files;
				import java.nio.file.Path;

				final class Check
				{
					public static void main(String[] args) throws Exception
					{
						Path input = Path.of(System.getenv("CAT_CHECK_INPUT"));
						System.out.println(Files.readString(input.resolve("project/message.txt")));
					}
				}
				""");

			Result result = run(fixture, ParentPolicy.UNSANDBOXED, List.of(), List.of());

			requireThat(result.outcome(), "outcome").isEqualTo(Outcome.PASS);
			requireThat(result.observed(), "observed").contains(MESSAGE);
			requireScratchClean(fixture);
		}
	}

	/**
	 * Verifies that a program cannot connect to a public address, which this machine can reach outside the sandbox.
	 *
	 * @throws IOException if a fixture or process cannot be used
	 * @throws InterruptedException if a process wait is interrupted
	 * @throws PolicyRefusal if the policy refuses a launch executable
	 */
	@Test
	public void programCannotConnectToPublicAddress() throws IOException, InterruptedException, PolicyRefusal
	{
		// Requirement: R-L21
		requireMacos();
		try (TestJvmScope jvm = new TestJvmScope(); TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory(jvm.temporaryDirectory(), "check-seatbelt-"));
			Fixture fixture = fixture(root, """
				import java.io.IOException;
				import java.net.InetSocketAddress;
				import java.net.Socket;

				final class Check
				{
					public static void main(String[] args)
					{
						try (Socket socket = new Socket())
						{
							socket.connect(new InetSocketAddress("1.1.1.1", 443), 10_000);
							System.out.println("Connected to a public address");
							System.exit(1);
						}
						catch (IOException e)
						{
							System.out.println("Network denied: " + e.getMessage());
						}
					}
				}
				""");

			Result result = run(fixture, ParentPolicy.UNSANDBOXED, List.of(), List.of());

			requireThat(result.outcome(), "outcome").isEqualTo(Outcome.PASS);
			requireThat(result.observed(), "observed").contains("Network denied: ").
				contains("Operation not permitted");
		}
	}

	/**
	 * Verifies that a program cannot write outside its scratch directory, neither a folder of its own owner nor its
	 * input directory.
	 *
	 * @throws IOException if a fixture or process cannot be used
	 * @throws InterruptedException if a process wait is interrupted
	 * @throws PolicyRefusal if the policy refuses a launch executable
	 */
	@Test
	public void programCannotWriteOutsideScratch() throws IOException, InterruptedException, PolicyRefusal
	{
		// Requirement: R-L21
		requireMacos();
		try (TestJvmScope jvm = new TestJvmScope(); TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory(jvm.temporaryDirectory(), "check-seatbelt-"));
			Path outside = Files.createDirectory(root.resolve("outside"));
			Fixture fixture = fixture(root, """
				import java.io.IOException;
				import java.nio.file.Files;
				import java.nio.file.Path;

				final class Check
				{
					public static void main(String[] args) throws IOException
					{
						Path input = Path.of(System.getenv("CAT_CHECK_INPUT"));
						for (Path target : new Path[]{Path.of(args[0]).resolve("written.txt"),
							input.resolve("project/written.txt"), input.resolve("project/message.txt")})
						{
							try
							{
								Files.writeString(target, "written by the program");
								System.out.println("Wrote " + target);
								System.exit(1);
							}
							catch (IOException e)
							{
								System.out.println("Write denied: " + e.getClass().getSimpleName());
							}
						}
					}
				}
				""");

			Result result = run(fixture, ParentPolicy.UNSANDBOXED, List.of(), List.of(outside.toString()));

			requireThat(result.outcome(), "outcome").isEqualTo(Outcome.PASS);
			requireThat(result.observed(), "observed").doesNotContain("Wrote ");
			requireThat(Files.exists(outside.resolve("written.txt")), "outsideFileExists").isFalse();
			requireThat(Files.exists(fixture.input().resolve("project/written.txt")), "inputFileExists").isFalse();
			requireThat(Files.readString(fixture.input().resolve("project/message.txt")), "message").
				isEqualTo(MESSAGE);
		}
	}

	/**
	 * Verifies that a program cannot read a directory that its owner is denied, and that the held-out directory is
	 * denied to it even though its owner may read it.
	 *
	 * @throws IOException if a fixture or process cannot be used
	 * @throws InterruptedException if a process wait is interrupted
	 * @throws PolicyRefusal if the policy refuses a launch executable
	 */
	@Test
	public void programCannotReadDeniedDirectories() throws IOException, InterruptedException, PolicyRefusal
	{
		// Requirement: R-L21
		requireMacos();
		try (TestJvmScope jvm = new TestJvmScope(); TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory(jvm.temporaryDirectory(), "check-seatbelt-")).
				toRealPath();
			Path denied = Files.createDirectory(root.resolve("owner denied"));
			Files.writeString(denied.resolve("secret.txt"), SECRET);
			Path heldOut = Files.createDirectory(root.resolve("held-out"));
			Files.writeString(heldOut.resolve("secret.txt"), SECRET);
			Path allowed = Files.createDirectory(root.resolve("allowed"));
			Files.writeString(allowed.resolve("secret.txt"), SECRET);
			Fixture fixture = fixture(root, """
				import java.io.IOException;
				import java.nio.file.DirectoryStream;
				import java.nio.file.Files;
				import java.nio.file.Path;

				final class Check
				{
					public static void main(String[] args) throws IOException
					{
						for (int index = 0; index < 2; ++index)
						{
							Path folder = Path.of(args[index]);
							try
							{
								System.out.println(Files.readString(folder.resolve("secret.txt")));
								System.exit(1);
							}
							catch (IOException e)
							{
								System.out.println("Read denied: " + e.getClass().getSimpleName());
							}
							try (DirectoryStream<Path> entries = Files.newDirectoryStream(folder))
							{
								for (Path entry : entries)
								{
									System.out.println("Listed " + entry.getFileName());
									System.exit(1);
								}
							}
							catch (IOException e)
							{
								System.out.println("List denied: " + e.getClass().getSimpleName());
							}
						}
						System.out.println(Files.readString(Path.of(args[2]).resolve("secret.txt")));
					}
				}
				""");
			ParentPolicy owner = new ParentPolicy(true, List.of(root), List.of(), List.of(denied), List.of(),
				HostAccess.ALL, true, List.of());

			Result result = run(fixture, owner, List.of(heldOut),
				List.of(denied.toString(), heldOut.toString(), allowed.toString()));

			requireThat(result.outcome(), "outcome").isEqualTo(Outcome.PASS);
			requireThat(result.observed(), "observed").doesNotContain("Listed ").contains("Read denied: ").
				contains("List denied: ");
			// The one directory that nothing denies is read, which shows that the denials are the cause above
			requireThat(result.observed().split(SECRET, -1).length - 1, "secretsPrinted").isEqualTo(1);
			requireScratchClean(fixture);
		}
	}

	/**
	 * Verifies that a program writes and reads back a file of its scratch directory, through its working directory and
	 * through the scratch variable, and that nothing of it remains afterwards.
	 *
	 * @throws IOException if a fixture or process cannot be used
	 * @throws InterruptedException if a process wait is interrupted
	 * @throws PolicyRefusal if the policy refuses a launch executable
	 */
	@Test
	public void programWritesScratch() throws IOException, InterruptedException, PolicyRefusal
	{
		// Requirement: R-L21
		requireMacos();
		try (TestJvmScope jvm = new TestJvmScope(); TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory(jvm.temporaryDirectory(), "check-seatbelt-"));
			Fixture fixture = fixture(root, """
				import java.nio.file.Files;
				import java.nio.file.Path;

				final class Check
				{
					public static void main(String[] args) throws Exception
					{
						Path scratch = Path.of(System.getenv("CAT_CHECK_SCRATCH"));
						Files.writeString(scratch.resolve("through-variable.txt"), "variable");
						Files.writeString(Path.of("through-directory.txt"), "directory");
						System.out.println(Files.readString(Path.of("through-variable.txt")) + " " +
							Files.readString(scratch.resolve("through-directory.txt")));
					}
				}
				""");

			Result result = run(fixture, ParentPolicy.UNSANDBOXED, List.of(), List.of());

			requireThat(result.outcome(), "outcome").isEqualTo(Outcome.PASS);
			requireThat(result.observed(), "observed").contains("variable directory");
			requireScratchClean(fixture);
		}
	}

	/**
	 * Runs the fixture's program under the owner's policy.
	 *
	 * @param fixture the staged program
	 * @param owner the owner's policy
	 * @param heldOut the held-out paths to deny the program
	 * @param arguments the program's arguments
	 * @return the program's result
	 * @throws IOException if a fixture or process cannot be used
	 * @throws InterruptedException if a process wait is interrupted
	 * @throws PolicyRefusal if the policy refuses a launch executable
	 */
	private static Result run(Fixture fixture, ParentPolicy owner, List<Path> heldOut, List<String> arguments)
		throws IOException, InterruptedException, PolicyRefusal
	{
		Launch launch = CheckProgram.ownerLaunch(Platform.current(), Map.of(), List.of(owner), heldOut);
		return CheckProgram.run(launch, fixture.input(), fixture.scratchParent(),
			new BuiltInTool.CheckProgram(PROGRAM, arguments), Map.of(), TIMEOUT, OUTPUT_LIMIT);
	}

	/**
	 * Requires macOS, whose {@code sandbox-exec} these tests exercise.
	 *
	 * @throws SkipException if the current platform is not macOS
	 */
	private static void requireMacos()
	{
		if (!Platform.current().isMacos())
			throw new SkipException("The check-program Seatbelt tests need macOS");
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
		Files.writeString(input.resolve("project/message.txt"), MESSAGE);
		Files.createDirectory(input.resolve("initial"));
		Files.writeString(input.resolve("replies.json"), "[]");
		Files.writeString(input.resolve("activity.json"), """
			{"format_version": 1, "entries": [], "received": []}
			""");
		return new Fixture(input, Files.createDirectory(root.resolve("scratch parent")));
	}

	/**
	 * Verifies that a run leaves no scratch directory behind.
	 *
	 * @param fixture the staged program
	 * @throws IOException if the scratch parent cannot be listed
	 */
	private static void requireScratchClean(Fixture fixture) throws IOException
	{
		try (Stream<Path> entries = Files.list(fixture.scratchParent()))
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
