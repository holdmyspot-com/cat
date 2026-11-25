package com.holdmyspot.cat.common.optimizer.execution;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.nio.file.LinkOption.NOFOLLOW_LINKS;

import com.holdmyspot.cat.common.Platform;
import com.holdmyspot.cat.common.TemporaryDirectories;
import com.holdmyspot.cat.common.optimizer.contract.BuiltInTool;
import com.holdmyspot.cat.common.runner.policy.BubblewrapReadView;
import com.holdmyspot.cat.common.runner.policy.ParentPolicy;
import com.holdmyspot.cat.common.runner.policy.PolicyRefusal;
import com.holdmyspot.cat.common.runner.policy.ReadDenials;
import com.holdmyspot.cat.common.runner.policy.RealPaths;
import com.holdmyspot.cat.common.runner.policy.SeatbeltProfile;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.module.ModuleFinder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Runs one confirmed check program against staged evidence in the campaign owner's sandbox.
 */
public final class CheckProgram
{
	/** The check program's time limit. */
	public static final Duration TIME_LIMIT = Duration.ofSeconds(60);
	/** The combined standard output and standard error limit, in bytes. */
	public static final int OUTPUT_LIMIT = 16 * 1024;
	private static final Path ENV = Path.of("/usr/bin/env");
	private static final String SCRATCH = "scratch";
	private static final String LAUNCHER = "CatCheckSource.java";
	private static final String PROFILE = "check-program.sb";
	private static final String READY = "CAT check source ready";
	/*
	 * The launcher discards the source launcher's compiler output so that a warning of a program that runs stays out of
	 * its observed value and, when the program does not start, reports each error as the compiler does, with its
	 * location, message, source line and caret. The observed value of a program error is kept in the trial's record and
	 * the user's reports, and a judge is told only that the program could not run.
	 */
	private static final String SOURCE = """
		import com.sun.source.util.JavacTask;
		import com.sun.tools.javac.launcher.Fault;
		import com.sun.tools.javac.launcher.SourceLauncher;
		import java.io.PrintWriter;
		import java.io.StringWriter;
		import java.lang.reflect.InvocationTargetException;
		import java.nio.file.Path;
		import java.util.List;
		import java.util.Locale;
		import javax.tools.Diagnostic;
		import javax.tools.DiagnosticCollector;
		import javax.tools.JavaCompiler;
		import javax.tools.JavaFileObject;
		import javax.tools.StandardJavaFileManager;
		import javax.tools.ToolProvider;

		class CatCheckSource
		{
			public static void main(String[] arguments)
			{
				System.err.println("%s");
				try
				{
					new SourceLauncher(new PrintWriter(new StringWriter(), true)).run(new String[0], arguments);
				}
				catch (Fault e)
				{
					reportErrors(Path.of(arguments[0]));
					System.err.println(e.getMessage());
					System.exit(2);
				}
				catch (InvocationTargetException e)
				{
					e.getCause().printStackTrace(System.err);
					System.exit(2);
				}
			}

			private static void reportErrors(Path program)
			{
				try
				{
					JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
					DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
					try (StandardJavaFileManager files = compiler.getStandardFileManager(diagnostics, Locale.ROOT, null))
					{
						JavacTask task = (JavacTask) compiler.getTask(new StringWriter(), files, diagnostics,
							List.of("-proc:none", "-implicit:none", "-Xlint:none", "-sourcepath",
								program.getParent().toString()), null, files.getJavaFileObjects(program));
						task.analyze();
					}
					Path input = Path.of(System.getenv("CAT_CHECK_INPUT"));
					for (Diagnostic<? extends JavaFileObject> diagnostic : diagnostics.getDiagnostics())
					{
						if (diagnostic.getKind() != Diagnostic.Kind.ERROR)
							continue;
						System.err.println(diagnostic.toString().replace(input + "/", ""));
					}
				}
				catch (Exception | Error e)
				{
					System.err.println("The compiler errors could not be listed: " + e.getClass().getName());
				}
			}
		}
		""".formatted(READY);
	private static final byte[] READY_LINE = (READY + "\n").getBytes(UTF_8);
	private static final Duration CLEANUP_LIMIT = Duration.ofSeconds(5);

