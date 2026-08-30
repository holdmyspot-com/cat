package io.github.cowwoc.cat.distribution;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static java.nio.file.LinkOption.NOFOLLOW_LINKS;
import static java.nio.file.StandardCopyOption.ATOMIC_MOVE;

import io.github.cowwoc.cat.common.Platform;
import io.github.cowwoc.cat.common.PluginInfo;
import io.github.cowwoc.cat.common.ProjectRuleLoader;
import io.github.cowwoc.cat.common.ToolScope;
import io.github.cowwoc.cat.distribution.bundle.ArtifactLayer;
import io.github.cowwoc.cat.distribution.bundle.JlinkImageBuilder;
import io.github.cowwoc.cat.distribution.bundle.ModuleDescriptorPatcher;
import io.github.cowwoc.cat.distribution.bundle.PluginArtifactBuilder;
import io.github.cowwoc.cat.distribution.bundle.ReleasePackager;
import io.github.cowwoc.cat.distribution.bundle.RuleArtifactRouter;
import io.github.cowwoc.cat.distribution.license.LegalMaterialsBuilder;
import io.github.cowwoc.cat.distribution.license.ThirdPartyComponent;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import tools.jackson.dataformat.yaml.YAMLMapper;

/**
 * Builds the platform-specific Claude and Codex CAT release archives from Maven module outputs.
 */
public final class DistributionMain
{
	/**
	 * Builds distributions for the current platform.
	 *
	 * @param args the project root, module directory, and release directory
	 * @throws NullPointerException if {@code args} is null
	 * @throws IllegalArgumentException if {@code args} does not contain exactly three values
	 * @throws IOException if distribution assembly fails
	 */
	public static void main(String[] args) throws IOException
	{
		requireThat(args, "args").isNotNull();
		if (args.length == 4 && args[0].equals("repackage-prompt-bundle"))
		{
			Path projectRoot = Path.of(args[1]);
			Path artifact = Path.of(args[2]);
			String harness = args[3];
			repackagePromptBundle(projectRoot, artifact, harness);
			return;
		}
		if (args.length != 3)
		{
			throw new IllegalArgumentException(
				"Usage: DistributionMain <project-root> <module-directory> <release-directory> or " +
					"DistributionMain repackage-prompt-bundle <project-root> <artifact-directory> <harness>");
		}
		build(Path.of(args[0]), Path.of(args[1]), Path.of(args[2]), Platform.current());
	}

	/**
	 * Repackages one installed artifact's model-facing prompt bundle from the current version-controlled sources.
	 *
	 * <p>
	 * The existing Java runtime is not copied, rebuilt, or modified. Fresh staged rules, skills, and agent
	 * instructions replace the prior prompt bundle only after source-only includes and direct rule routes have been
	 * resolved successfully.
	 *
	 * @param projectRoot the repository that owns the rule source trees
	 * @param artifact the existing harness artifact whose prompt resources are updated
	 * @param harness the selected harness name
	 * @throws IOException if a source tree, artifact, or generated rule route is invalid
	 */
	private static void repackagePromptBundle(Path projectRoot, Path artifact, String harness) throws IOException
	{
		requireThat(projectRoot, "projectRoot").isNotNull();
		requireThat(artifact, "artifact").isNotNull();
		requireThat(harness, "harness").isNotBlank();
		projectRoot = projectRoot.toAbsolutePath().normalize();
		artifact = artifact.toAbsolutePath().normalize();
		repackagePromptBundle(artifact, promptResourceRoots(projectRoot, harness), new YAMLMapper());
		new ProjectRuleLoader(new YAMLMapper()).loadForMainAgent(artifact, projectRoot, harness);
	}

	/**
	 * Returns the ordered source layers for one harness's installed prompt bundle.
	 *
	 * @param projectRoot the repository that owns the source layers
	 * @param harness the harness whose prompt resources will be installed
	 * @return the common layers followed by the selected harness layers
	 */
	static List<Path> promptResourceRoots(Path projectRoot, String harness)
	{
		requireThat(projectRoot, "projectRoot").isNotNull();
		requireThat(harness, "harness").isNotBlank();
		projectRoot = projectRoot.toAbsolutePath().normalize();
		Path harnessRoot = projectRoot.resolve("client").resolve(harness);
		return List.of(
			projectRoot.resolve("client/common/agents/src/main/resources"),
			projectRoot.resolve("client/common/skills/src/main/resources"),
			projectRoot.resolve("client/common/rules/src/main/resources"),
			harnessRoot.resolve("plugin/src/main/resources"),
			harnessRoot.resolve("skills/src/main/resources"),
			harnessRoot.resolve("rules/src/main/resources"));
	}

