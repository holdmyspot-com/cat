package com.holdmyspot.cat.common.updater.engine.test;

import com.holdmyspot.cat.common.FileReplacer;
import com.holdmyspot.cat.common.Platform;
import com.holdmyspot.cat.common.updater.activation.CommandResult;
import com.holdmyspot.cat.common.updater.activation.HarnessCli;
import com.holdmyspot.cat.common.updater.activation.HarnessIntegration;
import com.holdmyspot.cat.common.updater.engine.HarnessSession;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A harness integration for the generic harness {@code harness-a} that records each call and fails the calls that a
 * test names. It never runs a program.
 */
public final class RecordingIntegration implements HarnessIntegration
{
	/**
	 * Each call's name followed by its home, in call order.
	 */
	private final List<String> calls = new ArrayList<>();
	/**
	 * The calls that throw an {@code IOException}, as a harness program that fails would.
	 */
	private final Set<String> failing = new HashSet<>();
	/**
	 * The calls that throw an unchecked exception, standing in for a process that stops mid-operation.
	 */
	private final Set<String> crashing = new HashSet<>();
	/**
	 * The entries that the next {@code installPermissions} call reports as added.
	 */
	private Map<String, List<String>> added = Map.of();
	/**
	 * The entries that {@code permissions} reports as maintained: every entry that a permission installation reported
	 * as added, and the entries that a test names.
	 */
	private final Map<String, List<String>> maintained = new LinkedHashMap<>();
	/**
	 * The entries that each {@code removePermissions} call received.
	 */
	private final List<Map<String, List<String>>> removed = new ArrayList<>();
	/**
	 * Whether {@code legacyPermissions} reports an entry for the shared stable launcher of earlier builds.
	 */
	private boolean retiring;

	/**
	 * Creates an integration whose calls all succeed.
	 */
	public RecordingIntegration()
	{
	}

	/**
	 * Makes a call fail with an {@code IOException} until {@link #succeed(String)} is called.
	 *
	 * @param call the call's name, such as {@code register}
	 * @return this integration
	 */
	public RecordingIntegration fail(String call)
	{
		failing.add(call);
		return this;
	}

	/**
	 * Makes a call stop with an unchecked exception, as if the process ended during it.
	 *
	 * @param call the call's name
	 * @return this integration
	 */
	public RecordingIntegration crash(String call)
	{
		crashing.add(call);
		return this;
	}

	/**
	 * Makes a call succeed again.
	 *
	 * @param call the call's name
	 * @return this integration
	 */
	public RecordingIntegration succeed(String call)
	{
		failing.remove(call);
		crashing.remove(call);
		return this;
	}

	/**
	 * Sets the entries that the next permission installation reports as added.
	 *
	 * @param entries each configuration's added entries
	 * @return this integration
	 */
	public RecordingIntegration adding(Map<String, List<String>> entries)
	{
		this.added = new LinkedHashMap<>(entries);
		maintain(entries);
		return this;
	}

	/**
	 * Adds entries to those that the integration reports as maintained, without reporting them as added.
	 *
	 * @param entries each configuration's maintained entries
	 * @return this integration
	 */
	public RecordingIntegration maintain(Map<String, List<String>> entries)
	{
		for (Map.Entry<String, List<String>> entry : entries.entrySet())
		{
			List<String> values = new ArrayList<>(maintained.getOrDefault(entry.getKey(), List.of()));
			for (String value : entry.getValue())
			{
				if (!values.contains(value))
					values.add(value);
			}
			maintained.put(entry.getKey(), List.copyOf(values));
		}
		return this;
	}

	/**
	 * Sets whether the integration reports permission entries that earlier builds asked the user to add for their
	 * shared stable launcher: one entry naming that launcher.
	 *
	 * @param retiring true to report the entry, false to report none
	 * @return this integration
	 */
	public RecordingIntegration retiring(boolean retiring)
	{
		this.retiring = retiring;
		return this;
	}