	/**
	 * The trusted launch executables and the owner's effective read restrictions.
	 *
	 * @param sandbox the absolute path to the protected sandbox executable: Bubblewrap on Linux and
	 *   {@code sandbox-exec} on macOS
	 * @param javaExecutable the absolute path to Java from CAT's runtime
	 * @param policy the authenticated owner's filesystem policy
	 * @param heldOut the absolute paths of the held-out content and results to mask independently of the policy
	 */
	public record Launch(Path sandbox, Path javaExecutable, ParentPolicy policy, List<Path> heldOut)
	{
		/**
		 * Records the launch conditions.
		 *
		 * @param sandbox the absolute path to the protected sandbox executable
		 * @param javaExecutable the absolute path to Java from CAT's runtime
		 * @param policy the authenticated owner's filesystem policy
		 * @param heldOut the absolute paths of the held-out content and results to mask
		 * @throws NullPointerException if any argument or held-out path is null
		 * @throws IllegalArgumentException if an executable or held-out path is not absolute
		 */
		public Launch
		{
			requireThat(sandbox.isAbsolute(), "isAbsolute(sandbox)").isTrue();
			requireThat(javaExecutable.isAbsolute(), "isAbsolute(javaExecutable)").isTrue();
			requireThat(policy, "policy").isNotNull();
			heldOut = List.copyOf(heldOut);
			requireThat(heldOut.stream().allMatch(Path::isAbsolute), "absoluteHeldOutPaths").isTrue();
		}
	}

	/** How the check program decided, or whether it could decide. */
	public enum Outcome
	{
		/** The program exited with status zero. */
		PASS,
		/** The program exited with status one. */
		FAIL,
		/** The program exceeded a limit, could not run from its source, or exited with another status. */
		ERROR
	}

	/**
	 * A check program's result.
	 *
	 * @param outcome the pass, fail or program error
	 * @param observed bounded program output and the cause of an error
	 * @param elapsed time from sandbox launch to process exit or enforced cancellation
	 */
	public record Result(Outcome outcome, String observed, Duration elapsed)
	{
	}

	/**
	 * Resolves the owner's sandbox, Bubblewrap on Linux and {@code sandbox-exec} on macOS, and the bundled Java against
	 * each authenticated policy reading.
	 *
	 * @param platform the execution platform
	 * @param environment the owner's program search environment
	 * @param policies the owner's authenticated policy readings
	 * @param heldOut the existing held-out paths to mask even when the owner may read them
	 * @return the trusted sandbox, runtime and effective read policy
	 * @throws IOException if the host, executable or policy inputs cannot be used
	 * @throws PolicyRefusal if any policy would let the owner session's commands change a launch executable
	 * @throws NullPointerException if an argument is null
	 */
	public static Launch ownerLaunch(Platform platform, Map<String, String> environment, List<ParentPolicy> policies,
		List<Path> heldOut) throws IOException, PolicyRefusal
	{
		requireThat(platform, "platform").isNotNull();
		requireThat(environment, "environment").isNotNull();
		requireThat(policies, "policies").isNotNull();
		requireThat(heldOut, "heldOut").isNotNull();
		if (platform.isWindows())
			throw new IOException("Check programs require WSL2 on Windows.");
		if (!platform.isLinux() && !platform.isMacos())
			throw new IOException("CAT has no check program sandbox on " + platform.id() + ".");
		if (policies.isEmpty())
			throw new IOException("No authenticated owner policy was available for the check program.");

		Path located;
		if (platform.isLinux())
		{
			located = Path.of(platform.findProgram("bwrap", environment));
			if (!located.isAbsolute())
				throw new IOException("Check programs require Bubblewrap (bwrap) at an absolute location on PATH.");
		}
		else
		{
			located = SeatbeltProfile.SANDBOX_EXECUTABLE;
			if (!Files.isExecutable(located))
			{
				throw new IOException("Check programs require " + located + " on macOS, which is missing or not " +
					"executable.");
			}
		}
		Path sandbox = located.toRealPath();
		Path runtime = Path.of(System.getProperty("java.home")).toRealPath();
		Path java = runtime.resolve("bin").resolve("java").toRealPath();
		if (ModuleFinder.ofSystem().find("jdk.compiler").isEmpty())
			throw new IOException("The CAT runtime has no Java source compiler for check programs.");

		ParentPolicy effective = ParentPolicy.UNSANDBOXED;
		for (ParentPolicy policy : policies)
		{
			policy.requireTrusted(located.getParent());
			policy.requireTrusted(sandbox);
			policy.requireTrustedTree(runtime);
			policy.requireTrusted(java);
			policy.requireTrusted(ENV);
			effective = effective.intersect(policy);
		}
		return new Launch(sandbox, java, effective, heldOut);
	}

	/**
	 * Identifies the input format and the Java feature version of the runtime that runs check programs.
	 *
	 * @return the measurement version
	 */
	public static String version()
	{
		return "input-" + CheckProgramInput.FORMAT_VERSION + "-java-" + Runtime.version().feature();
	}