	/**
	 * Replaces an artifact's packaged model-facing resources from ordered source layers without changing its runtime
	 * files.
	 *
	 * @param artifact the existing harness artifact whose prompt resources are updated
	 * @param sourceRoots ordered resource directories whose {@code agents}, {@code skills}, and {@code rules} contents
	 *        are layered into the new prompt bundle
	 * @param yamlMapper the YAML parser used to generate packaged routes
	 * @throws IOException if prompt-resource layers collide, cannot be assembled, or cannot replace the prior bundle
	 */
	static void repackagePromptBundle(Path artifact, List<Path> sourceRoots, YAMLMapper yamlMapper) throws IOException
	{
		requireThat(artifact, "artifact").isNotNull();
		requireThat(sourceRoots, "sourceRoots").isNotNull();
		requireThat(yamlMapper, "yamlMapper").isNotNull();
		artifact = artifact.toAbsolutePath().normalize();
		requireThat(artifact, "artifact").isDirectory();
		if (sourceRoots.isEmpty())
			throw new IllegalArgumentException("Prompt source roots must not be empty");

		Path temporary = Files.createTempDirectory(artifact.getParent(), "cat-prompt-bundle-");
		try
		{
			Path stagedArtifact = temporary.resolve("artifact");
			for (Path sourceRoot : sourceRoots)
				copyPromptResources(sourceRoot, stagedArtifact);
			PluginArtifactBuilder.resolvePromptIncludes(stagedArtifact);
			RuleArtifactRouter.route(stagedArtifact, yamlMapper);
			replacePromptBundle(artifact, stagedArtifact);
		}
		finally
		{
			deleteTree(temporary);
		}
	}

