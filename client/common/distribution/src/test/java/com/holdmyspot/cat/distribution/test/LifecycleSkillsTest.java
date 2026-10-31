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
 * Tests that both plugins hold the {@code update} and {@code uninstall} skills, through which an agent installs another
 * CAT release or removes CAT with the installer that the installed plugin holds.
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
	 * Supplies each harness with the installer files that its skills run from the installed plugin's {@code install/}.
	 *
	 * @return the harness's name and the installer files
	 */
	@DataProvider
	public Object[][] harnesses()
	{
		return new Object[][]{
			{"claude", List.of("cat.sh", "common.sh", "bootstrap.sh", "uninstall-claude.sh")},
			{"codex", List.of("cat.sh", "common.sh", "bootstrap.sh", "uninstall-codex.sh", "install-codex.ps1",
				"bootstrap-codex.ps1")}};
	}

	/**
	 * Verifies that a harness's plugin, assembled from the skill layers, holds the {@code update} and
	 * {@code uninstall} skills with routing descriptions, its own harness's frontmatter and argument, and the shared
	 * body, and that its {@code install/} holds each installer file that the skills run.
	 *
	 * @param harness        the harness whose plugin to assemble
	 * @param installerFiles the installer files that the skills run
	 * @throws IOException        if a skill or installer cannot be read, or the plugin cannot be assembled
	 * @throws URISyntaxException if the class path returns a malformed resource location
	 */
	@Test(dataProvider = "harnesses")
	public void pluginHoldsLifecycleSkills(String harness, List<String> installerFiles)
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
				List.of(installers("common"), installers(harness)), temporary.resolve("plugin"));

			requireThat(description(Files.readString(plugin.resolve("skills/update/SKILL.md"))), "updateDescription").
				startsWith("Use when the user asks").contains("install").contains("update").contains("upgrade");
			requireThat(Files.readString(plugin.resolve("skills/update/first-use.md")), "updateBody").
				contains("bin/plugin-info").contains("releases/latest").contains("install/cat.sh").
				contains("restart or").contains("resume the session");
			requireThat(description(Files.readString(plugin.resolve("skills/uninstall/SKILL.md"))),
				"uninstallDescription").startsWith("Use when the user asks").contains("uninstall").contains("remove");
			requireThat(Files.readString(plugin.resolve("skills/uninstall/first-use.md")), "uninstallBody").
				contains("install/cat.sh").contains("`--uninstall`").contains("`--delete-data` only when the user asks");
			for (String skill : SKILLS)
			{
				String manifest = Files.readString(plugin.resolve("skills/" + skill + "/SKILL.md"));
				requireThat(manifest, skill).contains("--harness " + harness).contains("Read `first-use.md`").
					doesNotContain("cat:include");
				String body = Files.readString(plugin.resolve("skills/" + skill + "/first-use.md"));
				requireThat(body, skill + "Body").contains("status 77").contains("outside the sandbox").
					contains("prefixed with `! `").contains("Do not run it again").doesNotContain("${CAT_PLUGIN_ROOT}");
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
			for (String file : installerFiles)
				requireThat(Files.isRegularFile(plugin.resolve("install").resolve(file)), file).isTrue();
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
	 * Returns a module's source folder of installer scripts.
	 *
	 * @param module the module's harness, {@code claude}, {@code codex} or {@code common}
	 * @return the folder
	 * @throws URISyntaxException if the class path returns a malformed location
	 */
	private static Path installers(String module) throws URISyntaxException
	{
		Path classes = Path.of(LifecycleSkillsTest.class.getProtectionDomain().getCodeSource().getLocation().toURI());
		// target/test-classes is two levels below the module, which is two levels below the client directory
		return classes.resolve("../../../../" + module + "/lifecycle/src/main/resources/install").normalize();
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
