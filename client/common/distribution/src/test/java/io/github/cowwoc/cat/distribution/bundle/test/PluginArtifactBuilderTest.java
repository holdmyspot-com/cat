package io.github.cowwoc.cat.distribution.bundle.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.that;
import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.nio.file.attribute.PosixFilePermission.GROUP_EXECUTE;
import static java.nio.file.attribute.PosixFilePermission.OTHERS_EXECUTE;
import static java.nio.file.attribute.PosixFilePermission.OWNER_EXECUTE;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.List;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import io.github.cowwoc.cat.common.PluginInfo;
import io.github.cowwoc.cat.common.TemporaryDirectories;
import io.github.cowwoc.cat.common.ToolScope;
import io.github.cowwoc.cat.distribution.bundle.ArtifactLayer;
import io.github.cowwoc.cat.distribution.bundle.PluginArtifactBuilder;
import io.github.cowwoc.cat.distribution.bundle.RuleArtifactRouter;
import org.testng.annotations.Test;

/**
 * Tests {@link PluginArtifactBuilder}.
 */
public final class PluginArtifactBuilderTest
{
	/** Creates a test instance. */
	public PluginArtifactBuilderTest()
	{
	}

	/**
	 * Verifies resources, runtime files, generated legal materials, and installer scripts are assembled.
	 */
	@Test
	public void assemblesCompleteArtifact() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path temporary = cleanup.add(Files.createTempDirectory("cat-artifact-"));
			Path common = jar(temporary.resolve("common.jar"), "common.txt", "common");
			Path harness = jar(temporary.resolve("harness.jar"), "skills/help/SKILL.md", "harness");
			Path runtime = Files.createDirectories(temporary.resolve("runtime/bin")).getParent();
			Files.writeString(runtime.resolve("bin/plugin-info"), "launcher");
			Path legal = legalMaterials(temporary);
			Path installers = Files.createDirectories(temporary.resolve("installers"));
			Files.writeString(installers.resolve("install-claude.sh"), "claude installer");
			Files.writeString(installers.resolve("install-codex.sh"), "codex installer");
			Files.writeString(installers.resolve("common.sh"), "shared installer functions");
			Files.writeString(installers.resolve("uninstall-claude.sh"), "claude uninstaller");
			Files.writeString(installers.resolve("uninstall-codex.sh"), "codex uninstaller");

			Path output = new PluginArtifactBuilder(PluginInfo.CAT_VERSION).build(
				List.of(new ArtifactLayer("common", common),
					new ArtifactLayer("harness", harness, Set.of("skills/help/SKILL.md"))), runtime, legal,
				List.of(installers),
				temporary.resolve("output"));