	/**
	 * Builds the harness distributions for a platform.
	 *
	 * @param projectRoot the repository root
	 * @param moduleDirectory the directory containing dependency JARs
	 * @param releaseDirectory the output directory for release archives
	 * @param platform the target platform
	 * @throws NullPointerException if {@code projectRoot}, {@code moduleDirectory}, {@code releaseDirectory},
	 *         or {@code platform} are null
	 * @throws IllegalArgumentException if {@code releaseDirectory} is the filesystem root
	 * @throws IOException if distribution assembly fails
	 */
	public static void build(Path projectRoot, Path moduleDirectory, Path releaseDirectory, Platform platform)
		throws IOException
	{
		requireThat(projectRoot, "projectRoot").isNotNull();
		requireThat(moduleDirectory, "moduleDirectory").isNotNull();
		requireThat(releaseDirectory, "releaseDirectory").isNotNull();
		requireThat(platform, "platform").isNotNull();
		projectRoot = projectRoot.toAbsolutePath().normalize();
		moduleDirectory = moduleDirectory.toAbsolutePath().normalize();
		releaseDirectory = releaseDirectory.toAbsolutePath().normalize();
		requireThat(moduleDirectory, "moduleDirectory").isDirectory();
		requireThat(releaseDirectory, "releaseDirectory").isNotEqualTo(releaseDirectory.getRoot(), "filesystemRoot");

		Path target = releaseDirectory.getParent();
		Path runtimeDirectory = target.resolve("runtime");
		Path jlinkDirectory = target.resolve("jlink");
		Path jlinkModules = target.resolve("jlink-modules");
		deleteAndRecreateDirectory(releaseDirectory);
		deleteAndRecreateDirectory(runtimeDirectory);
		deleteAndRecreateDirectory(jlinkDirectory);
		deleteAndRecreateDirectory(jlinkModules);

		Path commonCli = findDependencyJar(moduleDirectory, "client-common-cli");
		Path commonEvaluation = findDependencyJar(moduleDirectory, "client-common-evaluation");
		Path claudeCli = findDependencyJar(moduleDirectory, "client-claude-cli");
		Path codexCli = findDependencyJar(moduleDirectory, "client-codex-cli");
		Path requirements = findDependencyJar(moduleDirectory, "requirements-java");
		Path requirementsAnnotation = findDependencyJar(moduleDirectory, "requirements-annotation");
		Path pouch = findDependencyJar(moduleDirectory, "pouch-core");
		Path slf4j = findDependencyJar(moduleDirectory, "slf4j-api");
		Path slf4jNop = findDependencyJar(moduleDirectory, "slf4j-nop");
		Path javaDiff = findDependencyJar(moduleDirectory, "java-diff-utils");
		Path commonAgents = findDependencyJar(moduleDirectory, "client-common-agents");
		Path commonPlugin = findDependencyJar(moduleDirectory, "client-common-plugin");
		Path commonSkills = findDependencyJar(moduleDirectory, "client-common-skills");
		Path commonRules = findDependencyJar(moduleDirectory, "client-common-rules");
		Path claudePlugin = findDependencyJar(moduleDirectory, "client-claude-plugin");
		Path claudeRules = findDependencyJar(moduleDirectory, "client-claude-rules");
		Path claudeSkills = findDependencyJar(moduleDirectory, "client-claude-skills");
		Path codexSkills = findDependencyJar(moduleDirectory, "client-codex-skills");
		Path codexPlugin = findDependencyJar(moduleDirectory, "client-codex-plugin");
		Path codexRules = findDependencyJar(moduleDirectory, "client-codex-rules");
		Path jacksonCore = findDependencyJar(moduleDirectory, "jackson-core");
		Path jacksonAnnotations = findDependencyJar(moduleDirectory, "jackson-annotations");
		Path jacksonDatabind = findDependencyJar(moduleDirectory, "jackson-databind");
		Path jacksonYaml = findDependencyJar(moduleDirectory, "jackson-dataformat-yaml");
		Path snakeyaml = findDependencyJar(moduleDirectory, "snakeyaml-engine");
		Path commonmark = findDependencyJar(moduleDirectory, "commonmark");
		Path testng = findDependencyJar(moduleDirectory, "testng");

		List<Path> sharedRuntimeModules = List.of(copyModule(commonCli, jlinkModules), copyModule(commonEvaluation,
			jlinkModules),
			copyModule(requirements, jlinkModules), copyModule(requirementsAnnotation, jlinkModules),
			copyModule(pouch, jlinkModules), copyModule(slf4j, jlinkModules), copyModule(slf4jNop, jlinkModules),
			modularizeJavaDiff(javaDiff, jlinkModules), copyModule(commonmark, jlinkModules),
			modularizeTestNg(testng, jlinkModules));
		Path packagedClaudeCli = copyModule(claudeCli, jlinkModules);
		Path packagedCodexCli = copyModule(codexCli, jlinkModules);
		List<Path> jsonRuntimeModules = List.of(copyModule(jacksonCore, jlinkModules), copyModule(jacksonAnnotations,
			jlinkModules),
			copyModule(jacksonDatabind, jlinkModules), copyModule(jacksonYaml, jlinkModules), copyModule(snakeyaml,
				jlinkModules));

		JlinkImageBuilder imageBuilder = new JlinkImageBuilder();
		Path claudeImage = imageBuilder.build(append(append(sharedRuntimeModules, packagedClaudeCli), jsonRuntimeModules),
			Set.of("io.github.cowwoc.cat.claude.cli", "org.slf4j.nop"),
			List.of(
				new JlinkImageBuilder.Launcher("plugin-info", "io.github.cowwoc.cat.claude.cli",
					"io.github.cowwoc.cat.claude.ClaudePluginInfo"),
				new JlinkImageBuilder.Launcher("claude-runner", "io.github.cowwoc.cat.claude.cli",
					"io.github.cowwoc.cat.claude.runner.ClaudeRunnerCli"),
				new JlinkImageBuilder.Launcher("git-topic-replay", "io.github.cowwoc.cat.common.cli",
					"io.github.cowwoc.cat.common.git.replay.GitTopicReplayEditor"),
				new JlinkImageBuilder.Launcher("prompt-file-check", "io.github.cowwoc.cat.common.cli",
					"io.github.cowwoc.cat.common.PromptFileCheck"),
				new JlinkImageBuilder.Launcher("source-documentation-inventory", "io.github.cowwoc.cat.common.cli",
					"io.github.cowwoc.cat.common.SourceDocumentationInventory"),
				new JlinkImageBuilder.Launcher("prompt-evidence-check", "io.github.cowwoc.cat.common.cli",
					"io.github.cowwoc.cat.common.PromptEvidenceCheck"),
				new JlinkImageBuilder.Launcher("prompt-evaluation", "io.github.cowwoc.cat.common.cli",
					"io.github.cowwoc.cat.common.PromptEvaluation"),
				new JlinkImageBuilder.Launcher("workflow-temp", "io.github.cowwoc.cat.common.cli",
					"io.github.cowwoc.cat.common.InvocationDirectory"),
				new JlinkImageBuilder.Launcher("session-analyzer", "io.github.cowwoc.cat.common.cli",
					"io.github.cowwoc.cat.common.history.SessionHistory"),
				new JlinkImageBuilder.Launcher("get-history", "io.github.cowwoc.cat.claude.cli",
					"io.github.cowwoc.cat.claude.ClaudeGetHistory"),
				new JlinkImageBuilder.Launcher("session-start", "io.github.cowwoc.cat.claude.cli",
					"io.github.cowwoc.cat.claude.ClaudeSessionStartHook"),
				new JlinkImageBuilder.Launcher("session-end", "io.github.cowwoc.cat.claude.cli",
					"io.github.cowwoc.cat.claude.ClaudeSessionEndHook"),
				new JlinkImageBuilder.Launcher("subagent-start", "io.github.cowwoc.cat.claude.cli",
					"io.github.cowwoc.cat.claude.ClaudeSubagentStartHook")), jlinkDirectory.resolve("claude"));
		List<Path> codexModules = append(append(sharedRuntimeModules, packagedCodexCli), jsonRuntimeModules);
		Path codexImage = imageBuilder.build(codexModules,
			Set.of("io.github.cowwoc.cat.codex.cli", "org.slf4j.nop"),
			List.of(
				new JlinkImageBuilder.Launcher("plugin-info", "io.github.cowwoc.cat.codex.cli",
					"io.github.cowwoc.cat.codex.CodexPluginInfo"),
				new JlinkImageBuilder.Launcher("codex-runner", "io.github.cowwoc.cat.codex.cli",
					"io.github.cowwoc.cat.codex.runner.CodexRunnerCli"),
				new JlinkImageBuilder.Launcher("git-topic-replay", "io.github.cowwoc.cat.common.cli",
					"io.github.cowwoc.cat.common.git.replay.GitTopicReplayEditor"),
				new JlinkImageBuilder.Launcher("prompt-file-check", "io.github.cowwoc.cat.common.cli",
					"io.github.cowwoc.cat.common.PromptFileCheck"),
				new JlinkImageBuilder.Launcher("source-documentation-inventory", "io.github.cowwoc.cat.common.cli",
					"io.github.cowwoc.cat.common.SourceDocumentationInventory"),
				new JlinkImageBuilder.Launcher("prompt-evidence-check", "io.github.cowwoc.cat.common.cli",
					"io.github.cowwoc.cat.common.PromptEvidenceCheck"),
				new JlinkImageBuilder.Launcher("prompt-evaluation", "io.github.cowwoc.cat.common.cli",
					"io.github.cowwoc.cat.common.PromptEvaluation"),
				new JlinkImageBuilder.Launcher("workflow-temp", "io.github.cowwoc.cat.common.cli",
					"io.github.cowwoc.cat.common.InvocationDirectory"),
				new JlinkImageBuilder.Launcher("session-analyzer", "io.github.cowwoc.cat.common.cli",
					"io.github.cowwoc.cat.common.history.SessionHistory"),
				new JlinkImageBuilder.Launcher("get-history", "io.github.cowwoc.cat.codex.cli",
					"io.github.cowwoc.cat.codex.CodexGetHistory"),
				new JlinkImageBuilder.Launcher("session-start", "io.github.cowwoc.cat.codex.cli",
					"io.github.cowwoc.cat.codex.CodexSessionStartHook"),
				new JlinkImageBuilder.Launcher("subagent-start", "io.github.cowwoc.cat.codex.cli",
					"io.github.cowwoc.cat.codex.CodexSubagentStartHook")), jlinkDirectory.resolve("codex"));

		// Resolve these eagerly so a missing runtime dependency fails with a specific artifact name.
		if (!Files.isRegularFile(commonCli) || !Files.isRegularFile(commonEvaluation) ||
			!Files.isRegularFile(claudeCli) ||
			!Files.isRegularFile(codexCli) ||
			!Files.isRegularFile(requirements))
			throw new IOException("One or more Java runtime modules are missing from " + moduleDirectory);

		PluginArtifactBuilder artifactBuilder = new PluginArtifactBuilder(PluginInfo.CAT_VERSION);
		LegalMaterialsBuilder legalMaterialsBuilder = new LegalMaterialsBuilder(PluginInfo.CAT_VERSION);
		Path catLicense = projectRoot.resolve("LICENSE.md");
		Path canonicalLicenses = projectRoot.resolve("client/legal/licenses");
		Path commonInstallers = projectRoot.resolve("client/common/lifecycle/src/main/resources/install");
		Path claudeInstallers = projectRoot.resolve("client/claude/lifecycle/src/main/resources/install");
		Path codexInstallers = projectRoot.resolve("client/codex/lifecycle/src/main/resources/install");
		List<ThirdPartyComponent> commonLegalComponents = List.of(
			new ThirdPartyComponent("CommonMark Java", "org.commonmark:commonmark:0.30.0", "BSD-2-Clause.txt", commonmark,
				List.of("META-INF/LICENSE.txt")),
			new ThirdPartyComponent("TestNG", "org.testng:testng:7.12.0", "Apache-2.0.txt", testng,
				List.of("META-INF/LICENSE.txt")),
			new ThirdPartyComponent("java-diff-utils", "io.github.java-diff-utils:java-diff-utils:4.16",
				"Apache-2.0.txt", javaDiff, List.of()),
			new ThirdPartyComponent("Pouch", "io.github.cowwoc.pouch:pouch-core:10.3", "Apache-2.0.txt", pouch,
				List.of()),
			new ThirdPartyComponent("Requirements Annotation",
				"io.github.cowwoc.requirements:requirements-annotation:13.2", "Apache-2.0.txt", requirementsAnnotation,
				List.of()),
			new ThirdPartyComponent("Requirements Java", "io.github.cowwoc.requirements:requirements-java:13.2",
				"Apache-2.0.txt", requirements, List.of()),
			new ThirdPartyComponent("SLF4J API", "org.slf4j:slf4j-api:2.0.17", "MIT.txt", slf4j,
				List.of("META-INF/LICENSE.txt")),
			new ThirdPartyComponent("SLF4J NOP Provider", "org.slf4j:slf4j-nop:2.0.17", "MIT.txt", slf4jNop,
				List.of("META-INF/LICENSE.txt")));
		List<ThirdPartyComponent> jsonLegalComponents = List.of(
			new ThirdPartyComponent("Jackson Annotations", "com.fasterxml.jackson.core:jackson-annotations:2.21",
				"Apache-2.0.txt", jacksonAnnotations, List.of("META-INF/NOTICE")),
			new ThirdPartyComponent("Jackson Core", "tools.jackson.core:jackson-core:3.1.4", "Apache-2.0.txt",
				jacksonCore, List.of("META-INF/NOTICE", "META-INF/FastDoubleParser-LICENSE",
					"META-INF/FastDoubleParser-ThirdParty-LICENSE", "META-INF/Schubfach-LICENSE")),
			new ThirdPartyComponent("FastDoubleParser", "embedded in tools.jackson.core:jackson-core:3.1.4", "MIT.txt",
				jacksonCore, List.of()),
			new ThirdPartyComponent("fast_double_parser", "embedded in tools.jackson.core:jackson-core:3.1.4",
				"Boost-1.0.txt", jacksonCore, List.of()),
			new ThirdPartyComponent("fast_float", "embedded in tools.jackson.core:jackson-core:3.1.4", "MIT.txt",
				jacksonCore, List.of()),
			new ThirdPartyComponent("bigint", "embedded in tools.jackson.core:jackson-core:3.1.4", "BSD-2-Clause.txt",
				jacksonCore, List.of()),
			new ThirdPartyComponent("Schubfach", "embedded in tools.jackson.core:jackson-core:3.1.4", "MIT.txt",
				jacksonCore, List.of()),
			new ThirdPartyComponent("Jackson Databind", "tools.jackson.core:jackson-databind:3.1.4", "Apache-2.0.txt",
				jacksonDatabind, List.of("META-INF/NOTICE")),
			new ThirdPartyComponent("Jackson Dataformat YAML", "tools.jackson.dataformat:jackson-dataformat-yaml:3.1.4",
				"Apache-2.0.txt", jacksonYaml, List.of("META-INF/NOTICE")),
			new ThirdPartyComponent("SnakeYAML Engine", "org.snakeyaml:snakeyaml-engine:3.0.1", "Apache-2.0.txt",
				snakeyaml, List.of()));
		List<ThirdPartyComponent> claudeLegalComponents = append(commonLegalComponents, jsonLegalComponents);
		List<ThirdPartyComponent> codexLegalComponents = append(commonLegalComponents, jsonLegalComponents);
		Path claudeLegalMaterials = legalMaterialsBuilder.build(catLicense, claudeLegalComponents, canonicalLicenses,
			runtimeDirectory.resolve("claude-legal"));
		Path codexLegalMaterials = legalMaterialsBuilder.build(catLicense, codexLegalComponents, canonicalLicenses,
			runtimeDirectory.resolve("codex-legal"));
		Path claudeRoot = artifactBuilder.build(List.of(new ArtifactLayer("common-agents", commonAgents),
			new ArtifactLayer("common-plugin", commonPlugin, Set.of(), Set.of("hooks/session-start.sh",
				"hooks/subagent-start.sh")),
			new ArtifactLayer("common-skills", commonSkills), new ArtifactLayer("claude-skills", claudeSkills),
			new ArtifactLayer("common-rules", commonRules),
			new ArtifactLayer("claude-plugin", claudePlugin, Set.of(), Set.of("hooks/session-end.sh")),
			new ArtifactLayer("claude-rules", claudeRules)),
			claudeImage, claudeLegalMaterials, List.of(commonInstallers, claudeInstallers),
			runtimeDirectory.resolve("claude"));
		Path codexRoot = artifactBuilder.build(List.of(new ArtifactLayer("common-agents", commonAgents),
			new ArtifactLayer("common-plugin", commonPlugin, Set.of(), Set.of("hooks/session-start.sh",
				"hooks/subagent-start.sh")),
			new ArtifactLayer("common-skills", commonSkills),
			new ArtifactLayer("common-rules", commonRules), new ArtifactLayer("codex-skills", codexSkills, Set.of(
				"skills/get-history/SKILL.md",
				"skills/git-amend/SKILL.md", "skills/git-commit/SKILL.md", "skills/git-merge-linear/SKILL.md",
				"skills/git-rebase/SKILL.md", "skills/git-rewrite-history/SKILL.md", "skills/git-squash/SKILL.md",
				"skills/git-commit-per-topic/SKILL.md", "skills/help/SKILL.md")),
			new ArtifactLayer("codex-plugin", codexPlugin), new ArtifactLayer("codex-rules", codexRules)), codexImage,
			codexLegalMaterials, List.of(commonInstallers, codexInstallers), runtimeDirectory.resolve("codex"));
		try (ToolScope scope = new ToolScope())
		{
			validateRuleDependencies(claudeRoot, codexRoot, projectRoot, scope.yaml());
			RuleArtifactRouter.route(claudeRoot, scope.yaml());
			RuleArtifactRouter.route(codexRoot, scope.yaml());
		}

		ReleasePackager packager = new ReleasePackager(PluginInfo.CAT_VERSION);
		Path claudeArchive = packager.packageArtifact("claude", platform, claudeRoot, releaseDirectory);
		Path codexArchive = packager.packageArtifact("codex", platform, codexRoot, releaseDirectory);
		packager.writeChecksumManifest(List.of(claudeArchive, codexArchive), releaseDirectory.resolve("SHA256SUMS.txt"));
	}