	/**
	 * Runs a program from a staged, private input directory, using an empty scratch directory.
	 *
	 * @param launch the trusted executables and authenticated policy
	 * @param input the staged input directory
	 * @param scratchParent a private directory in which the owner may create scratch directories
	 * @param declaration the assertion's check program
	 * @param textByName the test case's placeholder values
	 * @param timeout the positive maximum process time
	 * @param outputLimit the positive combined stream limit in bytes
	 * @return the program's decision or error
	 * @throws NullPointerException if any argument is null
	 * @throws IllegalArgumentException if a limit is not positive
	 * @throws IOException if staging or the host sandbox prevents a program from running
	 * @throws InterruptedException if the owner interrupts the run
	 */
	public static Result run(Launch launch, Path input, Path scratchParent, BuiltInTool.CheckProgram declaration,
		Map<String, String> textByName, Duration timeout, int outputLimit) throws IOException, InterruptedException
	{
		requireThat(declaration, "declaration").isNotNull();
		requireThat(textByName, "textByName").isNotNull();
		return run(launch, input, scratchParent, declaration.program(), declaration.filledArguments(textByName), timeout,
			outputLimit);
	}

	/**
	 * Runs a program from a staged, private input directory, using an empty scratch directory.
	 *
	 * @param launch the trusted executables and authenticated policy
	 * @param input the staged input directory
	 * @param scratchParent a private directory in which the owner may create scratch directories
	 * @param programPath the path of the program below the input directory
	 * @param arguments the program's arguments, with their placeholders filled
	 * @param timeout the positive maximum process time
	 * @param outputLimit the positive combined stream limit in bytes
	 * @return the program's decision or error
	 * @throws NullPointerException if any argument is null
	 * @throws IllegalArgumentException if a limit is not positive
	 * @throws IOException if staging or the host sandbox prevents a program from running
	 * @throws InterruptedException if the owner interrupts the run
	 */
	public static Result run(Launch launch, Path input, Path scratchParent, String programPath, List<String> arguments,
		Duration timeout, int outputLimit) throws IOException, InterruptedException
	{
		requireThat(launch, "launch").isNotNull();
		requireThat(programPath, "programPath").isNotNull();
		requireThat(arguments, "arguments").isNotNull();
		requireThat(timeout.isPositive(), "positiveTimeout").isTrue();
		requireThat(outputLimit, "outputLimit").isPositive();
		if (Platform.current().isWindows())
			throw new IOException("Check programs require WSL2 on Windows");

		Path evidence = input.toRealPath();
		Path program = evidence.resolve(programPath);
		if (!Files.isRegularFile(program, NOFOLLOW_LINKS) || !program.toRealPath().startsWith(evidence))
			throw new IOException("Expected a staged check program: " + program);
		try (TemporaryDirectories temporary = new TemporaryDirectories())
		{
			Path work = temporary.add(Files.createTempDirectory(scratchParent.toRealPath(), "check-program-"));
			if (work.startsWith(evidence) || evidence.startsWith(work))
				throw new IOException("Check program input and scratch must be separate");
			Path scratch = Files.createDirectory(work.resolve(SCRATCH));
			Path launcher = Files.writeString(work.resolve(LAUNCHER), SOURCE);
			List<String> command = command(launch, evidence, work, scratch);
			command.addAll(List.of(launch.javaExecutable().toString(), "--disable-@files", "-XX:-UsePerfData",
				"-Duser.home=" + scratch, "-Djava.io.tmpdir=" + scratch, "--add-exports",
				"jdk.compiler/com.sun.tools.javac.launcher=ALL-UNNAMED", launcher.toString(), program.toString()));
			command.addAll(arguments);
			ProcessBuilder builder = new ProcessBuilder(command).directory(scratch.toFile());
			builder.environment().clear();
			long start = System.nanoTime();
			try (Process process = builder.start())
			{
				return execute(process, start, timeout, outputLimit);
			}
		}
	}

	/**
	 * Builds a command that presents the input read-only and only the scratch directory writable.
	 *
	 * @param launch the authenticated launch conditions
	 * @param input the staged input
	 * @param work the host-owned work directory for the launcher and masks
	 * @param scratch the program's empty writable directory
	 * @return the sandbox command prefix ending with the fixed environment assignments
	 * @throws IOException if the filesystem view cannot be prepared
	 */
	private static List<String> command(Launch launch, Path input, Path work, Path scratch) throws IOException
	{
		ParentPolicy policy = launch.policy();
		for (Path executable : List.of(launch.sandbox(), launch.javaExecutable(), ENV))
		{
			if (!Files.isExecutable(executable) || !policy.mayRead(executable))
				throw new IOException("Check program sandbox cannot use executable: " + executable);
		}
		if (!policy.mayRead(input) || !policy.mayRead(work))
			throw new IOException("The owner cannot read the staged check program input or scratch");
		List<Path> masks = RealPaths.resolveAll(launch.heldOut());
		for (Path mask : masks)
		{
			if (input.startsWith(mask) || mask.startsWith(input) || work.startsWith(mask) || mask.startsWith(work))
				throw new IOException("Check program input or scratch intersects a held-out directory: " + mask);
		}
		if (Platform.current().isMacos())
			return seatbeltCommand(launch, input, work, scratch, masks);
		return bubblewrapCommand(launch, input, work, scratch, masks);
	}

