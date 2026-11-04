package com.holdmyspot.cat.common.updater.engine.test;

import com.holdmyspot.cat.common.FileReplacer;
import com.holdmyspot.cat.common.Platform;
import com.holdmyspot.cat.common.updater.activation.CommandResult;
import com.holdmyspot.cat.common.updater.activation.HarnessCli;
import com.holdmyspot.cat.common.updater.activation.HarnessCommand;
import com.holdmyspot.cat.common.updater.activation.HarnessIntegration;
import com.holdmyspot.cat.common.updater.engine.Registration;
import java.io.IOException;
import java.nio.file.Path;
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
	 * The entries that each {@code removePermissions} call received.
	 */
	private final List<Map<String, List<String>>> removed = new ArrayList<>();
	/**
	 * The program that {@code discover} reports.
	 */
	private HarnessCommand command;
	/**
	 * The environment that the last {@code discover} call received.
	 */
	private Map<String, String> discoveredEnvironment = Map.of();

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
		return this;
	}

	/**
	 * Sets the program that discovery reports.
	 *
	 * @param command the program
	 * @return this integration
	 */
	public RecordingIntegration discovering(HarnessCommand command)
	{
		this.command = command;
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
	 * Returns the environment that the last discovery received.
	 *
	 * @return the environment
	 */
	public Map<String, String> discoveredEnvironment()
	{
		return discoveredEnvironment;
	}

	/**
	 * Returns a registration that uses this integration, a harness program that always succeeds and a replacer that
	 * never waits.
	 *
	 * @return the registration
	 */
	public Registration registration()
	{
		return new Registration(this, cli(), new FileReplacer(Platform.current(), () ->
		{
		}, Clock.systemUTC()));
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
	public HarnessCommand discover(Path home, Map<String, String> environment) throws IOException
	{
		record("discover", home);
		discoveredEnvironment = Map.copyOf(environment);
		if (command == null)
			throw new IOException("No harness-a program was found");
		return command;
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
	 * @param home the home it received
	 * @throws IOException if the test made the call fail
	 */
	private void record(String call, Path home) throws IOException
	{
		calls.add(call + " " + home);
		if (crashing.contains(call))
			throw new IllegalStateException("harness-a stopped during " + call);
		if (failing.contains(call))
			throw new IOException("harness-a refused " + call);
	}
}
