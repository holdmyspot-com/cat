package com.holdmyspot.cat.distribution.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static java.nio.charset.StandardCharsets.UTF_16LE;
import static java.nio.charset.StandardCharsets.UTF_8;

import com.holdmyspot.cat.common.Platform;
import com.holdmyspot.cat.common.TemporaryDirectories;
import com.holdmyspot.cat.distribution.bundle.Launcher;
import com.holdmyspot.cat.distribution.secrets.DistributionMainAccess;
import com.holdmyspot.cat.distribution.secrets.DistributionMainSecrets;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import org.testng.annotations.Test;

/**
 * Tests that the launchers of the Codex plugin run from the shell that Codex runs commands in, by the absolute path
 * without an extension that CAT's skills name, such as {@code <plugin>/bin/plugin-info}.
 */
public final class CodexImageLaunchersTest
{
	/** Creates a test instance. */
	public CodexImageLaunchersTest()
	{
	}

	/**
	 * Verifies that each launcher of the Codex plugin starts its program on the bound runtime when Codex's shell,
	 * PowerShell on Windows and a POSIX shell elsewhere, runs the launcher's absolute path without an extension. On
	 * POSIX, the bound runtime's {@code java} prints its arguments, which hold the option that allows Basic proxy
	 * logins for HTTPS tunnels with its empty value intact; on Windows, the binding names the test's own JDK, which
	 * reports that the plugin's empty module path lacks the program's module.
	 *
	 * @throws IOException          if the plugin cannot be assembled or its launchers cannot be run
	 * @throws InterruptedException if the thread is interrupted while the launchers run
	 */
	@Test
	public void launchersRunByTheirExtensionlessPaths() throws IOException, InterruptedException
	{
		// Requirement: R-I9
		Platform platform = Platform.current();
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path directory = cleanup.add(Files.createTempDirectory("codex-image-")).toAbsolutePath();
			Path bin = Files.createDirectories(directory.resolve("bin/runtime/modules")).getParent().getParent();
			DistributionMainAccess access = DistributionMainSecrets.getDistributionMainAccess();
			access.writeCodexLaunchers(bin, platform);
			Path runtime;
			if (platform.isWindows())
				runtime = Path.of(System.getProperty("java.home")).toAbsolutePath().normalize();
			else
			{
				runtime = Files.createDirectories(directory.resolve("runtime/bin")).getParent();
				writeExecutable(runtime.resolve("bin/java"), "#!/bin/sh\necho started \"$@\"\n");
			}
			Files.writeString(bin.resolve("runtime-binding"), runtime + "\n");

			List<Launcher> launchers = access.codexLaunchers();
			List<String> paths = new ArrayList<>();
			for (Launcher launcher : launchers)
				paths.add(bin.resolve(launcher.name()).toString());
			if (platform.isWindows())
			{
				String output = runInPowerShell(paths);
				for (Launcher launcher : launchers)
				{
					requireThat(output, "output").contains("Module " + launcher.module() + " not found").
						doesNotContain("resolved ");
				}
				return;
			}
			String output = runInPosixShell(paths, directory);
			List<String> lines = output.strip().lines().toList();
			requireThat(lines, "lines").withContext(output, "output").size().isEqualTo(launchers.size());
			for (int i = 0; i < launchers.size(); ++i)
			{
				Launcher launcher = launchers.get(i);
				requireThat(lines.get(i), launcher.name()).startsWith("started ").
					contains(" -Djdk.http.auth.tunneling.disabledSchemes= ").
					contains("--module-path " + bin.resolve("runtime/modules").toRealPath()).
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
	 * @return PowerShell's output, whatever its exit status, because each program fails without its module
	 * @throws IOException          if PowerShell cannot be run
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
		ProcessBuilder builder = new ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive", "-EncodedCommand",
			encoded).redirectErrorStream(true);
		try (Process process = builder.start())
		{
			process.getOutputStream().close();
			String output = new String(process.getInputStream().readAllBytes(), UTF_8);
			process.waitFor();
			return output;
		}
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
}