	/**
	 * Prevents instantiation.
	 */
	private DistributionMain()
	{
	}

	/**
	 * Validates the rule graphs that each packaged harness will inject before modifying the assembled artifacts.
	 *
	 * @param claudeRoot the assembled Claude artifact
	 * @param codexRoot the assembled Codex artifact
	 * @param projectRoot the CAT project root
	 * @param yamlMapper the YAML parser
	 * @throws IOException if a direct rule dependency is missing or cyclic
	 */
	private static void validateRuleDependencies(Path claudeRoot, Path codexRoot, Path projectRoot, YAMLMapper yamlMapper)
		throws IOException
	{
		ProjectRuleLoader loader = new ProjectRuleLoader(yamlMapper);
		loader.loadForMainAgent(claudeRoot, projectRoot, "claude");
		loader.loadForMainAgent(codexRoot, projectRoot, "codex");
	}

	/**
	 * Finds one dependency JAR by artifact identifier.
	 *
	 * @param directory the dependency directory
	 * @param artifactId the artifact identifier
	 * @return the single JAR whose filename starts with the artifact identifier
	 * @throws IOException if no matching JAR or more than one matching JAR is found
	 */
	private static Path findDependencyJar(Path directory, String artifactId) throws IOException
	{
		try (Stream<Path> entries = Files.list(directory))
		{
			List<Path> matches = entries.filter(path -> path.getFileName().toString().startsWith(artifactId + "-") &&
				path.getFileName().toString().endsWith(".jar")).sorted().toList();
			if (matches.size() != 1)
				throw new IOException("Expected one " + artifactId + " JAR in " + directory + " but found " + matches);
			return matches.getFirst();
		}
	}

