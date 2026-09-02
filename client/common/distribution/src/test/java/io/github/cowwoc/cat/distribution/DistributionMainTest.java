package io.github.cowwoc.cat.distribution;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.TemporaryDirectories;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.testng.annotations.Test;
import tools.jackson.dataformat.yaml.YAMLMapper;

/**
 * Tests {@link DistributionMain}.
 */
public final class DistributionMainTest
{
	/** Creates a test instance. */
	public DistributionMainTest()
	{
	}

	/**
	 * Verifies a prompt-only package update replaces installed model-facing resources without changing runtime files.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test
	public void repackagesPromptResources() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path temporary = cleanup.add(Files.createTempDirectory("prompt-bundle-"));
			Path artifact = Files.createDirectories(temporary.resolve("artifact"));
			Path runtime = Files.createDirectories(artifact.resolve("client/bin"));
			Files.writeString(runtime.resolve("launcher"), "unchanged runtime");
			Files.createDirectories(artifact.resolve("rules/common"));
			Files.writeString(artifact.resolve("rules/common/obsolete.md"), "obsolete rule");
			Files.createDirectories(artifact.resolve("skills/example"));
			Files.writeString(artifact.resolve("skills/example/obsolete.md"), "obsolete skill");
			Files.createDirectories(artifact.resolve("agents/common"));
			Files.writeString(artifact.resolve("agents/common/obsolete.md"), "obsolete agent");

			Path common = Files.createDirectories(temporary.resolve("common"));
			Path commonRules = Files.createDirectories(common.resolve("rules/common"));
			Files.writeString(commonRules.resolve("default.md"), "common route");
			Path commonGuidance = Files.createDirectories(common.resolve("rules/include/common"));
			Files.writeString(commonGuidance.resolve("target.md"), "target guidance");
			Path commonSkill = Files.createDirectories(common.resolve("skills/example"));
			Files.writeString(commonSkill.resolve("first-use.md"), "shared skill body");
			Path commonAgent = Files.createDirectories(common.resolve("agents/common"));
			Files.writeString(commonAgent.resolve("shared.md"), "shared agent body");

			Path harness = Files.createDirectories(temporary.resolve("harness"));
			Path harnessRules = Files.createDirectories(harness.resolve("rules/codex"));
			Files.writeString(harnessRules.resolve("native.md"), "native route");
			Path harnessSkill = Files.createDirectories(harness.resolve("skills/example"));
			Files.writeString(harnessSkill.resolve("SKILL.md"), "<!-- cat:include first-use.md -->");
			Path harnessAgent = Files.createDirectories(harness.resolve("agents/codex"));
			Files.writeString(harnessAgent.resolve("reviewer.md"), "<!-- cat:include ../common/shared.md -->");

			DistributionMain.repackagePromptBundle(artifact, List.of(common, harness), new YAMLMapper());

			requireThat(Files.readString(artifact.resolve("client/bin/launcher")), "runtime").
				isEqualTo("unchanged runtime");
			requireThat(artifact.resolve("rules/common/default.md"), "commonRoute").isRegularFile();
			requireThat(artifact.resolve("rules/include/common/target.md"), "targetGuidance").isRegularFile();
			requireThat(artifact.resolve("rules/codex/native.md"), "nativeRoute").isRegularFile();
			requireThat(Files.exists(artifact.resolve("rules/common/obsolete.md")), "obsoleteRule").isFalse();
			requireThat(Files.readString(artifact.resolve("skills/example/SKILL.md")), "expandedSkill").
				isEqualTo("shared skill body");
			requireThat(Files.exists(artifact.resolve("skills/example/first-use.md")), "sourceOnlySkillBody").isFalse();
			requireThat(Files.readString(artifact.resolve("agents/codex/reviewer.md")), "expandedAgent").
				isEqualTo("shared agent body");
			requireThat(Files.exists(artifact.resolve("agents/common")), "sourceOnlyAgents").isFalse();
			requireThat(Files.exists(artifact.resolve("skills/example/obsolete.md")), "obsoleteSkill").isFalse();
		}
	}

	/**
	 * Verifies a harness's custom agents are one of the source layers for its installed prompt bundle.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test
	public void includesHarnessPluginAgents() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path project = cleanup.add(Files.createTempDirectory("prompt-resource-roots-"));
			Path harnessPluginResources = project.resolve("client/codex/plugin/src/main/resources");
			Path harnessPluginAgents = Files.createDirectories(harnessPluginResources.resolve("agents/codex"));
			Files.writeString(harnessPluginAgents.resolve("reviewer.toml"), "name = \"reviewer\"");

			List<Path> sourceRoots = DistributionMain.promptResourceRoots(project, "codex");

			requireThat(sourceRoots, "sourceRoots").contains(harnessPluginResources);
		}
	}

	/**
	 * Finds the repository root from the test process working directory.
	 *
	 * @return the repository root
	 * @throws IllegalStateException if no ancestor contains the client reactor
	 */
	private static Path findProjectRoot()
	{
		Path candidate = Path.of("").toAbsolutePath().normalize();
		while (candidate != null)
		{
			if (Files.isRegularFile(candidate.resolve("client/pom.xml")))
				return candidate;
			candidate = candidate.getParent();
		}
		throw new IllegalStateException("No ancestor of the test working directory contains client/pom.xml");
	}

