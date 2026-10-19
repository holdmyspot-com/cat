package com.holdmyspot.cat.distribution.bundle;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static java.nio.charset.StandardCharsets.UTF_8;

import com.holdmyspot.cat.common.BoundedInput;
import com.holdmyspot.cat.distribution.bundle.secrets.JlinkImageBuilderSecrets;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.spi.ToolProvider;
import java.util.stream.Stream;

/**
 * Uses the JDK's {@code jlink} tool to build the Java runtime directory bundled with a CAT plugin.
 */
public final class JlinkImageBuilder
{
	private static final int MAXIMUM_LAUNCHER_BYTES = 1_048_576;
	/**
	 * The options of every launcher's JVM. The CLI's standard output carries structured responses, so JVM warnings go to
	 * standard error instead of corrupting that channel. Java writes its standard streams in the console's or the
	 * system's character set, which on Windows is not UTF-8, while the harnesses read UTF-8. On Windows, the common
	 * module reads the program's arguments from the process's command line through native code, because the Java
	 * launcher loses the characters that the system's code page lacks.
	 */
	private static final String VM_OPTIONS = "-Xlog:all=off:stdout -Xlog:all=warning:stderr " +
		"-Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8 --enable-native-access=com.holdmyspot.cat.common.cli";

	static
	{
		JlinkImageBuilderSecrets.setAccess(JlinkImageBuilder::new);
	}

	/**
	 * Defines the launcher filename and Java module/main class passed to {@code jlink}.
	 *
	 * @param name the launcher filename
	 * @param module the module containing the entry point
	 * @param mainClass the entry-point class
	 */
	public record Launcher(String name, String module, String mainClass)
	{
		/**
		 * Validates the launcher configuration.
		 *
		 * @param name the launcher filename
		 * @param module the module containing the entry point
		 * @param mainClass the entry-point class
		 * @throws NullPointerException if {@code name}, {@code module}, or {@code mainClass} are null
		 * @throws IllegalArgumentException if {@code name}, {@code module}, or {@code mainClass} are blank
		 */
		public Launcher
		{
			requireThat(name, "name").isNotBlank();
			requireThat(module, "module").isNotBlank();
			requireThat(mainClass, "mainClass").isNotBlank();
		}
	}

	private final ToolProvider jlink;

	/**
	 * Creates a builder backed by the current JDK's jlink tool.
	 *
	 * @throws IllegalStateException if the JDK does not provide jlink
	 */
	public JlinkImageBuilder()
	{
		this(ToolProvider.findFirst("jlink").
			orElseThrow(() -> new IllegalStateException("The JDK does not provide jlink")));
	}

	/**
	 * Creates a builder backed by a tool provider.
	 *
	 * @param jlink the jlink tool provider
	 * @throws NullPointerException if {@code jlink} is null
	 */
	private JlinkImageBuilder(ToolProvider jlink)
	{
		requireThat(jlink, "jlink").isNotNull();
		this.jlink = jlink;
	}

