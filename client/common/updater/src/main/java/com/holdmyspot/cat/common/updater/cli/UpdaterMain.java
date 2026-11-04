package com.holdmyspot.cat.common.updater.cli;

import com.holdmyspot.cat.common.CommandLineArguments;
import com.holdmyspot.cat.common.FileReplacer;
import com.holdmyspot.cat.common.Pause;
import com.holdmyspot.cat.common.Platform;
import com.holdmyspot.cat.common.updater.activation.HarnessIntegration;
import com.holdmyspot.cat.common.updater.activation.PluginActivation;
import com.holdmyspot.cat.common.updater.cli.internal.Invocation;
import com.holdmyspot.cat.common.updater.cli.internal.ProcessHarnessCli;
import java.nio.file.Path;
import java.time.Clock;
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
			// The updater's JAR lies in <version>/app, so its version directory is the JAR's grandparent
			Path jar = Path.of(UpdaterMain.class.getProtectionDomain().getCodeSource().getLocation().toURI()).
				toRealPath();
			Invocation invocation = new Invocation(jar.getParent().getParent(), Path.of("").toAbsolutePath(),
				platform, System.getenv(),
				ServiceLoader.load(PluginActivation.class).stream().map(ServiceLoader.Provider::get).toList(),
				ServiceLoader.load(HarnessIntegration.class).stream().map(ServiceLoader.Provider::get).toList(),
				ProcessHarnessCli::new, Clock.systemUTC(), Pause::sleeping, FileReplacer.retrying(Pause::sleeping),
				System.out, System.err);
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