			requireThat(Files.readString(output.resolve("skills/help/SKILL.md")), "harnessSkill").isEqualTo("harness");
			requireThat(Files.readString(output.resolve("client/VERSION")), "versionFile").isEqualTo(
				PluginInfo.CAT_VERSION + "\n");
			requireThat(Files.readString(output.resolve("client/bin/plugin-info")), "pluginInfoLauncher").isEqualTo(
				"launcher");
			requireThat(Files.readString(output.resolve("LICENSE.txt")), "license").isEqualTo("license");
			requireThat(Files.readString(output.resolve("NOTICE.txt")), "notice").isEqualTo("notice");
			requireThat(Files.exists(output.resolve("LICENSE.md")), "repositoryLicense").isFalse();
			requireThat(Files.readString(output.resolve("licenses/Apache-2.0.txt")), "apacheLicense").isEqualTo("apache");
			requireThat(Files.readString(output.resolve("licenses/MIT.txt")), "mitLicense").isEqualTo("mit");
			requireThat(Files.exists(output.resolve("docs")), "pricingDocumentation").isFalse();
			requireThat(Files.readString(output.resolve("install/install-claude.sh")), "claudeInstaller").
				isEqualTo("claude installer");
			requireThat(Files.readString(output.resolve("install/install-codex.sh")), "codexInstaller").
				isEqualTo("codex installer");
			requireThat(Files.readString(output.resolve("install/common.sh")), "commonInstallerFunctions").
				isEqualTo("shared installer functions");
			requireThat(Files.readString(output.resolve("install/uninstall-claude.sh")), "claudeUninstaller").
				isEqualTo("claude uninstaller");
			requireThat(Files.readString(output.resolve("install/uninstall-codex.sh")), "codexUninstaller").
				isEqualTo("codex uninstaller");
		}
	}

	/**
	 * Verifies each harness-specific skill header is combined with its shared body before the artifact is installed.
	 */
	@Test
	public void assemblesHarnessSpecificSkillHeaders() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path temporary = cleanup.add(Files.createTempDirectory("cat-skill-assembly-"));
			Path common = jar(temporary.resolve("common.jar"),
				"skills/shared-first-use-route.md", """
					If you have not already read this skill's `first-use.md` in the current
					conversation context, read it now and follow it exactly. Otherwise, reuse its guidance.
					Read `first-use.md` again after the context resets or is compacted.
					""");
			Path claude = jar(temporary.resolve("claude.jar"), List.of(
				new JarResource("skills/example/SKILL.md", "---\ndescription: Shared skill\nallowed-tools: [Bash]\n---\n\n" +
					"<!-- cat:include ../shared-first-use-route.md -->\n")));
			Path codex = jar(temporary.resolve("codex.jar"), List.of(
				new JarResource("skills/example/SKILL.md", "---\ndescription: Shared skill\n---\n\n" +
					"<!-- cat:include ../shared-first-use-route.md -->\n")));

			Path claudeArtifact = buildMinimal(temporary.resolve("claude"), List.of(new ArtifactLayer("common", common),
				new ArtifactLayer("claude", claude, Set.of("skills/example/SKILL.md"))));
			Path codexArtifact = buildMinimal(temporary.resolve("codex"), List.of(new ArtifactLayer("common", common),
				new ArtifactLayer("codex", codex, Set.of("skills/example/SKILL.md"))));

			requireThat(Files.readString(claudeArtifact.resolve("skills/example/SKILL.md")), "claudeSkill").
				contains("allowed-tools: [Bash]").contains("Read `first-use.md`").doesNotContain("cat:include");
			requireThat(Files.readString(codexArtifact.resolve("skills/example/SKILL.md")), "codexSkill").
				contains("description: Shared skill").contains("Read `first-use.md`").
				doesNotContain("allowed-tools").doesNotContain("cat:include");
			requireThat(Files.exists(claudeArtifact.resolve("skills/shared-first-use-route.md")), "claudeSourceOnlyBody").
				isFalse();
			requireThat(Files.exists(codexArtifact.resolve("skills/shared-first-use-route.md")), "codexSourceOnlyBody").
				isFalse();
		}
	}

	/**
	 * Verifies agents can include an authoritative packaged rule without retaining the source-only agent body.
	 */
	@Test
	public void assemblesAgentRuleReferences() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path temporary = cleanup.add(Files.createTempDirectory("cat-agent-rule-assembly-"));
			Path common = jar(temporary.resolve("common.jar"), List.of(
				new JarResource("agents/common/review.md", "Follow:\n<!-- cat:include ../../rules/common/audience.md -->\n"),
				new JarResource("rules/common/audience.md", "the authoritative language rule\\n")));
			Path codex = jar(temporary.resolve("codex.jar"),
				"agents/codex/reviewer.toml", "<!-- cat:include ../common/review.md -->\n");

			Path artifact = buildMinimal(temporary, List.of(new ArtifactLayer("common", common),
				new ArtifactLayer("codex", codex)));

			requireThat(Files.readString(artifact.resolve("agents/codex/reviewer.toml")), "reviewer").
				contains("Follow:\nthe authoritative language rule").doesNotContain("cat:include");
			requireThat(Files.exists(artifact.resolve("agents/common/review.md")), "sourceOnlyAgentBody").isFalse();
			requireThat(Files.readString(artifact.resolve("rules/common/audience.md")), "authoritativeRule").
			isEqualTo("the authoritative language rule\\n");
		}
	}

	/**
	 * Verifies declared executable resources remain executable after layer extraction.
	 */
	@Test
	public void preservesDeclaredExecutableResource() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path temporary = cleanup.add(Files.createTempDirectory("cat-executable-resource-"));
			Path plugin = jar(temporary.resolve("plugin.jar"), "hooks/session-start.sh", "#!/usr/bin/env sh\n");
			Path artifact = buildMinimal(temporary, List.of(new ArtifactLayer("plugin", plugin, Set.of(),
				Set.of("hooks/session-start.sh"))));

			Set<PosixFilePermission> permissions = Files.getPosixFilePermissions(artifact.resolve(
				"hooks/session-start.sh"));
			requireThat(permissions, "permissions").contains(OWNER_EXECUTE).contains(GROUP_EXECUTE).
				contains(OTHERS_EXECUTE);
		}
	}

	/**
	 * Verifies undeclared resource collisions are rejected.
	 */
	@Test
	public void rejectsUndeclaredResourceCollision() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path temporary = cleanup.add(Files.createTempDirectory("cat-collision-"));
			Path first = jar(temporary.resolve("first.jar"), "skills/help/SKILL.md", "first");
			Path second = jar(temporary.resolve("second.jar"), "skills/help/SKILL.md", "second");
			try
			{
				buildMinimal(temporary, List.of(new ArtifactLayer("first", first), new ArtifactLayer("second", second)));
				throw new AssertionError("Expected resource collision");
			}
			catch (IOException failure)
			{
				requireThat(failure.getMessage(), "failureMessage").contains("skills/help/SKILL.md").contains("second");
			}
		}
	}

	/**
	 * Verifies parent-directory traversal entries identify the layer, invalid entry, reason, and corrective action.
	 */
	@Test
	public void rejectsTraversalEntryClearly() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path temporary = cleanup.add(Files.createTempDirectory("cat-traversal-"));
			Path malicious = jar(temporary.resolve("malicious.jar"), "../escaped", "bad");
			try
			{
				buildMinimal(temporary, List.of(new ArtifactLayer("malicious", malicious)));
				throw new AssertionError("Expected invalid JAR entry");
			}
			catch (IOException failure)
			{
				requireThat(failure.getMessage(), "failureMessage").contains("Resource layer \"malicious\"").
					contains("\"../escaped\"").contains("must not contain \"..\"").contains("Rebuild or replace");
			}
		}
	}

	/**
	 * Verifies normalized path aliases identify the rejected traversal segment.
	 */
	@Test
	public void rejectsNormalizedAliasEntryClearly() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path temporary = cleanup.add(Files.createTempDirectory("cat-alias-"));
			Path malicious = jar(temporary.resolve("malicious.jar"), "alias/../skills/help/SKILL.md", "bad");
			try
			{
				buildMinimal(temporary, List.of(new ArtifactLayer("malicious", malicious)));
				throw new AssertionError("Expected invalid JAR entry");
			}
			catch (IOException failure)
			{
				requireThat(failure.getMessage(), "failureMessage").contains("Resource layer \"malicious\"").
					contains("\"alias/../skills/help/SKILL.md\"").contains("must not contain \"..\"").
					contains("Rebuild or replace");
			}
		}
	}

	/**
	 * Verifies absolute JAR entries explain that extraction is limited to the plugin directory.
	 */
	@Test
	public void rejectsAbsoluteEntryClearly() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path temporary = cleanup.add(Files.createTempDirectory("cat-absolute-entry-"));
			Path malicious = jar(temporary.resolve("malicious.jar"), "/escaped", "bad");
			assertInvalidEntry(malicious, "/escaped", "must be relative to the plugin directory");
		}
	}

	/**
	 * Verifies backslash-separated JAR entries explain the required separator.
	 */
	@Test
	public void rejectsBackslashEntryClearly() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path temporary = cleanup.add(Files.createTempDirectory("cat-backslash-entry-"));
			Path malicious = jar(temporary.resolve("malicious.jar"), "skills\\help\\SKILL.md", "bad");
			assertInvalidEntry(malicious, "skills\\help\\SKILL.md", "must use forward slashes");
		}
	}

	/**
	 * Verifies JAR entries with an empty path segment explain the rejected form.
	 */
	@Test
	public void rejectsEmptySegmentClearly() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path temporary = cleanup.add(Files.createTempDirectory("cat-empty-segment-"));
			Path malicious = jar(temporary.resolve("malicious.jar"), "skills//help/SKILL.md", "bad");
			assertInvalidEntry(malicious, "skills//help/SKILL.md", "must not contain an empty path segment");
		}
	}

	/**
	 * Verifies common and harness-specific rule layers remain isolated.
	 */
	@Test
	public void keepsHarnessRulesSeparate() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path temporary = cleanup.add(Files.createTempDirectory("cat-harness-rules-"));
			Path common = jar(temporary.resolve("common-rules.jar"), "rules/common/default.md", "common");
			Path claude = jar(temporary.resolve("claude-rules.jar"), "rules/claude/default.md", "claude");
			Path codex = jar(temporary.resolve("codex-rules.jar"), "rules/codex/default.md", "codex");

			Path claudeArtifact = buildMinimal(temporary.resolve("claude"),
				List.of(new ArtifactLayer("common-rules", common), new ArtifactLayer("claude-rules", claude)));
			Path codexArtifact = buildMinimal(temporary.resolve("codex"),
				List.of(new ArtifactLayer("common-rules", common), new ArtifactLayer("codex-rules", codex)));

			requireThat(claudeArtifact.resolve("rules/common/default.md"), "claudeCommonRule").isRegularFile();
			requireThat(claudeArtifact.resolve("rules/claude/default.md"), "claudeRule").isRegularFile();
			requireThat(claudeArtifact.resolve("rules"), "claudeRules").doesNotContain(Path.of("codex"));
			requireThat(codexArtifact.resolve("rules/common/default.md"), "codexCommonRule").isRegularFile();
			requireThat(codexArtifact.resolve("rules/codex/default.md"), "codexRule").isRegularFile();
			requireThat(codexArtifact.resolve("rules"), "codexRules").doesNotContain(Path.of("claude"));
		}
	}

	/**
	 * Verifies a bundled lazy-rule prerequisite is indexed relative to the injected common rule directory, so the rule
	 * loader can resolve both paths after direct context loses its source location.
	 */
	@Test
	public void routesBundledLazyDependencies() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories(); ToolScope scope = new ToolScope())
		{
			Path artifact = cleanup.add(Files.createTempDirectory("cat-lazy-rule-artifact-"));
			Path common = Files.createDirectories(artifact.resolve("rules/common"));
			Path include = Files.createDirectories(artifact.resolve("rules/include/java"));
			Files.writeString(common.resolve("default-rules.md"), "Default rules\n");
			Files.writeString(include.resolve("javadoc.md"), "---\ndepends-on: [\"../documentation/general.md\"]\n---\n" +
				"Javadoc rules\n");
			Files.createDirectories(artifact.resolve("rules/include/documentation"));
			Files.writeString(artifact.resolve("rules/include/documentation/general.md"), "Documentation rules\n");

			RuleArtifactRouter.route(artifact, scope.yaml());

			String index = Files.readString(common.resolve(".cat-lazy-rule-dependencies.md"));
			requireThat(index, "index").contains("`../include/java/javadoc.md`").
				contains("`../include/documentation/general.md`").doesNotContain("`rules/include/");
			requireThat(Files.readString(include.resolve("javadoc.md")), "javadocRule").
				doesNotContain("depends-on");
		}
	}

	/**
	 * Verifies that routing rejects a packaged rule whose frontmatter declares a key that rules do not define.
	 *
	 * @throws IOException for the expected rejection
	 */
	@Test(expectedExceptions = IOException.class, expectedExceptionsMessageRegExp =
		".*Unknown frontmatter key depends_on.*supports agents, depends-on and paths.*")
	public void rejectsUnknownRuleFrontmatterKey() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories(); ToolScope scope = new ToolScope())
		{
			Path artifact = cleanup.add(Files.createTempDirectory("cat-lazy-rule-artifact-"));
			Path common = Files.createDirectories(artifact.resolve("rules/common"));
			Path include = Files.createDirectories(artifact.resolve("rules/include/java"));
			Files.writeString(common.resolve("default-rules.md"), "Default rules\n");
			Files.writeString(include.resolve("javadoc.md"), "---\ndepends_on: [\"../documentation/general.md\"]\n" +
				"---\nJavadoc rules\n");
			Files.createDirectories(artifact.resolve("rules/include/documentation"));
			Files.writeString(artifact.resolve("rules/include/documentation/general.md"), "Documentation rules\n");

			RuleArtifactRouter.route(artifact, scope.yaml());
		}
	}

	/**
	 * Builds an artifact using the smallest complete set of fixture inputs.
	 *
	 * @param temporary the fixture directory
	 * @param layers the resource layers to apply
	 * @return the artifact output directory
	 * @throws IOException if a fixture or artifact cannot be created
	 */
	private static Path buildMinimal(Path temporary, List<ArtifactLayer> layers) throws IOException
	{
		assert that(temporary, "temporary").isNotNull().elseThrow();
		assert that(layers, "layers").isNotNull().elseThrow();
		Path runtime = Files.createDirectories(temporary.resolve("runtime"));
		Path legal = legalMaterials(temporary);
		Path installers = Files.createDirectories(temporary.resolve("installers"));
		return new PluginArtifactBuilder(PluginInfo.CAT_VERSION).build(layers, runtime, legal, List.of(installers),
			temporary.resolve("output"));
	}

	/**
	 * Verifies a JAR is rejected with the supplied reason and an actionable recovery instruction.
	 *
	 * @param jar the JAR containing an invalid entry
	 * @param entryName the invalid JAR entry name
	 * @param reason the expected explanation of the invalid form
	 * @throws IOException if the fixture cannot be assembled
	 */
	private static void assertInvalidEntry(Path jar, String entryName, String reason) throws IOException
	{
		try
		{
			buildMinimal(jar.getParent(), List.of(new ArtifactLayer("malicious", jar)));
			throw new AssertionError("Expected invalid JAR entry");
		}
		catch (IOException failure)
		{
			requireThat(failure.getMessage(), "failureMessage").contains("Resource layer \"malicious\"").
				contains("\"" + entryName + "\"").contains(reason).contains("Rebuild or replace");
		}
	}

	/**
	 * Creates the smallest complete legal-material fixture.
	 *
	 * @param temporary the fixture directory
	 * @return the generated legal-material directory
	 * @throws IOException if the fixture cannot be created
	 */
	private static Path legalMaterials(Path temporary) throws IOException
	{
		Path legal = Files.createDirectories(temporary.resolve("legal"));
		Files.writeString(legal.resolve("LICENSE.txt"), "license");
		Files.writeString(legal.resolve("NOTICE.txt"), "notice");
		Path licenses = Files.createDirectories(legal.resolve("licenses"));
		Files.writeString(licenses.resolve("Apache-2.0.txt"), "apache");
		Files.writeString(licenses.resolve("MIT.txt"), "mit");
		return legal;
	}

	/**
	 * Creates a JAR containing one resource entry.
	 *
	 * @param path the JAR path
	 * @param name the resource-entry name
	 * @param content the resource content
	 * @return the created JAR path
	 * @throws IOException if the JAR cannot be written
	 */
	private static Path jar(Path path, String name, String content) throws IOException
	{
		return jar(path, List.of(new JarResource(name, content)));
	}

	/**
	 * Creates a JAR containing the supplied resource entries.
	 *
	 * @param path the JAR path
	 * @param resources the resource entries
	 * @return the created JAR path
	 * @throws IOException if the JAR cannot be written
	 */
	private static Path jar(Path path, List<JarResource> resources) throws IOException
	{
		assert that(path, "path").isNotNull().elseThrow();
		assert that(resources, "resources").isNotEmpty().elseThrow();
		try (OutputStream output = Files.newOutputStream(path);
			JarOutputStream jar = new JarOutputStream(output))
		{
			jar.putNextEntry(new JarEntry("META-INF/MANIFEST.MF"));
			jar.write("Manifest-Version: 1.0\n".getBytes(UTF_8));
			jar.closeEntry();
			for (JarResource resource : resources)
			{
				jar.putNextEntry(new JarEntry(resource.name()));
				jar.write(resource.content().getBytes(UTF_8));
				jar.closeEntry();
			}
		}
		return path;
	}

	/**
	 * A resource entry used to create a fixture JAR.
	 *
	 * @param name the entry name
	 * @param content the entry content
	 */
	private record JarResource(String name, String content)
	{
	}
}