	/**
	 * Builds a runtime image.
	 *
	 * @param modulePath the module-path entries
	 * @param rootModules the modules to include
	 * @param launchers the launchers to generate
	 * @param output the output directory
	 * @return the output directory
	 * @throws NullPointerException if {@code modulePath}, {@code rootModules}, {@code launchers}, or {@code output}
	 *         are null
	 * @throws IllegalArgumentException if {@code modulePath} or {@code rootModules} are empty
	 * @throws IOException if jlink fails or a generated launcher cannot be updated
	 */
	public Path build(List<Path> modulePath, Set<String> rootModules, List<Launcher> launchers, Path output)
		throws IOException
	{
		requireThat(modulePath, "modulePath").isNotEmpty();
		requireThat(rootModules, "rootModules").isNotEmpty();
		requireThat(launchers, "launchers").isNotNull();
		requireThat(output, "output").isNotNull();

		String joinedModulePath = modulePath.stream().map(Path::normalize).map(Path::toString).sorted().
			reduce((left, right) -> left + File.pathSeparator + right).orElseThrow();
		String joinedModules = rootModules.stream().sorted().reduce((left, right) -> left + "," + right).orElseThrow();
		List<String> arguments = new ArrayList<>();
		arguments.add("--module-path");
		arguments.add(joinedModulePath);
		arguments.add("--add-modules");
		arguments.add(joinedModules);
		launchers.stream().sorted(Comparator.comparing(Launcher::name)).forEach(launcher ->
		{
			arguments.add("--launcher");
			arguments.add(launcher.name() + "=" + launcher.module() + "/" + launcher.mainClass());
		});
		arguments.add("--output");
		arguments.add(output.toAbsolutePath().normalize().toString());
		arguments.add("--strip-debug");
		arguments.add("--no-header-files");
		arguments.add("--no-man-pages");

		StringWriter standardOutput = new StringWriter();
		StringWriter standardError = new StringWriter();
		int exitCode;
		try (PrintWriter out = new PrintWriter(standardOutput);
			PrintWriter err = new PrintWriter(standardError))
		{
			exitCode = jlink.run(out, err, arguments.toArray(String[]::new));
		}
		if (exitCode != 0)
		{
			String diagnostics = (standardError + System.lineSeparator() + standardOutput).trim();
			String details = "";
			if (!diagnostics.isEmpty())
				details = ": " + diagnostics;
			throw new IOException("jlink failed with exit code " + exitCode + details);
		}
		rewriteLaunchersForWhitespacePaths(launchers, output);
		rewriteBatchLaunchers(launchers, output);
		return output;
	}

	/**
	 * Removes the POSIX launchers of an image built on Windows, keeping its batch launchers. PowerShell runs a command
	 * that a path without an extension names from the file of exactly that name, in preference to the file that adds an
	 * extension from {@code PATHEXT}, and hands that file, which it cannot run, to the program that Windows associates
	 * with it. Once the POSIX launcher is gone, the same command runs the batch launcher.
	 *
	 * @param launchers the launchers whose POSIX form to remove
	 * @param output the runtime-image directory
	 * @throws NullPointerException if {@code output} or {@code launchers} are null
	 * @throws IOException if a launcher has no batch form or cannot be removed
	 */
	public static void removePosixLaunchers(List<Launcher> launchers, Path output) throws IOException
	{
		requireThat(output, "output").isNotNull();
		requireThat(launchers, "launchers").isNotNull();
		for (Launcher launcher : launchers)
		{
			Path script = output.resolve("bin").resolve(launcher.name());
			if (Files.notExists(script.resolveSibling(launcher.name() + ".bat")))
				throw new IOException("The launcher has no batch form: " + script);
			Files.delete(script);
		}
	}

	/**
	 * Replaces every symbolic link in a runtime image with a copy of the file that it points to. jlink links some
	 * modules' license files to the {@code java.base} module's copies, and an installer that drops symbolic links, such
	 * as Codex's plugin installer, would otherwise leave those modules without their license files.
	 *
	 * @param image the runtime-image directory
	 * @throws NullPointerException if {@code image} is null
	 * @throws IOException if a link does not resolve to a regular file, or if the image cannot be read or changed
	 */
	public static void replaceLinksWithCopies(Path image) throws IOException
	{
		requireThat(image, "image").isNotNull();
		List<Path> links;
		try (Stream<Path> paths = Files.walk(image))
		{
			links = paths.filter(Files::isSymbolicLink).toList();
		}
		for (Path link : links)
		{
			if (!Files.isRegularFile(link))
				throw new IOException("The symbolic link does not point to a regular file: " + link);
		}
		for (Path link : links)
		{
			Path target = link.resolveSibling(Files.readSymbolicLink(link));
			Files.delete(link);
			Files.copy(target, link);
		}
	}

