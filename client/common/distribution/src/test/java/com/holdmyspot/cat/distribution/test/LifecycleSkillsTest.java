package com.holdmyspot.cat.distribution.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.PluginInfo;
import com.holdmyspot.cat.common.TemporaryDirectories;
import com.holdmyspot.cat.distribution.bundle.ArtifactLayer;
import com.holdmyspot.cat.distribution.bundle.PluginArtifactBuilder;
import java.io.IOException;
import java.io.OutputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.stream.Stream;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

/**
 * Tests that both plugins hold the {@code update} and {@code uninstall} skills, through which an agent installs,
 * updates and removes CAT only through the installed trusted updater.
 */
public final class LifecycleSkillsTest
{
	/**
	 * The skills that update and remove CAT.
	 */
	private static final List<String> SKILLS = List.of("update", "uninstall");

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
	 * body, which runs only the trusted updater's launcher, never installs through a script of the plugin, and leaves a
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
			Path plugin = new PluginArtifactBuilder(PluginInfo.CAT_VERSION).build(
				List.of(new ArtifactLayer("common-skills", commonSkills),
					new ArtifactLayer(harness + "-skills", harnessSkills)),
				Files.createDirectories(temporary.resolve("runtime")), Set.of(), legal,
				List.of(), temporary.resolve("plugin"));

			requireThat(description(Files.readString(plugin.resolve("skills/update/SKILL.md"))), "updateDescription").
				startsWith("Use when the user asks").contains("install").contains("update").contains("upgrade");
			String updateBody = Files.readString(plugin.resolve("skills/update/first-use.md")).replaceAll("\\s+", " ");
			requireThat(updateBody, "updateBody").contains("cat-updater/bin/cat-updater").
				contains("cat-updater\\bin\\cat-updater.ps1").contains("`<launcher> install --harness <h>`").
				contains("`<launcher> update --harness <h>`").contains("`<launcher> update --harness <h> <tag>`").
				contains("`<launcher> rollback --harness <h>`").
				contains("`powershell.exe -NoProfile -NonInteractive -ExecutionPolicy Bypass -File`").
				contains("asks the user to approve this command").doesNotContain("cat-updater.cmd").
				contains("`<launcher> status --harness <h>`").
				contains("`installed`").contains("`up_to_date`").contains("`refused`").
				contains("rerun the entry command of a newer release").contains("releases/latest/download/cat.sh").
				contains("releases/download/<tag>/cat.sh").contains("cat.ps1").contains("`-Harness <h>`").
				contains("Never run the launcher's `setup` operation yourself").
				contains("restart or resume the session").doesNotContain("--updater-only").
				doesNotContain("upgrade-self").doesNotContain("install/cat.sh").doesNotContain("bootstrap");
			requireThat(description(Files.readString(plugin.resolve("skills/uninstall/SKILL.md"))),
				"uninstallDescription").startsWith("Use when the user asks").contains("uninstall").contains("remove");
			String uninstallBody = Files.readString(plugin.resolve("skills/uninstall/first-use.md")).
				replaceAll("\\s+", " ");
			requireThat(uninstallBody, "uninstallBody").contains("cat-updater/bin/cat-updater").
				contains("cat-updater\\bin\\cat-updater.ps1").contains("Before you run anything, ask the user whether").
				contains("the default is to keep it").contains("`<launcher> uninstall`").
				contains("`<launcher> uninstall --delete-data` only when the user asked to delete it").
				contains("`uninstalled`").contains("`not_removed`").contains("needs the one manual entry command again").
				contains("may ask the user to approve this command").doesNotContain("cat-updater.cmd").
				doesNotContain("install/").doesNotContain("uninstall-").doesNotContain("-Uninstall").
				doesNotContain("-DeleteData").doesNotContain("cat.sh").doesNotContain("XDG_CONFIG_HOME");
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