	/**
	 * Builds the Linux command, which runs the program in a read-only view with the network unshared.
	 *
	 * @param launch the authenticated launch conditions
	 * @param input the staged input
	 * @param work the host-owned work directory for the launcher and masks
	 * @param scratch the program's empty writable directory
	 * @param masks the held-out paths to mask
	 * @return the sandbox command prefix ending with the fixed environment assignments
	 * @throws IOException if the filesystem view cannot be prepared
	 */
	private static List<String> bubblewrapCommand(Launch launch, Path input, Path work, Path scratch, List<Path> masks)
		throws IOException
	{
		List<String> command = BubblewrapReadView.readOnlyView(launch.sandbox());
		String scratchPath = scratch.toString();
		command.addAll(List.of("--remount-ro", "/proc", "--remount-ro", "/dev", "--ro-bind", input.toString(),
			input.toString(), "--bind", scratchPath, scratchPath));
		BubblewrapReadView.applyReadDenials(command, launch.policy(), work, masks, "CAT", "check program launch");
		// Bubblewrap supplies PWD even when its input environment is empty.
		command.addAll(List.of("--chdir", scratchPath, "--"));
		command.addAll(environment(input, scratchPath));
		return command;
	}

	/**
	 * Builds the macOS command, which applies a profile that CAT writes to the work directory and denies the network,
	 * every write outside the scratch directory and the same reads that the Linux view masks.
	 *
	 * @param launch the authenticated launch conditions
	 * @param input the staged input
	 * @param work the host-owned work directory for the launcher and the profile
	 * @param scratch the program's empty writable directory
	 * @param masks the held-out paths to deny
	 * @return the sandbox command prefix ending with the fixed environment assignments
	 * @throws IOException if the profile cannot be written or the read policy cannot be enforced
	 */
	private static List<String> seatbeltCommand(Launch launch, Path input, Path work, Path scratch, List<Path> masks)
		throws IOException
	{
		List<Path> denied = new ArrayList<>();
		for (ReadDenials.Denial denial : ReadDenials.of(launch.policy(), masks, "CAT", "check program launch"))
		{
			if (denial.mandatory() && !Files.exists(denial.path()))
				throw new IOException(denial.failure());
			denied.add(denial.path());
		}
		Path profile = Files.writeString(work.resolve(PROFILE), SeatbeltProfile.confining(scratch, denied));
		List<String> command = new ArrayList<>(List.of(launch.sandbox().toString(), "-f", profile.toString()));
		command.addAll(environment(input, scratch.toString()));
		return command;
	}

	/**
	 * Returns the command that starts the program with the fixed environment.
	 *
	 * @param input the staged input
	 * @param scratchPath the program's writable directory
	 * @return {@code env} with the assignments, which the caller follows with the program's own command
	 */
	private static List<String> environment(Path input, String scratchPath)
	{
		return List.of(ENV.toString(), "-i", "CAT_CHECK_INPUT=" + input, "CAT_CHECK_SCRATCH=" + scratchPath,
			"HOME=" + scratchPath, "TMPDIR=" + scratchPath, "TMP=" + scratchPath, "TEMP=" + scratchPath, "TZ=UTC",
			"LC_ALL=C.UTF-8");
	}