	/**
	 * Moves launchers out of a runtime image's {@code bin} folder into another folder, and makes each one start the
	 * image's Java runtime from its new folder. A launcher that jlink writes starts the {@code java} beside it, which
	 * the new folder lacks. A batch launcher takes the extension {@code .cmd} in its new folder, the name under which
	 * CAT's Windows rules tell agents to run it; {@code cmd.exe} runs both extensions alike.
	 *
	 * @param names       the names of the launchers to move; each launcher's POSIX form, batch form, or both move
	 * @param image       the runtime-image directory whose {@code bin} folder holds the launchers and {@code java}
	 * @param destination the folder that receives the launchers
	 * @throws NullPointerException if {@code names}, {@code image} or {@code destination} are null
	 * @throws IOException if a launcher has neither form, has an unexpected format, already exists in
	 *                     {@code destination}, or cannot be moved
	 */
	public static void moveLaunchers(Set<String> names, Path image, Path destination) throws IOException
	{
		requireThat(names, "names").isNotNull();
		requireThat(image, "image").isNotNull();
		requireThat(destination, "destination").isNotNull();
		Path bin = image.resolve("bin");
		String runtime = destination.toAbsolutePath().normalize().relativize(bin.toAbsolutePath().normalize()).
			toString();
		String posixRuntime = runtime.replace('\\', '/');
		String batchRuntime = runtime.replace('/', '\\');
		Files.createDirectories(destination);
		for (String name : names.stream().sorted().toList())
		{
			boolean moved = moveLauncher(bin.resolve(name), destination.resolve(name), "\"$DIR/java\"",
				"\"$DIR/" + posixRuntime + "/java\"");
			moved |= moveLauncher(bin.resolve(name + ".bat"), destination.resolve(name + ".cmd"), "\"%DIR%\\java\"",
				"\"%DIR%\\" + batchRuntime + "\\java\"");
			if (!moved)
				throw new IOException("The runtime image has no launcher named " + name + ": " + bin);
		}
	}

	/**
	 * Moves one launcher and replaces its path to {@code java}.
	 *
	 * @param launcher    the launcher in the runtime image
	 * @param destination the launcher's new path
	 * @param java        the launcher's text that names the {@code java} beside it
	 * @param replacement the text that names the image's {@code java} from the folder of {@code destination}
	 * @return {@code false} if the image has no such launcher
	 * @throws IOException if the launcher does not name {@code java}, {@code destination} already exists, or the
	 *                     launcher cannot be moved
	 */
	private static boolean moveLauncher(Path launcher, Path destination, String java, String replacement)
		throws IOException
	{
		if (Files.notExists(launcher))
			return false;
		String content = BoundedInput.readUtf8(launcher, MAXIMUM_LAUNCHER_BYTES, "jlink launcher");
		if (!content.contains(java))
			throw new IOException("Unexpected jlink launcher format: " + launcher);
		Files.move(launcher, destination);
		Files.writeString(destination, content.replace(java, replacement), UTF_8);
		return true;
	}