	/**
	 * Copies a module into the empty jlink module directory.
	 *
	 * @param module the module to copy
	 * @param directory the destination directory
	 * @return the copied module
	 * @throws IOException if the module cannot be copied
	 */
	private static Path copyModule(Path module, Path directory) throws IOException
	{
		return Files.copy(module, directory.resolve(module.getFileName()));
	}

	/**
	 * Appends one value to a list.
	 *
	 * @param <T> the value type
	 * @param paths the existing values
	 * @param path the value to append
	 * @return the combined list
	 */
	private static <T> List<T> append(List<T> paths, T path)
	{
		return Stream.concat(paths.stream(), Stream.of(path)).toList();
	}

	/**
	 * Appends values to a list.
	 *
	 * @param <T> the value type
	 * @param paths the existing values
	 * @param additionalPaths the values to append
	 * @return the combined list
	 */
	private static <T> List<T> append(List<T> paths, List<T> additionalPaths)
	{
		return Stream.concat(paths.stream(), additionalPaths.stream()).toList();
	}

	/**
	 * Adds an explicit module descriptor to java-diff-utils.
	 *
	 * @param source the original JAR
	 * @param moduleDirectory the patched-module directory
	 * @return the patched JAR
	 * @throws IOException if the descriptor cannot be added
	 */
	private static Path modularizeJavaDiff(Path source, Path moduleDirectory) throws IOException
	{
		return new ModuleDescriptorPatcher().patch(source, moduleDirectory.resolve(source.getFileName()),
			"io.github.javadiffutils", Set.of("com.github.difflib", "com.github.difflib.algorithm",
				"com.github.difflib.algorithm.myers", "com.github.difflib.patch", "com.github.difflib.text",
				"com.github.difflib.text.deltamerge", "com.github.difflib.unifieddiff"));
	}

