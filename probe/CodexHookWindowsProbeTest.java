package com.holdmyspot.cat.codex.optimizer.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.codex.layout.CodexSystemFolder;
import com.holdmyspot.cat.codex.optimizer.CodexHookProcess;
import com.holdmyspot.cat.common.Platform;
import com.holdmyspot.cat.common.runner.policy.HostAccess;
import com.holdmyspot.cat.common.runner.policy.ParentPolicy;
import com.holdmyspot.cat.common.runner.policy.PolicyRefusal;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import org.testng.SkipException;
import org.testng.annotations.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * Throwaway probe: runs a hook through the real Codex 0.172.0 Windows sandbox with the command that CAT builds.
 */
public final class CodexHookWindowsProbeTest
{
	private static final String HOOK = """
		@echo off
		echo HOME=[%HOME%]
		if defined CODEX_HOME (echo CODEX_HOME=[%CODEX_HOME%]) else (echo CODEX_HOME=unset)
		echo PATHHEAD=[%PATH:~0,40%]
		echo ARG1=[%~1]
		echo ARG2=[%~2]
		echo inside>"%CD%\\inside.txt"
		echo inside-exit=%ERRORLEVEL%
		echo outside>"%PROBE_OUTSIDE%\\outside.txt"
		echo outside-exit=%ERRORLEVEL%
		echo home>"%PROBE_TRIAL_HOME%\\home.txt"
		echo home-exit=%ERRORLEVEL%
		type "%PROBE_DENIED%\\secret.txt" >nul 2>&1
		echo denied-read-exit=%ERRORLEVEL%
		curl.exe -s -o NUL -m 15 https://example.com
		echo net-env-exit=%ERRORLEVEL%
		curl.exe -s -o NUL -m 15 --noproxy "*" https://example.com
		echo net-direct-exit=%ERRORLEVEL%
		powershell -NoProfile -Command "try { (New-Object Net.Sockets.TcpClient).Connect('1.1.1.1', 443); 'tcp=connected' } catch { 'tcp=refused' }"
		nslookup example.com >nul 2>&1
		echo dns-exit=%ERRORLEVEL%
		exit /b 0
		""";

	/**
	 * Creates the probe.
	 */
	public CodexHookWindowsProbeTest()
	{
	}

	/**
	 * Runs the hook under each Windows implementation and records what it could do.
	 *
	 * @throws IOException          if a file cannot be written
	 * @throws PolicyRefusal        if the policy refuses the executable
	 * @throws InterruptedException if waiting is interrupted
	 */
	@Test
	public void probe() throws IOException, PolicyRefusal, InterruptedException
	{
		String codex = System.getenv("CAT_HOOK_PROBE_CODEX");
		if (!Platform.current().isWindows() || codex == null || codex.isBlank())
			throw new SkipException("Probe runs only on the Windows probe branch");
		Path evidence = Files.createDirectories(Path.of(System.getenv("CAT_HOOK_PROBE_EVIDENCE")));
		List<String> failures = new ArrayList<>();
		for (String implementation : List.of("elevated", "unelevated"))
		{
			for (boolean trial : List.of(false, true))
			{
				String name = implementation + (trial ? "-trial" : "-production");
				failures.addAll(runCase(name, implementation, trial, Path.of(codex), evidence));
			}
		}
		Files.writeString(evidence.resolve("failures.txt"), String.join(System.lineSeparator(), failures));
		requireThat(failures, "failures").isEmpty();
	}

	private static List<String> runCase(String name, String implementation, boolean trial, Path codex,
		Path evidence) throws IOException, PolicyRefusal, InterruptedException
	{
		Path root = Files.createDirectories(Path.of("C:\\hookprobe", name)).toRealPath();
		Path scratch = Files.createDirectories(root.resolve("comparison"));
		Path workspace = Files.createDirectories(scratch.resolve(trial ? "trial" : "production"));
		Path home = Files.createDirectories(scratch.resolve("home"));
		Path outside = Files.createDirectories(root.resolve("outside"));
		Path denied = Files.createDirectories(root.resolve("denied"));
		Files.writeString(denied.resolve("secret.txt"), "secret");
		Files.writeString(workspace.resolve("probe.cmd"), HOOK.replace("\n", "\r\n"));
		ParentPolicy policy = new ParentPolicy(true, List.of(scratch), List.of(), List.of(denied), List.of(),
			HostAccess.NONE, false, List.of());
		ProcessBuilder builder = new ProcessBuilder(List.of("cmd.exe", "/d", "/c", "probe.cmd", "plain",
			"two words")).directory(workspace.toFile());
		Map<String, String> environment = builder.environment();
		environment.put("HOME", "C:\\hook\\home");
		environment.remove("CODEX_HOME");
		environment.put("PROBE_OUTSIDE", outside.toString());
		environment.put("PROBE_DENIED", denied.toString());
		environment.put("PROBE_TRIAL_HOME", home.toString());
		CodexHookProcess.Sandbox sandbox = new CodexHookProcess.Sandbox(Platform.current(), codex,
			Map.of("windows.sandbox", implementation), CodexSystemFolder.ofThisMachine());
		Optional<Path> trialHome = Optional.empty();
		if (trial)
			trialHome = Optional.of(home);
		CodexHookProcess.prepare(builder, scratch, trialHome, sandbox, policy, JsonMapper.builder().build());
		Path out = evidence.resolve(name + ".out.txt");
		Path err = evidence.resolve(name + ".err.txt");
		Files.writeString(evidence.resolve(name + ".command.txt"), String.join(System.lineSeparator(),
			builder.command()));
		builder.redirectOutput(out.toFile()).redirectError(err.toFile());
		long started = System.nanoTime();
		Process process = builder.start();
		process.getOutputStream().close();
		boolean ended = process.waitFor(300, TimeUnit.SECONDS);
		if (!ended)
			process.destroyForcibly().waitFor();
		long millis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
		String output = Files.readString(out, StandardCharsets.UTF_8);
		List<String> failures = new ArrayList<>();
		String summary = name + ": ended=" + ended + " exit=" + (ended ? process.exitValue() : -1) + " ms=" +
			millis + " inside=" + Files.exists(workspace.resolve("inside.txt")) + " outside=" +
			Files.exists(outside.resolve("outside.txt")) + " home=" + Files.exists(home.resolve("home.txt"));
		Files.writeString(evidence.resolve("summary.txt"), summary + System.lineSeparator(),
			java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
		System.out.println(summary);
		System.out.println(output);
		System.out.println(Files.readString(err, StandardCharsets.UTF_8));
		check(failures, name, ended && process.exitValue() == 0, "the hook ended with status 0");
		check(failures, name, output.contains("HOME=[C:\\hook\\home]"), "HOME is the hook's");
		check(failures, name, output.contains("CODEX_HOME=unset"), "CODEX_HOME is removed");
		check(failures, name, output.contains("ARG1=[plain]") && output.contains("ARG2=[two words]"),
			"the arguments arrive");
		check(failures, name, Files.exists(workspace.resolve("inside.txt")), "the workspace is writable");
		check(failures, name, !Files.exists(outside.resolve("outside.txt")), "outside the comparison is not");
		check(failures, name, Files.exists(home.resolve("home.txt")) == trial,
			"the trial home is writable only in the trial run");
		check(failures, name, !output.contains("net-env-exit=0"), "the environment's proxy keeps it off the network");
		return failures;
	}

	private static void check(List<String> failures, String name, boolean holds, String expectation)
	{
		if (!holds)
			failures.add(name + ": expected " + expectation);
	}
}