	/**
	 * Makes jlink launchers work from installation paths containing whitespace and in every locale, and gives each
	 * launcher's JVM a private performance-data directory when Bubblewrap is available.
	 * <p>
	 * jlink emits {@code $DIR/java -m ...}. With {@code DIR=/opt/CAT Plugin/bin}, the shell splits that command at
	 * the space. This method replaces it with {@code exec "$DIR/java" -m ...}, after deriving {@code DIR} from a
	 * quoted launcher path. HotSpot names its shared performance-data file {@code /tmp/hsperfdata_<user>/<pid>}, and
	 * JVMs in separate PID namespaces can have the same PID and therefore contend for the same file. Mounting an empty
	 * temporary filesystem over only that directory keeps the feature enabled without that contention, while the
	 * program shares the rest of the caller's {@code /tmp}. Without Bubblewrap, the launcher runs Java directly.
	 * <p>
	 * Java names files and decodes its arguments in the character set of {@code LC_CTYPE}, which in the C locale cannot
	 * name a file whose name is not ASCII. When the caller's {@code LC_CTYPE} is not UTF-8, every launcher therefore runs
	 * Java in {@code LC_CTYPE=C.UTF-8}. It keeps the caller's {@code LC_ALL} and {@code LC_CTYPE} in
	 * {@code CAT_CALLER_LC_ALL} and {@code CAT_CALLER_LC_CTYPE}, each {@code +} followed by the value or {@code -} when
	 * it is unset, so that a program can run a command in the caller's locale. A caller whose locale is already UTF-8
	 * keeps it, so that the programs that Java starts inherit it unchanged.
	 *
	 * @param launchers the launchers to rewrite
	 * @param output the runtime-image directory
	 * @throws IOException if a launcher cannot be read or written
	 */
	private void rewriteLaunchersForWhitespacePaths(List<Launcher> launchers, Path output) throws IOException
	{
		String unconfiguredVmOptions = "JLINK_VM_OPTIONS=\n";
		String configuredVmOptions = "JLINK_VM_OPTIONS='" + VM_OPTIONS + "'\n";
		String unquotedDirectory = "DIR=`dirname $0`\n";
		String unquotedInvocation = "$DIR/java ";
		String quotedDirectory = "DIR=$(CDPATH= cd -- \"$(dirname -- \"$0\")\" && pwd)\n";
		String localeSetup = """
			case ${LC_ALL:-${LC_CTYPE:-${LANG:-}}} in
			  *[Uu][Tt][Ff]-8* | *[Uu][Tt][Ff]8*) ;;
			  *)
			    CAT_CALLER_LC_ALL=${LC_ALL+"+$LC_ALL"}
			    CAT_CALLER_LC_CTYPE=${LC_CTYPE+"+$LC_CTYPE"}
			    export CAT_CALLER_LC_ALL="${CAT_CALLER_LC_ALL:--}"
			    export CAT_CALLER_LC_CTYPE="${CAT_CALLER_LC_CTYPE:--}"
			    unset LC_ALL
			    export LC_CTYPE=C.UTF-8
			    ;;
			esac
			""";
		for (Launcher launcher : launchers)
		{
			Path script = output.resolve("bin").resolve(launcher.name());
			String content = BoundedInput.readUtf8(script, MAXIMUM_LAUNCHER_BYTES, "jlink launcher");
			if (!content.contains(unconfiguredVmOptions) || !content.contains(unquotedDirectory) ||
				!content.contains(unquotedInvocation))
				throw new IOException("Unexpected jlink launcher format: " + script);
			int invocationStart = content.indexOf(unquotedInvocation);
			int invocationEnd = content.indexOf('\n', invocationStart);
			if (invocationEnd < 0)
				throw new IOException("Jlink launcher invocation has no line ending: " + script);
			String arguments = content.substring(invocationStart + unquotedInvocation.length(), invocationEnd);
			String isolatedInvocation = "if command -v bwrap >/dev/null 2>&1; then\n" +
				"  exec bwrap --bind / / --tmpfs \"/tmp/hsperfdata_$(id -un)\" --proc /proc --dev /dev -- \"$DIR/java\" " +
				arguments + "\nfi\nexec \"$DIR/java\" " + arguments;
			String rewritten = content.replace(unconfiguredVmOptions, configuredVmOptions).
				replace(unquotedDirectory, quotedDirectory);
			rewritten = rewritten.substring(0, rewritten.indexOf(unquotedInvocation)) + localeSetup + isolatedInvocation +
				rewritten.substring(rewritten.indexOf('\n', rewritten.indexOf(unquotedInvocation)));
			Files.writeString(script, rewritten, UTF_8);
		}
	}

	/**
	 * Gives the batch launchers that jlink writes on Windows the JVM options of the POSIX launchers, and quotes the
	 * assignment of the launcher's folder, whose name may hold a character, such as {@code &}, that {@code cmd.exe}
	 * reads as a command separator. Other platforms have no batch launchers.
	 *
	 * @param launchers the launchers to rewrite
	 * @param output the runtime-image directory
	 * @throws IOException if a launcher cannot be read or written
	 */
	private void rewriteBatchLaunchers(List<Launcher> launchers, Path output) throws IOException
	{
		String unconfiguredVmOptions = "set JLINK_VM_OPTIONS=\r\n";
		String unquotedDirectory = "set DIR=%~dp0\r\n";
		for (Launcher launcher : launchers)
		{
			Path script = output.resolve("bin").resolve(launcher.name() + ".bat");
			if (Files.notExists(script))
				continue;
			String content = BoundedInput.readUtf8(script, MAXIMUM_LAUNCHER_BYTES, "jlink launcher");
			if (!content.contains(unconfiguredVmOptions) || !content.contains(unquotedDirectory))
				throw new IOException("Unexpected jlink launcher format: " + script);
			String rewritten = content.replace(unconfiguredVmOptions, "set JLINK_VM_OPTIONS=" + VM_OPTIONS + "\r\n").
				replace(unquotedDirectory, "set \"DIR=%~dp0\"\r\n");
			Files.writeString(script, rewritten, UTF_8);
		}
	}
}