	/**
	 * Adds the TestNG API module descriptor required by evaluator Java checks.
	 *
	 * @param source the original TestNG JAR
	 * @param moduleDirectory the patched-module directory
	 * @return the patched JAR
	 * @throws IOException if the descriptor cannot be added
	 */
	private static Path modularizeTestNg(Path source, Path moduleDirectory) throws IOException
	{
		return new ModuleDescriptorPatcher().patch(source, moduleDirectory.resolve(source.getFileName()), "org.testng",
			Set.of("org.testng", "org.testng.xml"));
	}

	/**
	 * Copies model-facing resources from one source layer into the staged bundle while rejecting a source-layer
	 * collision.
	 *
	 * @param sourceRoot the root containing one source resource layer
	 * @param stagedArtifact the destination prompt bundle
	 * @throws IOException if the source is invalid or conflicts with an earlier layer
	 */
	private static void copyPromptResources(Path sourceRoot, Path stagedArtifact) throws IOException
	{
		sourceRoot = sourceRoot.toAbsolutePath().normalize();
		if (!Files.isDirectory(sourceRoot, NOFOLLOW_LINKS))
			throw new IOException("Prompt source directory does not exist: " + sourceRoot);
		for (String resourceDirectory : List.of("agents", "skills", "rules"))
		{
			Path resourceRoot = sourceRoot.resolve(resourceDirectory);
			if (!Files.exists(resourceRoot, NOFOLLOW_LINKS))
				continue;
			if (!Files.isDirectory(resourceRoot, NOFOLLOW_LINKS))
				throw new IOException("Prompt source path is not a directory: " + resourceRoot);
			try (Stream<Path> paths = Files.walk(resourceRoot))
			{
				for (Path source : paths.sorted().toList())
				{
					Path target = stagedArtifact.resolve(sourceRoot.relativize(source));
					if (Files.isDirectory(source, NOFOLLOW_LINKS))
					{
						Files.createDirectories(target);
						continue;
					}
					if (!Files.isRegularFile(source, NOFOLLOW_LINKS))
						throw new IOException("Prompt source contains a non-regular file: " + source);
					if (Files.exists(target, NOFOLLOW_LINKS))
					{
						throw new IOException("Prompt source collision at " + target + " while copying " + sourceRoot);
					}
					Files.createDirectories(target.getParent());
					Files.copy(source, target);
				}
			}
		}
	}

