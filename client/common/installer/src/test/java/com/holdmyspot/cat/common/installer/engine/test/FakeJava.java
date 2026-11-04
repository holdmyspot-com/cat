package com.holdmyspot.cat.common.installer.engine.test;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.CountDownLatch;

/**
 * Stands in for a fixture plugin's {@code bin/java} wrapper and the session-start program that it runs, so that
 * a test of how training reacts to a run's outcome does not wait for real Java to record and write a cache.
 * <p>
 * It reacts to the startup cache options as the JDK's {@code java} launcher documents them for its two-step
 * ahead-of-time cache workflow: with {@code -XX:AOTMode=record} it runs the program and then writes the file that
 * {@code -XX:AOTConfiguration} names; with {@code -XX:AOTMode=create} it writes the file that {@code -XX:AOTCache}
 * names without running the program. The cache that it writes names the runtime that trained it: the value of the
 * system property {@code fixture.runtime}, which a fixture runtime's {@code java} sets, or {@code the testing runtime}
 * without one. Every run prints a line to standard output and to standard error.
 * <p>
 * The argument {@code -Dfixture.behavior} selects the program's behavior:
 * <ul>
 *   <li>{@code report}: the recording run writes what the program observed, as properties, to the file that
 *   {@code -Dfixture.report} names: its standard input as {@code input}, each environment variable that the
 *   comma-separated {@code -Dfixture.variables} names as {@code variable.<name>}, and each system property that
 *   {@code -Dfixture.properties} names as {@code property.<name>}. An unset name is reported as {@code null}.</li>
 *   <li>{@code fail}: the cache-writing run writes part of the cache and then fails.</li>
 *   <li>{@code hang}: the recording run never ends.</li>
 *   <li>{@code train}: the runs only write their files.</li>
 * </ul>
 */
public final class FakeJava
{
	/**
	 * Prevents instantiation.
	 */
	private FakeJava()
	{
	}

	/**
	 * Runs the fake.
	 *
	 * @param arguments the arguments that the trainer passed to the wrapper
	 * @throws IOException          if a file cannot be written, or the behavior is {@code fail}
	 * @throws InterruptedException if the behavior is {@code hang} and the program is interrupted
	 */
	public static void main(String[] arguments) throws IOException, InterruptedException
	{
		Map<String, String> options = new HashMap<>();
		for (String argument : arguments)
		{
			int equals = argument.indexOf('=');
			if (argument.startsWith("-") && equals > 0)
				options.put(argument.substring(0, equals), argument.substring(equals + 1));
		}
		System.out.println("output that must not reach the caller");
		System.err.println("error output that must not reach the caller");
		String behavior = options.get("-Dfixture.behavior");
		switch (options.get("-XX:AOTMode"))
		{
			case "record" ->
			{
				if (behavior.equals("hang"))
					new CountDownLatch(1).await();
				if (behavior.equals("report"))
				{
					report(Path.of(options.get("-Dfixture.report")), options.get("-Dfixture.variables"),
						options.get("-Dfixture.properties"));
				}
				Files.writeString(Path.of(options.get("-XX:AOTConfiguration")), "configuration", UTF_8);
			}
			case "create" ->
			{
				Files.writeString(Path.of(options.get("-XX:AOTCache")), cache(), UTF_8);
				if (behavior.equals("fail"))
					throw new IOException("The fixture program fails");
			}
			default -> throw new IllegalArgumentException("Unexpected arguments: " + String.join(" ", arguments));
		}
	}

	/**
	 * Returns the content of the cache that the fake writes.
	 *
	 * @return the content, which names the runtime that trained the cache
	 */
	private static String cache()
	{
		return cacheOf(System.getProperty("fixture.runtime", "the testing runtime"));
	}

	/**
	 * Returns the content of the cache that the fake writes when a fixture runtime trains it.
	 *
	 * @param runtime the name that the fixture runtime gives the fake
	 * @return the content
	 */
	public static String cacheOf(String runtime)
	{
		return "a cache trained by " + runtime;
	}

	/**
	 * Writes what the program observed.
	 *
	 * @param file       the file to write
	 * @param variables  the comma-separated names of the environment variables to report
	 * @param properties the comma-separated names of the system properties to report
	 * @throws IOException if the input cannot be read or the file cannot be written
	 */
	private static void report(Path file, String variables, String properties) throws IOException
	{
		Properties observed = new Properties();
		observed.setProperty("input", new String(System.in.readAllBytes(), UTF_8));
		for (String name : variables.split(","))
			observed.setProperty("variable." + name, String.valueOf(System.getenv(name)));
		for (String name : properties.split(","))
			observed.setProperty("property." + name, String.valueOf(System.getProperty(name)));
		try (OutputStream out = Files.newOutputStream(file))
		{
			observed.store(out, null);
		}
	}
}
