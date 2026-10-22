package com.holdmyspot.cat.distribution.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static java.nio.charset.StandardCharsets.UTF_16LE;
import static java.nio.charset.StandardCharsets.UTF_8;

import com.holdmyspot.cat.common.Platform;
import com.holdmyspot.cat.common.TemporaryDirectories;
import com.holdmyspot.cat.distribution.bundle.JlinkImageBuilder;
import com.holdmyspot.cat.distribution.bundle.secrets.JlinkImageBuilderSecrets;
import com.holdmyspot.cat.distribution.secrets.DistributionMainSecrets;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Set;
import java.util.spi.ToolProvider;
import org.testng.annotations.Test;

/**
 * Tests that the launchers of the Codex image run from the shell that Codex runs commands in, by the absolute path
 * without an extension that CAT's skills name, such as {@code <plugin>/bin/plugin-info}, including the launchers that
 * the plugin moves from the image's {@code bin} folder to its own.
 */
public final class CodexImageLaunchersTest
{
	/** Creates a test instance. */
	public CodexImageLaunchersTest()
	{
	}

	/**
	 * Verifies that each launcher of the Codex image starts its program when Codex's shell, PowerShell on Windows and a
	 * POSIX shell elsewhere, runs the launcher's absolute path without an extension.
	 *
	 * @throws IOException          if the image cannot be built or its launchers cannot be run
	 * @throws InterruptedException if the thread is interrupted while the launchers run
	 */
	@Test
	public void launchersRunByTheirExtensionlessPaths() throws IOException, InterruptedException
	{
		Platform platform = Platform.current();
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path directory = cleanup.add(Files.createTempDirectory(Path.of("target"), "codex-image-")).toAbsolutePath();
			JlinkImageBuilder builder = JlinkImageBuilderSecrets.getJlinkImageBuilderAccess().create(new FakeJlink(platform));
			Path image = DistributionMainSecrets.getDistributionMainAccess().
				codexImage(builder, List.of(Path.of("codex.jar")),
				directory.resolve("client"), platform);
			Set<String> agentLaunchers = DistributionMainSecrets.getDistributionMainAccess().codexAgentLaunchers();
			Path agentBin = directory.resolve("bin");
			JlinkImageBuilder.moveLaunchers(agentLaunchers, image, agentBin);

			List<JlinkImageBuilder.Launcher> launchers = DistributionMainSecrets.getDistributionMainAccess().codexLaunchers();
			List<String> paths = new ArrayList<>();
			for (JlinkImageBuilder.Launcher launcher : launchers)
			{
				Path bin;
				if (agentLaunchers.contains(launcher.name()))
					bin = agentBin;
				else
					bin = image.resolve("bin");
				paths.add(bin.resolve(launcher.name()).toString());
			}
			String output;
			if (platform.isWindows())
				output = runInPowerShell(paths);
			else
				output = runInPosixShell(paths, directory);

			List<String> lines = output.strip().lines().toList();
			requireThat(lines, "lines").withContext(output, "output").size().isEqualTo(launchers.size());
			for (int i = 0; i < launchers.size(); ++i)
			{
				JlinkImageBuilder.Launcher launcher = launchers.get(i);
				requireThat(lines.get(i), launcher.name()).startsWith("started ").
					contains("-m " + launcher.module() + "/" + launcher.mainClass());
			}
		}
	}

	/**
	 * Runs each launcher from PowerShell, as Codex runs a command on Windows. A launcher that PowerShell does not resolve
	 * to a batch launcher, {@code .bat} or {@code .cmd}, is reported instead of run, because PowerShell hands a file that
	 * it cannot run to the program that Windows associates with the file, which can open a window.
	 *
	 * @param launchers the launchers' paths
	 * @return PowerShell's output
	 * @throws IOException          if PowerShell cannot be run or fails
	 * @throws InterruptedException if the thread is interrupted while PowerShell runs
	 */
	private static String runInPowerShell(List<String> launchers) throws IOException, InterruptedException
	{
		StringBuilder script = new StringBuilder(512 * launchers.size()).append("$ErrorActionPreference = 'Stop'\n");
		for (String launcher : launchers)
		{
			String literal = "'" + launcher.replace("'", "''") + "'";
			script.append("if ((Get-Command -Name ").append(literal).append(").Extension -in '.bat', '.cmd') { & ").
				append(literal).append(" } else { 'resolved ' + (Get-Command -Name ").append(literal).
				append(").Source }\n");
		}
		String encoded = Base64.getEncoder().encodeToString(script.toString().getBytes(UTF_16LE));
		return run(new ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive", "-EncodedCommand", encoded));
	}

	/**
	 * Runs each launcher from a POSIX shell, as Codex runs a command on Linux and macOS. A stand-in Bubblewrap fails as
	 * it does inside a sandbox that cannot create another namespace, so a launcher that ran Java through it would
	 * fail.
	 *
	 * @param launchers the launchers' paths
	 * @param directory a directory that the test owns
	 * @return the shell's output
	 * @throws IOException          if the shell cannot be run or fails
	 * @throws InterruptedException if the thread is interrupted while the shell runs
	 */
	private static String runInPosixShell(List<String> launchers, Path directory)
		throws IOException, InterruptedException
	{
		Path stubs = Files.createDirectories(directory.resolve("stubs"));
		writeExecutable(stubs.resolve("bwrap"), """
			#!/bin/sh
			echo 'bwrap: No permissions to create new namespace' >&2
			exit 1
			""");
		List<String> command = new ArrayList<>(List.of("sh", "-c", "for launcher in \"$@\"; do \"$launcher\"; done",
			"sh"));
		command.addAll(launchers);
		ProcessBuilder builder = new ProcessBuilder(command);
		builder.environment().put("PATH", stubs + ":" + System.getenv("PATH"));
		return run(builder);
	}

	/**
	 * Runs a process to completion.
	 *
	 * @param builder the process to run
	 * @return the process's standard output and standard error
	 * @throws IOException          if the process cannot be run or fails
	 * @throws InterruptedException if the thread is interrupted while the process runs
	 */
	private static String run(ProcessBuilder builder) throws IOException, InterruptedException
	{
		try (Process process = builder.redirectErrorStream(true).start())
		{
			process.getOutputStream().close();
			String output = new String(process.getInputStream().readAllBytes(), UTF_8);
			int status = process.waitFor();
			requireThat(status, "status").withContext(output, "output").isEqualTo(0);
			return output;
		}
	}

	/**
	 * Writes a POSIX script that every user may run.
	 *
	 * @param path    the script's path
	 * @param content the script
	 * @throws IOException if the script cannot be written
	 */
	private static void writeExecutable(Path path, String content) throws IOException
	{
		Files.writeString(path, content);
		requireThat(path.toFile().setExecutable(true, false), "executable").isTrue();
	}

	/**
	 * Writes the launchers that jlink writes on a platform, in jlink's form, beside a {@code java} that prints the
	 * arguments that a launcher passes it.
	 */
	private static final class FakeJlink implements ToolProvider
	{
		private final Platform platform;

		/**
		 * Creates a fake jlink.
		 *
		 * @param platform the platform whose launchers to write
		 */
		private FakeJlink(Platform platform)
		{
			this.platform = platform;
		}

		@Override
		public String name()
		{
			return "jlink";
		}

		@Override
		public int run(PrintWriter out, PrintWriter err, String... args)
		{
			List<String> arguments = List.of(args);
			Path bin = Path.of(arguments.get(arguments.indexOf("--output") + 1)).resolve("bin");
			try
			{
				Files.createDirectories(bin);
				if (platform.isWindows())
					Files.writeString(bin.resolve("java.cmd"), "@echo started %*\r\n");
				else
					writeExecutable(bin.resolve("java"), "#!/bin/sh\necho started \"$@\"\n");
				for (int i = 0; i < arguments.size(); ++i)
				{
					if (!arguments.get(i).equals("--launcher"))
						continue;
					++i;
					String specification = arguments.get(i);
					String name = specification.substring(0, specification.indexOf('='));
					String target = specification.substring(specification.indexOf('=') + 1);
					writeExecutable(bin.resolve(name),
						"#!/bin/sh\nJLINK_VM_OPTIONS=\nDIR=`dirname $0`\n$DIR/java -m " + target + " \"$@\"\n");
					if (platform.isWindows())
					{
						Files.writeString(bin.resolve(name + ".bat"), "@echo off\r\nset JLINK_VM_OPTIONS=\r\n" +
							"set DIR=%~dp0\r\n\"%DIR%\\java\" %JLINK_VM_OPTIONS% -m " + target + " %*\r\n");
					}
				}
			}
			catch (IOException e)
			{
				e.printStackTrace(err);
				return 1;
			}
			return 0;
		}
	}
}