	/**
	 * Exchanges installed prompt resources for a fully assembled staged bundle and restores the prior bundle on a failed
	 * replacement.
	 *
	 * @param artifact the existing plugin artifact
	 * @param stagedArtifact the prepared replacement artifact
	 * @throws IOException if the replacement or recovery cannot complete
	 */
	private static void replacePromptBundle(Path artifact, Path stagedArtifact) throws IOException
	{
		Path backup = stagedArtifact.getParent().resolve("previous-prompt-bundle");
		List<Path> backedUp = new ArrayList<>();
		List<Path> replaced = new ArrayList<>();
		for (String resourceDirectory : List.of("agents", "skills", "rules"))
		{
			Path staged = stagedArtifact.resolve(resourceDirectory);
			if (!Files.isDirectory(staged, NOFOLLOW_LINKS))
				throw new IOException("Staged prompt directory is missing: " + staged);
		}
		Files.createDirectories(backup);
		try
		{
			for (String resourceDirectory : List.of("agents", "skills", "rules"))
			{
				Path installed = artifact.resolve(resourceDirectory);
				if (!Files.isDirectory(installed, NOFOLLOW_LINKS))
					throw new IOException("Plugin artifact has no installed prompt directory: " + installed);
				Path previous = backup.resolve(resourceDirectory);
				move(installed, previous);
				backedUp.add(previous);
				move(stagedArtifact.resolve(resourceDirectory), installed);
				replaced.add(installed);
			}
			deleteTree(backup);
		}
		catch (IOException replacementFailure)
		{
			for (int index = replaced.size() - 1; index >= 0; --index)
				deleteTree(replaced.get(index));
			for (int index = backedUp.size() - 1; index >= 0; --index)
			{
				Path previous = backedUp.get(index);
				if (Files.exists(previous, NOFOLLOW_LINKS))
					move(previous, artifact.resolve(previous.getFileName()));
			}
			throw replacementFailure;
		}
	}