	/**
	 * Verifies one native build materializes separate Personal and Commercial repository trees for both harnesses.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test
	public void materializesBothEditionRepositories() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path temporary = cleanup.add(Files.createTempDirectory("edition-repositories-"));
			Path claude = createAssembledArtifact(temporary.resolve("assembled-claude"));
			Path codex = createAssembledArtifact(temporary.resolve("assembled-codex"));

			DistributionMain.materializeReleaseTrees(findProjectRoot(), claude, codex, temporary.resolve("release"),
				io.github.cowwoc.cat.common.Platform.LINUX_X64);

			for (ReleaseManifest.Edition edition : ReleaseManifest.Edition.values())
			{
				Path editionRoot = temporary.resolve("release").resolve(edition.externalValue());
				requireThat(editionRoot.resolve("claude/linux-x86_64"), "claudeTarget").isDirectory();
				requireThat(editionRoot.resolve("codex/linux-x86_64"), "codexTarget").isDirectory();
				requireThat(editionRoot.resolve("LICENSE.md"), "license").isRegularFile();
				requireThat(editionRoot.resolve("README.md"), "readme").isRegularFile();
				requireThat(editionRoot.resolve("release-manifest.json"), "releaseManifest").isRegularFile();
			}
		}
	}

	/**
	 * Verifies an in-tree JDK-style legal alias becomes a regular release input without changing its bytes.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test
	public void normalizesJdkLegalSymlinks() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("assembly-link-"));
			Path legal = Files.createDirectories(root.resolve("client/legal/java.base"));
			Path source = Files.writeString(legal.resolve("LICENSE"), "license text");
			Path aliasDirectory = Files.createDirectories(root.resolve("client/legal/java.xml"));
			Path alias = aliasDirectory.resolve("LICENSE");
			Files.createSymbolicLink(alias, Path.of("../java.base/LICENSE"));

			DistributionMain.normalizeSafeSymlinks(root);

			requireThat(Files.isSymbolicLink(alias), "aliasIsLink").isFalse();
			requireThat(Files.readString(alias), "aliasText").isEqualTo(Files.readString(source));
		}
	}

	/**
	 * Creates a representative assembled artifact for edition-integration tests.
	 *
	 * @param root the artifact root
	 * @return the artifact root
	 * @throws IOException if fixture creation fails
	 */
	private static Path createAssembledArtifact(Path root) throws IOException
	{
		Path launchers = Files.createDirectories(root.resolve("client/bin"));
		Files.writeString(launchers.resolve("prompt-file-check"), "shared");
		for (String launcher : EditionArtifactMaterializer.COMMERCIAL_LAUNCHERS)
			Files.writeString(launchers.resolve(launcher), "commercial");
		Files.createDirectories(root.resolve("skills/prompt-builder"));
		Files.writeString(root.resolve("skills/prompt-builder/SKILL.md"), "shared");
		Files.createDirectories(root.resolve("skills/prompt-optimizer"));
		Files.writeString(root.resolve("skills/prompt-optimizer/SKILL.md"), "commercial");
		Files.createDirectories(root.resolve("skills/prompt-optimizer"));
		Files.writeString(root.resolve("skills/prompt-optimizer/SKILL.md"), "commercial");
		Files.createDirectories(root.resolve("skills/feedback"));
		Files.writeString(root.resolve("skills/feedback/SKILL.md"), "commercial");
		Files.writeString(root.resolve("LICENSE.txt"), "license");
		Files.writeString(root.resolve("NOTICE.txt"), "notice");
		return root;
	}
}
