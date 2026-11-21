package com.holdmyspot.cat.common.runner;

import com.holdmyspot.cat.common.JvmScope;
import com.holdmyspot.cat.common.BoundedInput;
import com.holdmyspot.cat.common.Platform;
import com.holdmyspot.cat.common.TemporaryDirectories;
import com.holdmyspot.cat.common.runner.policy.ParentPolicy;
import com.holdmyspot.cat.common.runner.policy.PolicyRefusal;
import com.holdmyspot.cat.common.runner.policy.RealPaths;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

/**
 * Starts the runner launcher of a resolved artifact, which runs the artifact that holds it, and redirects its
 * diagnostics into the broker's private response directory.
 *
 * @param scope    the borrowed process scope, which the caller closes
 * @param artifact the root of the selected artifact
 * @param launcher the runner launcher in the artifact's {@code bin} directory
 * @param prefix   command arguments preceding runner options
 */
public record RequestedRunner(JvmScope scope, Path artifact, Path launcher, List<String> prefix)
{
	private static final String OUTPUT_FILE = "runner.stdout";
	/**
	 * Creates a runner target.
	 *
	 * @param scope    the borrowed process scope, which the caller closes
	 * @param artifact the root of the selected artifact
	 * @param launcher the runner launcher in the artifact's {@code bin} directory
	 * @param prefix   command arguments preceding runner options
	 */
	public RequestedRunner
	{
		requireThat(scope, "scope").isNotNull();
		requireThat(artifact, "artifact").isNotNull();
		requireThat(launcher, "launcher").isNotNull();
		prefix = List.copyOf(prefix);
	}

	/**
	 * A runner selected by the host, with its launch environment fixed.
	 *
	 * @param artifact the selected artifact
	 * @param launcher the runner launcher
	 * @param start starts the selected runner
	 */
	public record Selection(Path artifact, Path launcher, Start start)
	{
		/**
		 * Creates a selected runner.
		 *
		 * @param artifact the absolute artifact path
		 * @param launcher the absolute launcher path
		 * @param start starts the selected runner
		 * @throws NullPointerException if any argument is null
		 * @throws IllegalArgumentException if a path is relative
		 */
		public Selection
		{
			requireThat(artifact, "artifact").isNotNull().isAbsolute();
			requireThat(launcher, "launcher").isNotNull().isAbsolute();
			requireThat(start, "start").isNotNull();
		}

		/**
		 * Checks the installation and the runtime its launcher will execute against the owner's sandbox.
		 *
		 * @param readings the individual owner policies, before intersection
		 * @throws IOException if the installation or its runtime cannot be validated
		 * @throws PolicyRefusal if an owner policy may write the installation or its runtime
		 */
		public void requireTrusted(List<ParentPolicy> readings) throws IOException, PolicyRefusal
		{
			List<ParentPolicy> policies = readings.stream().filter(ParentPolicy::sandboxed).distinct().toList();
			if (policies.isEmpty())
				return;

			requireTrustedTree(artifact, policies);
			Path bin = artifact.resolve(PluginDirectory.BIN);
			requireTrustedTree(bin, policies);
			Path realBin = bin.toRealPath();
			Path realLauncher = launcher.toRealPath();
			if (!realLauncher.getParent().equals(realBin))
				throw new IOException("The selected runner " + launcher + " does not run from " + bin);
			Platform platform = Platform.current();
			Path runtime = RuntimeBinding.readUsable(artifact, platform);
			requireTrustedTree(runtime, policies);
			String java = "java";
			if (platform.isWindows())
				java += ".exe";
			requireTrustedTree(runtime.resolve(PluginDirectory.BIN).resolve(java), policies);

			requireTrustedLinks(realBin, policies);
			Path realRuntime = runtime.toRealPath();
			if (!realRuntime.startsWith(realBin))
				requireTrustedLinks(realRuntime, policies);
		}

		/**
		 * Starts the fixed comparison operation after the broker has authenticated the selected installation and
		 * supplied the owner policy. The launch is sent through the private child pipe.
		 *
		 * @param launch the broker-authorized comparison
		 * @param directory the broker-owned response directory
		 * @return the native observations
		 * @throws IOException if startup, the native comparison or result validation fails
		 * @throws InterruptedException if the owner broker interrupts the comparison
		 */
		public HookComparisonResult compareHooks(HookComparisonLaunch launch, Path directory)
			throws IOException, InterruptedException
		{
			JsonMapper json = JsonMapper.builder().build();
			try (TemporaryDirectories cleanup = new TemporaryDirectories())
			{
				Path output = cleanup.add(Files.createTempDirectory(directory, "hook-runner-"));
				try (Process process = start.run(List.of(HookComparisonLaunch.OPTION), output))
				{
					try
					{
						try (OutputStream stream = process.getOutputStream())
						{
							launch.write(json, stream);
						}
						if (process.waitFor() != 0)
						{
							String reason = BoundedInput.readUtf8(output.resolve(RequestedSessionStarter.ERROR_FILE),
								65_536, "Codex comparison error").strip();
							throw new IOException("Codex could not compare the hooks. " + reason);
						}
						try
						{
							return HookComparisonResult.parse(json.readTree(BoundedInput.readUtf8(output.resolve(OUTPUT_FILE),
								HookComparisonResult.MAXIMUM_BYTES, "Codex comparison result")));
						}
						catch (JacksonException e)
						{
							throw new IOException("The Codex comparison runner returned invalid JSON", e);
						}
					}
					finally
					{
						if (process.isAlive())
							HarnessProcessTermination.stopRunner(process);
					}
				}
			}
		}

