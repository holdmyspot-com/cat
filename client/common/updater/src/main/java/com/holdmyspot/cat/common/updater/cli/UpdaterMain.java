package com.holdmyspot.cat.common.updater.cli;

import com.holdmyspot.cat.common.CommandLineArguments;
import com.holdmyspot.cat.common.FileReplacer;
import com.holdmyspot.cat.common.Pause;
import com.holdmyspot.cat.common.Platform;
import com.holdmyspot.cat.common.updater.activation.PluginActivation;
import com.holdmyspot.cat.common.updater.cli.internal.Invocation;
import java.nio.file.Path;
import java.time.Clock;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.ServiceConfigurationError;
import java.util.ServiceLoader;
import tools.jackson.databind.json.JsonMapper;

/**
 * Starts the trusted updater with installation-bound authority and the complete strict command grammar.
 */
public final class UpdaterMain
{
	/**
	 * The environment variables updater Java code reads; setup uses the location variables and transport
	 * may use the proxy variables. Routine location authority comes from the installed policy-path record.
	 */
	private static final List<String> ENVIRONMENT_INPUTS = List.of("HOME", "XDG_DATA_HOME", "XDG_CONFIG_HOME",
		"LOCALAPPDATA", "HTTPS_PROXY", "https_proxy", "NO_PROXY", "no_proxy");

	/**
	 * Runs one updater operation and exits with its documented status.
	 *
	 * @param args the complete operation arguments
	 */
	public static void main(String[] args)
	{
		int status;
		try
		{
			Platform platform = Platform.current();
			Map<String, String> environment = new HashMap<>();
			for (String name : ENVIRONMENT_INPUTS)
			{
				String value = System.getenv(name);
				if (value != null)
					environment.put(name, value);
			}
			Invocation invocation = new Invocation(Path.of(System.getProperty("java.home")).toRealPath(),
				Path.of("").toAbsolutePath(), platform, environment,
				ServiceLoader.load(PluginActivation.class).stream().map(ServiceLoader.Provider::get).toList(),
				ServiceLoader.load(SandboxExemption.class).stream().map(ServiceLoader.Provider::get).toList(),
				Clock.systemUTC(), Pause::sleeping, FileReplacer.retrying(Pause::sleeping), System.out, System.err);
			status = invocation.run(CommandLineArguments.recover(args));
		}
		catch (Exception | ServiceConfigurationError failure)
		{
			String message = "The updater could not start: " + failure.getMessage();
			System.err.println(message);
			System.out.println(JsonMapper.builder().build().writeValueAsString(Map.of("result", "refused",
				"harness", "unknown", "version", "", "message", message, "paths", List.of(),
				"consistent", true, "key_ids", List.of())));
			status = 3;
		}
		System.exit(status);
	}

	/**
	 * Prevents construction of the process entry point.
	 */
	private UpdaterMain()
	{
	}
}