	/**
	 * Bounds the running sandbox's process and output and interprets its result.
	 *
	 * @param process the confined process
	 * @param start the monotonic reading from before launch
	 * @param timeout the maximum process time
	 * @param outputLimit the combined captured byte limit
	 * @return the program's result
	 * @throws IOException if the sandbox, its launcher or its output transport fails
	 * @throws InterruptedException if the owner interrupts the run
	 */
	private static Result execute(Process process, long start, Duration timeout, int outputLimit)
		throws IOException, InterruptedException
	{
		AtomicLong total = new AtomicLong();
		FutureTask<Capture> stdout = new FutureTask<>(() -> capture(process.getInputStream(), false, total, outputLimit,
			process));
		FutureTask<Capture> stderr = new FutureTask<>(() -> capture(process.getErrorStream(), true, total, outputLimit,
			process));
		Thread.startVirtualThread(stdout);
		Thread.startVirtualThread(stderr);
		try
		{
			process.getOutputStream().close();
			Duration remaining = timeout.minusNanos(System.nanoTime() - start);
			boolean timedOut = !remaining.isPositive() || !process.waitFor(remaining);
			if (timedOut)
				process.destroyForcibly();
			if (!process.waitFor(CLEANUP_LIMIT))
				throw new IOException("Check program sandbox did not stop");
			Duration elapsed = Duration.ofNanos(System.nanoTime() - start);
			Capture out = stdout.get(CLEANUP_LIMIT.toNanos(), TimeUnit.NANOSECONDS);
			Capture err = stderr.get(CLEANUP_LIMIT.toNanos(), TimeUnit.NANOSECONDS);
			String output = out.text(outputLimit) + err.text(Math.max(0, outputLimit - out.bytes().length));
			if (!err.ready())
				throw new IOException("Check program sandbox did not start its source launcher: " + output);
			if (total.get() > outputLimit)
				return new Result(Outcome.ERROR, withCause(output, "Check program exceeded its output limit"), elapsed);
			if (timedOut)
				return new Result(Outcome.ERROR, withCause(output, "Check program exceeded its time limit"), elapsed);
			for (Capture captured : List.of(out, err))
			{
				if (captured.failure() != null)
					throw captured.failure();
			}
			int status = process.exitValue();
			String exit = "Check program exited with status " + status;
			String observed = output;
			if (observed.isBlank())
				observed = exit;
			return switch (status)
			{
				case 0 -> new Result(Outcome.PASS, observed, elapsed);
				case 1 -> new Result(Outcome.FAIL, observed, elapsed);
				default -> new Result(Outcome.ERROR, withCause(output, exit), elapsed);
			};
		}
		catch (ExecutionException | TimeoutException e)
		{
			throw new IOException("Cannot collect check program output", e);
		}
		finally
		{
			if (process.isAlive())
				process.destroyForcibly();
		}
	}

	/**
	 * Captures a bounded prefix of one stream and kills the sandbox if both streams exceed the shared limit.
	 *
	 * @param stream the process's stream
	 * @param expectReady whether the trusted bootstrap writes a readiness line to this stream
	 * @param total the number of bytes captured across both streams, including a chunk that exceeded the limit
	 * @param limit the combined byte limit
	 * @param process the sandboxed process to stop when the limit is exceeded
	 * @return the captured bytes and the stream's completion state
	 */
	private static Capture capture(InputStream stream, boolean expectReady, AtomicLong total, int limit, Process process)
	{
		ByteArrayOutputStream captured = new ByteArrayOutputStream();
		boolean ready = false;
		try (stream)
		{
			if (expectReady)
			{
				byte[] prefix = stream.readNBytes(READY_LINE.length);
				ready = Arrays.equals(prefix, READY_LINE);
				if (!ready)
				{
					captured.write(prefix, 0, Math.min(prefix.length, limit));
					total.addAndGet(prefix.length);
				}
			}
			byte[] chunk = new byte[4096];
			for (int length = stream.read(chunk); length != -1; length = stream.read(chunk))
			{
				captured.write(chunk, 0, Math.min(length, limit - captured.size()));
				if (total.addAndGet(length) > limit)
				{
					process.destroyForcibly();
					break;
				}
			}
			return new Capture(captured.toByteArray(), ready, null);
		}
		catch (IOException e)
		{
			return new Capture(captured.toByteArray(), ready, e);
		}
	}

	/**
	 * Formats observed output with the trusted reason for an error.
	 *
	 * @param output bounded program output
	 * @param cause the reason for the program error
	 * @return the reported output and cause
	 */
	private static String withCause(String output, String cause)
	{
		if (output.isEmpty())
			return cause;
		return output + "\n" + cause;
	}

	/**
	 * A stream prefix collected by a single reader.
	 *
	 * @param bytes the first captured bytes
	 * @param ready whether the source launcher proved that it started
	 * @param failure a transport failure, or null on EOF
	 */
	private record Capture(byte[] bytes, boolean ready, IOException failure)
	{
		/**
		 * Decodes the portion of the prefix that fits the combined output limit.
		 *
		 * @param capacity bytes remaining in the combined output
		 * @return the decoded prefix
		 */
		private String text(int capacity)
		{
			return new String(bytes, 0, Math.min(bytes.length, capacity), UTF_8);
		}
	}

	/** Prevents construction. */
	private CheckProgram()
	{
	}
}
