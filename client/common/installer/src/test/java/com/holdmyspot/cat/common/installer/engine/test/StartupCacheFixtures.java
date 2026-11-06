package com.holdmyspot.cat.common.installer.engine.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import tools.jackson.databind.json.JsonMapper;

/**
 * Builds the parts of a plugin directory that a startup cache training runs.
 * <p>
 * A plugin's {@code bin/java} wrapper runs the {@code java} of the runtime that the plugin's
 * {@code bin/runtime-binding} names. The fixture wrapper does the same with a jar of one test program as the class
 * path, so a test controls which runtime a training runs by the binding, as an installation does.
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
	public static final String JAR = "bin/modules/fixture.jar";

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
	 * Returns the POSIX {@code bin/java} wrapper of a fixture plugin: it runs the bound runtime's
	 * {@code bin/java} with the plugin's fixture jar as the class path.
	 *
	 * @return the wrapper's text
	 */
	public static String posixWrapper()
	{
		return "#!/bin/sh\n" +
			"bin=$(CDPATH= cd -P -- \"$(dirname -- \"$0\")\" && pwd -P)\n" +
			"IFS= read -r runtime < \"$bin/runtime-binding\"\n" +
			"exec \"$runtime/bin/java\" -cp \"$bin/modules/fixture.jar\" \"$@\"\n";
	}

	/**
	 * Returns the Windows {@code bin\java.cmd} wrapper of a fixture plugin: it runs the bound runtime's
	 * {@code java.exe} with the plugin's fixture jar as the class path.
	 *
	 * @return the wrapper's text
	 */
	public static String windowsWrapper()
	{
		return "@echo off\r\n" +
			"for %%I in (\"%~dp0.\") do set \"BIN=%%~fI\"\r\n" +
			"set /p RUNTIME=<\"%BIN%\\runtime-binding\"\r\n" +
			"call \"%RUNTIME%\\bin\\java.exe\" -cp \"%BIN%\\modules\\fixture.jar\" %*\r\n";
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
}
