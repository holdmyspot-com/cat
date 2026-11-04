package com.holdmyspot.cat.common.installer.engine.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import tools.jackson.databind.json.JsonMapper;

/**
 * Builds the parts of a plugin directory and a Java runtime that a startup cache training runs.
 * <p>
 * A plugin's {@code client/bin/java} wrapper runs the {@code java} of the runtime that the plugin's
 * {@code client/runtime-binding} names. The fixture wrapper does the same with a jar of one test program as the class
 * path, so a test controls which runtime a training runs by the binding, as an installation does. A fixture runtime's
 * {@code java} runs the testing JVM's {@code java} and names the runtime to the program in the system property
 * {@code fixture.runtime}, so {@link FakeJava} can record which runtime trained a cache.
 */
public final class StartupCacheFixtures
{
	/**
	 * The variable that a fixture plugin's start-up program reads its plugin directory from.
	 */
	public static final String ROOT_VARIABLE = "FIXTURE_PLUGIN_ROOT";
	/**
	 * The input that a fixture plugin's start-up program receives.
	 */
	public static final String INPUT = "{\"event\":\"start\"}";
	/**
	 * The fixture jar's path relative to the plugin directory.
	 */
	public static final String JAR = "client/modules/fixture.jar";
	/**
	 * The options that a fixture runtime gives the testing JVM: the stand-in program runs for milliseconds, so the
	 * JVM starts it without the optimizing compiler, the parallel collectors and the performance data file.
	 */
	private static final String QUICK_START = "-XX:TieredStopAtLevel=1 -XX:+UseSerialGC -XX:-UsePerfData";

	/**
	 * Prevents instantiation.
	 */
	private StartupCacheFixtures()
	{
	}

	/**
	 * Returns a jar that holds one already-compiled program.
	 *
	 * @param program the program, a class without nested classes
	 * @return the jar's bytes
	 * @throws IOException if the program's class cannot be read
	 */
	public static byte[] jar(Class<?> program) throws IOException
	{
		String entry = program.getName().replace('.', '/') + ".class";
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		try (InputStream in = program.getResourceAsStream(program.getSimpleName() + ".class");
			JarOutputStream jar = new JarOutputStream(bytes))
		{
			requireThat(in, "programClass").isNotNull();
			jar.putNextEntry(new JarEntry(entry));
			in.transferTo(jar);
			jar.closeEntry();
		}
		return bytes.toByteArray();
	}

	/**
	 * Returns the POSIX {@code client/bin/java} wrapper of a fixture plugin: it runs the bound runtime's
	 * {@code bin/java} with the plugin's fixture jar as the class path.
	 *
	 * @param main the main class that the wrapper names before the arguments that it receives, or an empty string
	 * @return the wrapper's text
	 */
	public static String posixWrapper(String main)
	{
		return "#!/bin/sh\n" +
			"client=$(CDPATH= cd -P -- \"$(dirname -- \"$0\")/..\" && pwd -P)\n" +
			"IFS= read -r runtime < \"$client/runtime-binding\"\n" +
			"exec \"$runtime/bin/java\" -cp \"$client/modules/fixture.jar\"" + mainArgument(main) + " \"$@\"\n";
	}

	/**
	 * Returns the Windows {@code client\bin\java.cmd} wrapper of a fixture plugin: it runs a program of the bound
	 * runtime with the plugin's fixture jar as the class path.
	 *
	 * @param main    the main class that the wrapper names before the arguments that it receives, or an empty string
	 * @param program the runtime's program: {@code java.exe} for a real runtime, {@code java.cmd} for a fixture runtime
	 * @return the wrapper's text
	 */
	public static String windowsWrapper(String main, String program)
	{
		return "@echo off\r\n" +
			"for %%I in (\"%~dp0..\") do set \"CLIENT=%%~fI\"\r\n" +
			"set /p RUNTIME=<\"%CLIENT%\\runtime-binding\"\r\n" +
			"call \"%RUNTIME%\\bin\\" + program + "\" -cp \"%CLIENT%\\modules\\fixture.jar\"" + mainArgument(main) +
			" %*\r\n";
	}

	/**
	 * Returns the POSIX {@code bin/java} of a fixture runtime.
	 *
	 * @param runtime the name that the runtime gives the program in {@code fixture.runtime}
	 * @return the program's text
	 */
	public static String posixRuntimeJava(String runtime)
	{
		return "#!/bin/sh\nexec '" + testingJava("java") + "' " + QUICK_START + " '-Dfixture.runtime=" + runtime +
			"' \"$@\"\n";
	}

	/**
	 * Returns the Windows {@code bin\java.cmd} of a fixture runtime.
	 *
	 * @param runtime the name that the runtime gives the program in {@code fixture.runtime}
	 * @return the program's text
	 */
	public static String windowsRuntimeJava(String runtime)
	{
		return "@\"" + testingJava("java.exe") + "\" " + QUICK_START + " \"-Dfixture.runtime=" + runtime + "\" %*\r\n";
	}

	/**
	 * Returns a training description.
	 *
	 * @param arguments the arguments that the description gives the wrapper after the startup cache options
	 * @return the description's JSON text
	 */
	public static String description(List<String> arguments)
	{
		return JsonMapper.builder().build().writeValueAsString(Map.of("format", 1, "arguments", arguments,
			"plugin_root_variable", ROOT_VARIABLE, "input", INPUT));
	}

	/**
	 * Returns the path of the testing JVM's {@code java} program.
	 *
	 * @param name the program's file name
	 * @return the path
	 */
	public static String testingJava(String name)
	{
		return Path.of(System.getProperty("java.home")).resolve("bin").resolve(name).toString();
	}

	/**
	 * Returns a wrapper's main class argument.
	 *
	 * @param main the main class, or an empty string
	 * @return the argument with its leading space, or an empty string
	 */
	private static String mainArgument(String main)
	{
		if (main.isEmpty())
			return "";
		return " " + main;
	}
}