		/**
		 * Checks a path as reached, and the directories that control any symbolic links in its name.
		 *
		 * @param path the path used by the launcher
		 * @param policies the owner's sandbox policies
		 * @throws IOException if a path cannot be resolved
		 * @throws PolicyRefusal if any of the policies lets the owner write the path
		 */
		private static void requireTrustedTree(Path path, List<ParentPolicy> policies)
			throws IOException, PolicyRefusal
		{
			for (ParentPolicy policy : policies)
				policy.requireTrustedTree(path);
			for (Path part = path; part.getParent() != null; part = part.getParent())
			{
				if (Files.isSymbolicLink(part))
					for (ParentPolicy policy : policies)
						policy.requireTrusted(part.getParent());
			}
		}

		/**
		 * Checks targets of links below a real installation directory. Packaged Java or application files can be
		 * reached through a link even when the containing installation is read-only.
		 *
		 * @param root the canonical directory
		 * @param policies the owner's sandbox policies
		 * @throws IOException if a path cannot be resolved
		 * @throws PolicyRefusal if any of the policies lets the owner write a link target
		 */
		private static void requireTrustedLinks(Path root, List<ParentPolicy> policies) throws IOException, PolicyRefusal
		{
			try (Stream<Path> files = Files.walk(root))
			{
				for (Path link : files.filter(Files::isSymbolicLink).toList())
					if (!RealPaths.resolve(link).startsWith(root))
						requireTrustedTree(link, policies);
			}
			catch (UncheckedIOException e)
			{
				throw e.getCause();
			}
		}

		/**
		 * Checks a native comparison source and any links it uses against all owner policy readings.
		 *
		 * @param source the native source selected by the broker
		 * @param readings the individual authenticated owner policies
		 * @throws IOException if a path or link cannot be checked
		 * @throws PolicyRefusal if the owner could modify the source
		 */
		public static void requireTrustedSource(Path source, List<ParentPolicy> readings)
			throws IOException, PolicyRefusal
		{
			requireTrustedTree(source, readings);
			if (Files.isDirectory(source))
				requireTrustedLinks(source.toRealPath(), readings);
		}
	}

	/**
	 * Starts an already selected runner.
	 */
	@FunctionalInterface
	public interface Start
	{
		/**
		 * Starts the runner.
		 *
		 * @param arguments the runner options
		 * @param directory the response directory
		 * @return the runner's process
		 * @throws IOException if the process cannot start
		 */
		Process run(List<String> arguments, Path directory) throws IOException;
	}

	/**
	 * Selects the installed runner for a harness.
	 */
	@FunctionalInterface
	public interface Resolver extends RequestedSessionStarter
	{
		/**
		 * Selects the runner without starting it.
		 *
		 * @param harness the requested harness
		 * @return the selected runner
		 * @throws IOException if the runner cannot be resolved
		 */
		Selection select(String harness) throws IOException;

		@Override
		default Process start(String harness, List<String> arguments, Path directory) throws IOException
		{
			return select(harness).start().run(arguments, directory);
		}
	}

	/**
	 * Binds this runner's host environment so the broker can reuse the same selected runner.
	 *
	 * @param environment the host environment
	 * @return the selected runner
	 */
	public Selection select(Map<String, String> environment)
	{
		Map<String, String> copy = Map.copyOf(environment);
		return new Selection(artifact, launcher, (arguments, directory) -> start(arguments, directory, copy));
	}

	/**
	 * Reads a target from an artifact resolver's scalar result.
	 *
	 * @param scope the borrowed process scope
	 * @param result the resolver's {@code artifact=} and {@code runner=} lines
	 * @param prefix command arguments preceding runner options
	 * @return the target
	 * @throws IOException if either required location is missing
	 */
	public static RequestedRunner resolved(JvmScope scope, String result, List<String> prefix) throws IOException
	{
		List<Path> paths = locations(result);
		return new RequestedRunner(scope, paths.get(0), paths.get(1), prefix);
	}

	/**
	 * Reads the locations in an artifact resolver's scalar result.
	 *
	 * @param result the resolver's {@code artifact=} and {@code runner=} lines
	 * @return the artifact, then its runner launcher
	 * @throws NullPointerException if {@code result} is null
	 * @throws IOException          if either required location is missing
	 */
	static List<Path> locations(String result) throws IOException
	{
		requireThat(result, "result").isNotNull();
		Path artifact = null;
		Path runner = null;
		for (String line : result.lines().toList())
		{
			if (line.startsWith("artifact="))
				artifact = Path.of(line.substring("artifact=".length()));
			if (line.startsWith("runner="))
				runner = Path.of(line.substring("runner=".length()));
		}
		if (artifact == null || runner == null)
			throw new IOException("Artifact resolver returned no artifact or runner location");
		return List.of(artifact, runner);
	}

	/**
	 * Starts the child runner with a host environment and file-backed diagnostics.
	 *
	 * @param arguments runner options selected by the broker
	 * @param directory the response directory
	 * @param environment the host environment, never the session command environment
	 * @return the child runner
	 * @throws IOException if the runner cannot start
	 */
	public Process start(List<String> arguments, Path directory, Map<String, String> environment) throws IOException
	{
		requireThat(arguments, "arguments").isNotNull();
		requireThat(directory, "directory").isDirectory();
		requireThat(environment, "environment").isNotNull();
		List<String> command = new ArrayList<>();
		command.add(launcher.toString());
		command.addAll(prefix);
		command.addAll(arguments);
		ProcessBuilder builder = new ProcessBuilder(command).directory(directory.toFile()).
			redirectOutput(directory.resolve(OUTPUT_FILE).toFile()).
			redirectError(directory.resolve(RequestedSessionStarter.ERROR_FILE).toFile());
		builder.environment().clear();
		builder.environment().putAll(environment);
		return scope.executableFiles().run(builder);
	}
}