	/**
	 * Moves one directory within its filesystem, preserving atomic visibility when the filesystem supports it.
	 *
	 * @param source the existing source directory
	 * @param target the initially absent destination
	 * @throws IOException if the move cannot complete
	 */
	private static void move(Path source, Path target) throws IOException
	{
		try
		{
			Files.move(source, target, ATOMIC_MOVE);
		}
		catch (java.nio.file.AtomicMoveNotSupportedException _)
		{
			Files.move(source, target);
		}
	}

	/**
	 * Deletes one temporary directory after its staged bundle or backup is no longer needed.
	 *
	 * @param directory the directory to delete when it exists
	 * @throws IOException if an owned temporary path cannot be removed
	 */
	private static void deleteTree(Path directory) throws IOException
	{
		if (!Files.exists(directory, NOFOLLOW_LINKS))
			return;
		try (Stream<Path> paths = Files.walk(directory))
		{
			for (Path path : paths.sorted(Comparator.reverseOrder()).toList())
				Files.delete(path);
		}
	}

	/**
	 * Deletes the directory and all of its contents when it exists, then creates an empty directory at the same path.
	 *
	 * @param directory the directory to delete and recreate
	 * @throws IOException if the directory cannot be recreated
	 */
	private static void deleteAndRecreateDirectory(Path directory) throws IOException
	{
		deleteTree(directory);
		Files.createDirectories(directory);
	}
}
