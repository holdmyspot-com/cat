package com.holdmyspot.cat.common.installer.cli;

import com.holdmyspot.cat.common.CommandLineArguments;
import com.holdmyspot.cat.common.FileReplacer;
import com.holdmyspot.cat.common.Pause;
import com.holdmyspot.cat.common.Platform;
import com.holdmyspot.cat.common.installer.activation.HarnessIntegration;
import com.holdmyspot.cat.common.installer.activation.PluginActivation;
import com.holdmyspot.cat.common.installer.cli.internal.Invocation;
import com.holdmyspot.cat.common.installer.cli.internal.ProcessHarnessCli;
import com.holdmyspot.cat.common.installer.cli.internal.ProcessTerminal;
import com.holdmyspot.cat.common.runner.PluginDirectory;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.ServiceConfigurationError;
import java.util.ServiceLoader;
import tools.jackson.databind.json.JsonMapper;

/**
 * Starts the trusted installer with installation-bound authority and the complete strict command grammar.
 */
public final class InstallerMain
{
	/**
	 * Runs one installer operation and exits with its documented status.
	 *
	 * @param args the complete operation arguments
	 */
	public static void main(String[] args)
	{
		int status;
		try
		{
			Platform platform = Platform.current();
			Invocation invocation = new Invocation(PluginDirectory.of(InstallerMain.class),
				Path.of("").toAbsolutePath(), Path.of(System.getProperty("user.home")), platform, System.getenv(),
				ServiceLoader.load(PluginActivation.class).stream().map(ServiceLoader.Provider::get).toList(),
				ServiceLoader.load(HarnessIntegration.class).stream().map(ServiceLoader.Provider::get).toList(),
				ProcessHarnessCli::new, Clock.systemUTC(), Pause::sleeping, FileReplacer.retrying(Pause::sleeping),
				new ProcessTerminal(platform), System.out, System.err);
			status = invocation.run(CommandLineArguments.recover(args));
		}
		catch (Exception | ServiceConfigurationError failure)
		{
			String message = "The installer could not start: " + failure.getMessage();
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
	private InstallerMain()
	{
	}
}
