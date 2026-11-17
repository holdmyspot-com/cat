package com.holdmyspot.cat.common.installer.engine.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static java.nio.charset.StandardCharsets.UTF_8;

import com.holdmyspot.cat.common.CodeLoadingVariables;
import com.holdmyspot.cat.common.CommandLineArguments;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.concurrent.TimeUnit;

/**
 * Writes the executable fixtures of {@link ProcessElevationTest} in owned child JVMs, which create no descendants.
 */
public final class ExecutableWriter
{
	/**
	 * Writes the supplied body as UTF-8 and closes the file before this fixture process exits.
	 *
	 * @param arguments the output path followed by the Base64-encoded UTF-8 file body; the parent directory must exist
	 * @throws IllegalArgumentException if there are not exactly two arguments or the body is not valid Base64
	 * @throws IOException              if the output cannot be written
	 */
	public static void main(String[] arguments) throws IOException
	{
		String[] recovered = CommandLineArguments.recover(arguments);
		if (recovered.length != 2)
			throw new IllegalArgumentException("Expected an output path and file body");
		String body = new String(Base64.getDecoder().decode(recovered[1]), UTF_8);
		Files.writeString(Path.of(recovered[0]), body, UTF_8);
	}

	/**
	 * Writes a program in an isolated child JVM and waits for successful termination before returning.
	 * The body is encoded for transport so Windows command-line quoting cannot alter its quotes or newlines.
	 * The caller owns removal of the program's parent directory, including the writer's working, home, temporary and
	 * output paths. The process scope terminates and joins the writer on failure or interruption.
	 *
	 * @param program the program to write, in an existing parent directory without a {@code writer} child
	 * @param body    the complete program body
	 * @throws NullPointerException if any argument is null
	 * @throws IOException          if process state cannot be created or the writer fails or does not end in one minute
	 * @throws InterruptedException if the writer wait is interrupted
	 */
	public static void write(Path program, String body) throws IOException, InterruptedException
	{
		requireThat(program, "program").isNotNull();
		requireThat(body, "body").isNotNull();
		String modulePath = System.getProperty("jdk.module.path");
		if (modulePath == null || modulePath.isBlank())
			throw new IOException("The test JVM did not expose jdk.module.path");
		Path working = Files.createDirectory(program.getParent().resolve("writer"));
		Path home = Files.createDirectory(working.resolve("home"));
		Path temporary = Files.createDirectory(working.resolve("temporary"));
		Path output = working.resolve("output.log");
		Path java = Path.of(System.getProperty("java.home"), "bin", "java");
		ProcessBuilder builder = new ProcessBuilder(java.toString(), "-Duser.home=" + home,
			"-Djava.io.tmpdir=" + temporary, "--enable-native-access=com.holdmyspot.cat.common.cli",
			"--module-path", modulePath, "--module",
			ExecutableWriter.class.getModule().getName() + "/" + ExecutableWriter.class.getName(),
			program.toAbsolutePath().toString(), Base64.getEncoder().encodeToString(body.getBytes(UTF_8)));
		builder.directory(working.toFile());
		builder.redirectErrorStream(true);
		builder.redirectOutput(output.toFile());
		builder.environment().put("HOME", home.toString());
		builder.environment().put("TMPDIR", temporary.toString());
		builder.environment().put("TMP", temporary.toString());
		builder.environment().put("TEMP", temporary.toString());

		try (Process writer = CodeLoadingVariables.start(builder))
		{
			writer.getOutputStream().close();
			if (!writer.waitFor(1, TimeUnit.MINUTES))
				throw new IOException("Executable writer did not end: " + program);
			if (writer.exitValue() != 0)
				throw new IOException("Executable writer failed for " + program + ": " + Files.readString(output));
		}
	}

	/**
	 * Prevents instantiation.
	 */
	private ExecutableWriter()
	{
	}
}
