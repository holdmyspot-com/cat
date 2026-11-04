package com.holdmyspot.cat.distribution.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static java.nio.charset.StandardCharsets.UTF_8;

import com.holdmyspot.cat.common.FileReplacer;
import com.holdmyspot.cat.common.Platform;
import com.holdmyspot.cat.common.installation.PluginVersion;
import com.holdmyspot.cat.common.TemporaryDirectories;
import com.holdmyspot.cat.common.installer.activation.HarnessIntegration;
import com.holdmyspot.cat.distribution.bundle.ArtifactLayer;
import com.holdmyspot.cat.distribution.bundle.PluginArtifactBuilder;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Tests that both plugins hold the {@code update} and {@code uninstall} skills, through which an agent installs,
 * updates and removes CAT only through the installed trusted installer.
 */
public final class LifecycleSkillsTest
{
	/**
	 * The skills that update and remove CAT.
	 */
	private static final List<String> SKILLS = List.of("update", "uninstall");
	/**
	 * A launcher command that a skill names, with its arguments.
	 */
	private static final Pattern LAUNCHER_COMMAND = Pattern.compile("`<launcher> ([^`]+)`");
	/**
	 * A Codex execution-policy rule and its command prefix.
	 */
	private static final Pattern PREFIX_RULE = Pattern.compile("prefix_rule\\(pattern=(\\[[^]]*]), decision=");
	private static final JsonMapper MAPPER = JsonMapper.builder().build();

	/** Creates a test instance. */
	public LifecycleSkillsTest()
	{
	}

	/**
	 * Supplies each harness whose plugin to assemble.
	 *
	 * @return the harness's name
	 */
	@DataProvider
	public Object[][] harnesses()
	{
		return new Object[][]{{"claude"}, {"codex"}};
	}

	/**
	 * Verifies that a harness's plugin, assembled from the skill layers, holds the {@code update} and
	 * {@code uninstall} skills with routing descriptions, its own harness's frontmatter and identifier, and the shared
	 * body, which runs only the trusted installer's launcher, never installs through a script of the plugin, and leaves a
	 * sandbox-blocked change to the user.
	 *
	 * @param harness the harness whose plugin to assemble
	 * @throws IOException        if a skill cannot be read, or the plugin cannot be assembled
	 * @throws URISyntaxException if the class path returns a malformed resource location
	 */
	@Test(dataProvider = "harnesses")
	public void pluginHoldsLifecycleSkills(String harness)
		throws IOException, URISyntaxException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path temporary = cleanup.add(Files.createTempDirectory("cat-lifecycle-skills-"));
			Path commonSkills = skillJar("common", temporary.resolve("common-skills.jar"));
			Path harnessSkills = skillJar(harness, temporary.resolve(harness + "-skills.jar"));
			Path legal = Files.createDirectories(temporary.resolve("legal"));
			Path plugin = new PluginArtifactBuilder(PluginVersion.parse("1.0+b0123456789ab")).build(
				List.of(new ArtifactLayer("common-skills", commonSkills),
					new ArtifactLayer(harness + "-skills", harnessSkills)),
				Files.createDirectories(temporary.resolve("bin")), legal, List.of(), temporary.resolve("plugin"));