	/**
	 * Returns the calls made so far.
	 *
	 * @return each call's name, in call order
	 */
	public List<String> calls()
	{
		return calls.stream().map(call -> call.substring(0, call.indexOf(' '))).toList();
	}

	/**
	 * Returns the entries that each permission removal received.
	 *
	 * @return the removed entries, in call order
	 */
	public List<Map<String, List<String>>> removed()
	{
		return List.copyOf(removed);
	}

	/**
	 * Returns a harness session that uses this integration, a harness program that always succeeds and a replacer that
	 * never waits.
	 *
	 * @return the session
	 */
	public HarnessSession session()
	{
		return new HarnessSession(this, cli(), new FileReplacer(Platform.current(), () ->
		{
		}, Clock.systemUTC()));
	}

	/**
	 * Creates the {@code harness-a} program file that setup finds on the search path. Setup never starts it.
	 *
	 * @param directory the directory to create the program in
	 * @return the program
	 * @throws IOException if the program cannot be written
	 */
	public static Path program(Path directory) throws IOException
	{
		Path program = Files.writeString(Files.createDirectories(directory).resolve("harness-a"), "#!/bin/sh\n");
		if (Files.getFileStore(program).supportsFileAttributeView(PosixFileAttributeView.class))
			Files.setPosixFilePermissions(program, PosixFilePermissions.fromString("rwx------"));
		return program;
	}

	/**
	 * Returns a harness program that always succeeds without running anything.
	 *
	 * @return the program
	 */
	public static HarnessCli cli()
	{
		return arguments -> new CommandResult(0, "");
	}

	@Override
	public String harness()
	{
		return "harness-a";
	}

	@Override
	public String programName()
	{
		return "harness-a";
	}

	/**
	 * Returns {@code HARNESS_A_HOME}, naming the harness home.
	 */
	@Override
	public Map<String, String> programEnvironment(Path home, Map<String, String> environment)
	{
		return Map.of("HARNESS_A_HOME", home.toString());
	}

	/**
	 * Records the call as {@code requireIdentity} and fails it when the test asked for that.
	 */
	@Override
	public void requireIdentity(CommandResult version) throws IOException
	{
		record("requireIdentity", "exit status " + version.exitCode());
	}

	@Override
	public void validateConfiguration(Path home) throws IOException
	{
		record("validateConfiguration", home);
	}

	@Override
	public void register(Path home, HarnessCli cli, FileReplacer replacer) throws IOException
	{
		record("register", home);
	}

	@Override
	public void unregister(Path home, HarnessCli cli) throws IOException
	{
		record("unregister", home);
	}

	@Override
	public Map<String, List<String>> installPermissions(Path home, Path stableLauncher, FileReplacer replacer)
		throws IOException
	{
		record("installPermissions", home);
		Map<String, List<String>> entries = added;
		added = Map.of();
		return entries;
	}

	@Override
	public Map<String, List<String>> permissions(Path stableLauncher)
	{
		return Map.copyOf(maintained);
	}

	@Override
	public Map<String, List<String>> legacyPermissions(Path legacyLauncher)
	{
		if (!retiring)
			return Map.of();
		return Map.of("settings.json", List.of(legacyLauncher.toString()));
	}

	@Override
	public void removePermissions(Path home, Map<String, List<String>> entries, FileReplacer replacer)
		throws IOException
	{
		record("removePermissions", home);
		removed.add(Map.copyOf(entries));
	}

	/**
	 * Records a call and fails it when the test asked for that.
	 *
	 * @param call the call's name
	 * @param subject what it received, such as the harness home
	 * @throws IOException if the test made the call fail
	 */
	private void record(String call, Object subject) throws IOException
	{
		calls.add(call + " " + subject);
		if (crashing.contains(call))
			throw new IllegalStateException("harness-a stopped during " + call);
		if (failing.contains(call))
			throw new IOException("harness-a refused " + call);
	}
}