			requireThat(description(Files.readString(plugin.resolve("skills/update/SKILL.md"))), "updateDescription").
				startsWith("Use when the user asks").contains("install").contains("update").contains("upgrade");
			String updateBody = Files.readString(plugin.resolve("skills/update/first-use.md")).replaceAll("\\s+", " ");
			requireThat(updateBody, "updateBody").contains("cat-updater/<h>/bin/cat-updater").
				contains("cat-updater\\<h>\\bin\\cat-updater.ps1").contains("`<launcher> install --harness <h>`").
				contains("`<launcher> update --harness <h>`").contains("`<launcher> update --harness <h> <tag>`").
				doesNotContain("rollback").doesNotContain("roll back").doesNotContain("rolled_back").
				contains("When the user asks to go back to an earlier release, run " +
					"`<launcher> update --harness <h> <tag>`").
				contains("An update without a release never goes back to an earlier one.").
				contains("report the message to the user as it is, and do not retry the update or go back in any " +
					"other way.").
				contains("`powershell.exe -NoProfile -NonInteractive -ExecutionPolicy Bypass -File`").
				doesNotContain("cat-updater.cmd").
				contains("`<launcher> status --harness <h>`").
				contains("`installed`").contains("`up_to_date`").contains("`refused`").
				contains("`failed`: an unexpected error stopped the operation").
				contains("rerun the entry command of a newer release").contains("releases/latest/download/cat.sh").
				contains("releases/download/<tag>/cat.sh").contains("cat.ps1").contains("`-Harness <h>`").
				contains("with no release argument").
				contains("run `<launcher> update --harness <h> <tag>` after `install` reports `installed`").
				contains("`pending_swap`").contains("`aot_cache`").
				contains("`not_swapped`: the Java runtime could not be put in place, so the operation did not finish. " +
					"Run `<launcher> install --harness <h>` at once: it undoes the failed operation and puts back " +
					"the previous release, so that CAT's hooks work again. Then tell the user that the operation " +
					"could not complete, quoting the `not_swapped` result's `message`; when that install also fails, " +
					"quote its `message` too.").
				contains("Do not mention `aot_cache` to the user unless they ask about it").
				contains("Never run the launcher's `setup` operation yourself").
				contains("restart or resume the session").doesNotContain("--updater-only").
				doesNotContain("upgrade-self").doesNotContain("install/cat.sh").doesNotContain("bootstrap");
			requireThat(description(Files.readString(plugin.resolve("skills/uninstall/SKILL.md"))),
				"uninstallDescription").startsWith("Use when the user asks").contains("uninstall").contains("remove");
			String uninstallBody = Files.readString(plugin.resolve("skills/uninstall/first-use.md")).
				replaceAll("\\s+", " ");
			requireThat(uninstallBody, "uninstallBody").contains("cat-updater/<h>/bin/cat-updater").
				contains("cat-updater\\<h>\\bin\\cat-updater.ps1").
				contains("Before you run anything, ask the user whether").
				contains("the default is to keep it").contains("`<launcher> uninstall --harness <h>`").
				contains("`<launcher> uninstall --harness <h> --delete-data` only when the user asked to delete it").
				contains("`uninstalled`").contains("`not_removed`").contains("needs the one manual entry command again").
				contains("`uninstall_incomplete`: the removal stopped at a step and kept its progress. Run the same " +
					"command once more").
				contains("When the result's `unregistered` field is false").
				contains("`claude plugin uninstall cat@cat`").contains("`codex plugin remove cat@cat`").
				contains("not the other harness's CAT installation").
				contains("may ask the user to approve this command").doesNotContain("cat-updater.cmd").
				doesNotContain("install/").doesNotContain("uninstall-").doesNotContain("-Uninstall").
				doesNotContain("-DeleteData").doesNotContain("cat.sh").doesNotContain("XDG_CONFIG_HOME").
				doesNotContain("rollback").doesNotContain("roll back");
			for (String skill : SKILLS)
			{
				String manifest = Files.readString(plugin.resolve("skills/" + skill + "/SKILL.md"));
				requireThat(manifest, skill).contains("--harness " + harness).
					contains("write `" + harness + "` in its place").contains("Read `first-use.md`").
					doesNotContain("cat:include").doesNotContain("cat.sh");
				String body = Files.readString(plugin.resolve("skills/" + skill + "/first-use.md"));
				// The skill's source wraps lines, so phrases are matched across line breaks
				String flattened = body.replaceAll("\\s+", " ");
				requireThat(flattened, skill + "Body").contains("status 77").contains("outside the sandbox").
					contains("end your turn with a reply that names that path").
					contains("in a code block character for character as printed").
					contains("never as a summary or a description of it").contains("prefixed with `! `").
					contains("Do no other work in that turn").contains("Do not run it again").
					doesNotContain("${CAT_PLUGIN_ROOT}");
				if (harness.equals("claude"))
					requireThat(manifest, skill).contains("allowed-tools: [Bash]");
				else
				{
					// Codex runs a skill's commands in PowerShell on Windows and in a POSIX shell elsewhere
					requireThat(manifest, skill).doesNotContain("allowed-tools");
					requireThat(body, skill + "Body").doesNotContain("$").doesNotContain(" | ").doesNotContain("```");
				}
			}
			requireThat(Files.exists(plugin.resolve("skills/shared-lazy-body.md")), "sourceOnlyBody").isFalse();
		}
	}

	/**
	 * Verifies that every installer command that the {@code update} and {@code uninstall} skills tell an agent to run
	 * matches a permission entry that setup installs for the harness, so that the harness lets the agent run it.
	 *
	 * @param harness the harness whose permission entries to install
	 * @throws IOException if a skill cannot be read or the permission entries cannot be installed
	 */
	@Test(dataProvider = "harnesses")
	public void skillCommandsMatchPermissionEntries(String harness) throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path home = cleanup.add(Files.createTempDirectory("cat-lifecycle-permissions-"));
			Path launcher = home.resolve("data/bin/cat-updater");
			HarnessIntegration integration = ServiceLoader.load(HarnessIntegration.class).stream().
				map(ServiceLoader.Provider::get).filter(provider -> provider.harness().equals(harness)).findFirst().
				orElseThrow();
			Map<String, List<String>> entries = integration.installPermissions(home, launcher,
				new FileReplacer(Platform.current(), () ->
				{
				}, Clock.systemUTC()));

			List<String> commands = new ArrayList<>();
			for (String skill : SKILLS)
			{
				Matcher matcher = LAUNCHER_COMMAND.matcher(skillBody(skill));
				while (matcher.find())
				{
					commands.add(launcher + " " + matcher.group(1).replace("<h>", harness).
						replace("<tag>", "v1.1"));
				}
			}
			requireThat(commands, "commands").isNotEmpty();
			List<String> exclusions = entries.getOrDefault("settings.json#sandbox.excludedCommands", List.of());
			for (String command : commands)
			{
				if (harness.equals("claude"))
				{
					// A command the skill runs must be excluded from the sandbox, or the sandbox may block it
					requireThat(exclusions.stream().anyMatch(exclusion -> matches(exclusion, command)),
						"isExcluded").withContext(command, "command").withContext(exclusions, "exclusions").isTrue();
				}
				requireThat(isPermitted(command, entries), "isPermitted").withContext(command, "command").
					withContext(entries, "entries").isTrue();
			}
		}
	}

	/**
	 * Returns whether a permission entry that a harness integration installed matches a command.
	 *
	 * @param command the command, with single spaces between its tokens
	 * @param entries the installed entries, as {@link HarnessIntegration#installPermissions} returns them
	 * @return true if Claude Code would run the command outside its sandbox or allow it without a prompt, or a Codex
	 * 	rule's prefix matches it
	 * @throws IOException if a Codex rule's pattern is malformed
	 */
	private static boolean isPermitted(String command, Map<String, List<String>> entries) throws IOException
	{
		for (Map.Entry<String, List<String>> setting : entries.entrySet())
		{
			for (String entry : setting.getValue())
			{
				boolean permitted = switch (setting.getKey())
				{
					case "settings.json#sandbox.excludedCommands" -> matches(entry, command);
					case "settings.json#permissions.allow" -> allows(entry, command);
					case "rules/cat-installer.rules#" -> hasPrefixRule(entry, command);
					default -> throw new AssertionError("Unknown permission setting: " + setting.getKey());
				};
				if (permitted)
					return true;
			}
		}
		return false;
	}

	/**
	 * Returns whether a Claude Code permission rule allows a command.
	 *
	 * @param rule    the rule, {@code Bash(<command>)} or {@code Bash(<prefix> *)}
	 * @param command the command
	 * @return true if the rule names the command or a prefix of it followed by more arguments
	 */
	private static boolean allows(String rule, String command)
	{
		return matches(rule.substring("Bash(".length(), rule.length() - 1), command);
	}

	/**
	 * Returns whether a Claude Code command pattern matches a command.
	 *
	 * @param pattern the pattern, a command or {@code <prefix> *}
	 * @param command the command
	 * @return true if the pattern names the command or a prefix of it followed by more arguments
	 */
	private static boolean matches(String pattern, String command)
	{
		if (pattern.endsWith(" *"))
		{
			String prefix = pattern.substring(0, pattern.length() - 1);
			return command.startsWith(prefix) && command.length() > prefix.length();
		}
		return pattern.equals(command);
	}

	/**
	 * Returns whether a Codex execution-policy file holds a rule whose prefix matches a command.
	 *
	 * @param rules   the file's content
	 * @param command the command
	 * @return true if the tokens of a rule's pattern start the command's tokens
	 * @throws IOException if a rule's pattern is malformed
	 */
	private static boolean hasPrefixRule(String rules, String command) throws IOException
	{
		List<String> tokens = Arrays.asList(command.split(" "));
		Matcher matcher = PREFIX_RULE.matcher(rules);
		while (matcher.find())
		{
			List<String> pattern = new ArrayList<>();
			for (JsonNode token : MAPPER.readTree(matcher.group(1)))
				pattern.add(token.stringValue());
			if (tokens.size() >= pattern.size() && tokens.subList(0, pattern.size()).equals(pattern))
				return true;
		}
		return false;
	}

	/**
	 * Returns the shared body of a lifecycle skill.
	 *
	 * @param skill the skill's name
	 * @return the body
	 * @throws IOException if the class path cannot be read
	 */
	private static String skillBody(String skill) throws IOException
	{
		try (InputStream in = resources("skills/" + skill + "/first-use.md").getFirst().openStream())
		{
			return new String(in.readAllBytes(), UTF_8);
		}
	}

	/**
	 * Verifies that each harness's plugin holds the {@code update} and {@code uninstall} skills over one shared body.
	 *
	 * @throws IOException if the class path cannot be read
	 */
	@Test
	public void lifecycleSkillsShareOneBody() throws IOException
	{
		for (String skill : SKILLS)
		{
			List<URL> bodies = resources("skills/" + skill + "/first-use.md");
			requireThat(bodies, skill + "Bodies").size().isEqualTo(1);
			requireThat(bodies.getFirst().toString(), skill + "Body").matches(".*common[-/]skills.*");
			List<String> manifests = new ArrayList<>();
			for (URL manifest : resources("skills/" + skill + "/SKILL.md"))
				manifests.add(manifest.toString().replaceAll(".*(claude|codex)[-/]skills.*", "$1"));
			requireThat(manifests, skill + "Manifests").containsExactly(List.of("claude", "codex"));
		}
	}

	/**
	 * Returns the {@code description} of a skill's frontmatter.
	 *
	 * @param manifest the skill's {@code SKILL.md}
	 * @return the description
	 */
	private static String description(String manifest)
	{
		for (String line : manifest.lines().toList())
		{
			if (line.startsWith("description: "))
				return line.substring("description: ".length());
		}
		throw new AssertionError("No description in:\n" + manifest);
	}

	/**
	 * Writes a JAR that holds the shared skill bodies and the lifecycle skills of one skill module on the class path.
	 *
	 * @param module the module's harness, {@code claude}, {@code codex} or {@code common}
	 * @param jar    the JAR to write
	 * @return {@code jar}
	 * @throws IOException        if the module cannot be read or the JAR cannot be written
	 * @throws URISyntaxException if the class path returns a malformed resource location
	 */
	private static Path skillJar(String module, Path jar) throws IOException, URISyntaxException
	{
		URL skills = null;
		for (URL url : resources("skills"))
		{
			if (url.toString().matches(".*" + module + "[-/]skills.*"))
				skills = url;
		}
		requireThat(skills, "skills").withContext(module, "module").isNotNull();
		URI root = skills.toURI();
		if (!root.getScheme().equals("jar"))
			return writeSkillJar(Path.of(root), jar);
		String location = root.getSchemeSpecificPart();
		try (FileSystem contents = FileSystems.newFileSystem(Path.of(new URI(location.substring(0,
			location.indexOf("!/"))))))
		{
			return writeSkillJar(contents.getPath("/skills"), jar);
		}
	}

	/**
	 * Writes a JAR that holds the shared skill bodies and the lifecycle skills of a {@code skills} tree.
	 *
	 * @param skills the {@code skills} tree
	 * @param jar    the JAR to write
	 * @return {@code jar}
	 * @throws IOException if the tree cannot be read or the JAR cannot be written
	 */
	private static Path writeSkillJar(Path skills, Path jar) throws IOException
	{
		try (OutputStream output = Files.newOutputStream(jar);
			JarOutputStream out = new JarOutputStream(output);
			Stream<Path> files = Files.walk(skills))
		{
			for (Path file : files.filter(Files::isRegularFile).toList())
			{
				String name = skills.relativize(file).toString().replace(file.getFileSystem().getSeparator(), "/");
				if (!name.startsWith("shared-") && SKILLS.stream().noneMatch(skill -> name.startsWith(skill + "/")))
					continue;
				out.putNextEntry(new JarEntry("skills/" + name));
				out.write(Files.readAllBytes(file));
				out.closeEntry();
			}
		}
		return jar;
	}

	/**
	 * Returns every class path location of a resource.
	 *
	 * @param name the resource's name
	 * @return the locations
	 * @throws IOException if the class path cannot be read
	 */
	private static List<URL> resources(String name) throws IOException
	{
		return new ArrayList<>(Collections.list(LifecycleSkillsTest.class.getClassLoader().getResources(name)));
	}
}
