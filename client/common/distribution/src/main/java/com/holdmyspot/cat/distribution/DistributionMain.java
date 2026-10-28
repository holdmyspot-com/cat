package com.holdmyspot.cat.distribution;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static java.nio.file.LinkOption.NOFOLLOW_LINKS;

import com.holdmyspot.cat.common.Platform;
import com.holdmyspot.cat.common.PluginInfo;
import com.holdmyspot.cat.common.ProjectRuleLoader;
import com.holdmyspot.cat.common.ToolScope;
import com.holdmyspot.cat.common.MainToolScope;
import com.holdmyspot.cat.distribution.bundle.ArtifactLayer;
import com.holdmyspot.cat.distribution.bundle.JlinkImageBuilder;
import com.holdmyspot.cat.distribution.bundle.ModuleDescriptorPatcher;
import com.holdmyspot.cat.distribution.bundle.PluginArtifactBuilder;
import com.holdmyspot.cat.distribution.bundle.ReleasePackager;
import com.holdmyspot.cat.distribution.bundle.RuleArtifactRouter;
import com.holdmyspot.cat.distribution.bundle.RuleReferences;
import com.holdmyspot.cat.distribution.license.LegalMaterialsBuilder;
import com.holdmyspot.cat.distribution.license.ThirdPartyComponent;
import com.holdmyspot.cat.distribution.secrets.DistributionMainAccess;
import com.holdmyspot.cat.distribution.secrets.DistributionMainSecrets;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
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
	static
	{
		DistributionMainSecrets.setDistributionMainAccess(new DistributionMainAccess()
		{
			@Override
			public List<JlinkImageBuilder.Launcher> claudeLaunchers()
			{
				return DistributionMain.claudeLaunchers();
			}

			@Override
			public Set<String> claudeAgentLaunchers()
			{
				return DistributionMain.claudeAgentLaunchers();
			}

			@Override
			public List<JlinkImageBuilder.Launcher> codexLaunchers()
			{
				return DistributionMain.codexLaunchers();
			}

			@Override
			public Set<String> codexAgentLaunchers()
			{
				return DistributionMain.codexAgentLaunchers();
			}

			@Override
			public ArtifactLayer codexPluginLayer(Path jar)
			{
				return DistributionMain.codexPluginLayer(jar);
			}

			@Override
			public Path codexImage(JlinkImageBuilder builder, List<Path> modules, Path output, Platform platform)
				throws IOException
			{
				return DistributionMain.codexImage(builder, modules, output, platform);
			}

			@Override
			public String dependencyCoordinates(String groupId, String artifactId, Path path)
			{
				return new DependencyJar(groupId, artifactId, path).coordinates();
			}
		});
	}

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
		if (args.length != 3)
		{
			throw new IllegalArgumentException(
				"Usage: DistributionMain <project-root> <module-directory> <release-directory>");
		}
		build(Path.of(args[0]), Path.of(args[1]), Path.of(args[2]), Platform.current());
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
		Path claudeCli = findDependencyJar(moduleDirectory, "client-claude-cli");
		Path codexCli = findDependencyJar(moduleDirectory, "client-codex-cli");
		DependencyJar requirements = findThirdParty(moduleDirectory, "io.github.cowwoc.requirements",
			"requirements-java");
		DependencyJar requirementsAnnotation = findThirdParty(moduleDirectory, "io.github.cowwoc.requirements",
			"requirements-annotation");
		DependencyJar pouch = findThirdParty(moduleDirectory, "io.github.cowwoc.pouch", "pouch-core");
		DependencyJar slf4j = findThirdParty(moduleDirectory, "org.slf4j", "slf4j-api");
		DependencyJar slf4jNop = findThirdParty(moduleDirectory, "org.slf4j", "slf4j-nop");
		DependencyJar compress = findThirdParty(moduleDirectory, "org.apache.commons", "commons-compress");
		DependencyJar commonsIo = findThirdParty(moduleDirectory, "commons-io", "commons-io");
		DependencyJar commonsCodec = findThirdParty(moduleDirectory, "commons-codec", "commons-codec");
		DependencyJar commonsLang = findThirdParty(moduleDirectory, "org.apache.commons", "commons-lang3");
		DependencyJar javaDiff = findThirdParty(moduleDirectory, "io.github.java-diff-utils", "java-diff-utils");
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
		DependencyJar jacksonCore = findThirdParty(moduleDirectory, "tools.jackson.core", "jackson-core");
		DependencyJar jacksonAnnotations = findThirdParty(moduleDirectory, "com.fasterxml.jackson.core",
			"jackson-annotations");
		DependencyJar jacksonDatabind = findThirdParty(moduleDirectory, "tools.jackson.core", "jackson-databind");
		DependencyJar jacksonYaml = findThirdParty(moduleDirectory, "tools.jackson.dataformat",
			"jackson-dataformat-yaml");
		DependencyJar snakeyaml = findThirdParty(moduleDirectory, "org.snakeyaml", "snakeyaml-engine");
		DependencyJar jacksonToml = findThirdParty(moduleDirectory, "tools.jackson.dataformat",
			"jackson-dataformat-toml");

		List<Path> sharedRuntimeModules = List.of(copyModule(commonCli, jlinkModules),
			copyModule(requirements.path(), jlinkModules), copyModule(requirementsAnnotation.path(), jlinkModules),
			copyModule(pouch.path(), jlinkModules), copyModule(slf4j.path(), jlinkModules),
			copyModule(slf4jNop.path(), jlinkModules), copyModule(compress.path(), jlinkModules),
			copyModule(commonsIo.path(), jlinkModules), copyModule(commonsCodec.path(), jlinkModules),
			copyModule(commonsLang.path(), jlinkModules),
			modularizeJavaDiff(javaDiff.path(), jlinkModules));
		Path packagedClaudeCli = copyModule(claudeCli, jlinkModules);
		Path packagedCodexCli = copyModule(codexCli, jlinkModules);
		List<Path> jsonRuntimeModules = List.of(copyModule(jacksonCore.path(), jlinkModules),
			copyModule(jacksonAnnotations.path(), jlinkModules), copyModule(jacksonDatabind.path(), jlinkModules),
			copyModule(jacksonYaml.path(), jlinkModules), copyModule(snakeyaml.path(), jlinkModules),
			copyModule(jacksonToml.path(), jlinkModules));

		JlinkImageBuilder imageBuilder = new JlinkImageBuilder();
		Path claudeImage = imageBuilder.build(append(append(sharedRuntimeModules, packagedClaudeCli), jsonRuntimeModules),
			Set.of("com.holdmyspot.cat.claude.cli", "org.slf4j.nop"),
			claudeLaunchers(), jlinkDirectory.resolve("claude"));
		List<Path> codexModules = append(append(sharedRuntimeModules, packagedCodexCli), jsonRuntimeModules);
		Path codexImage = codexImage(imageBuilder, codexModules, jlinkDirectory.resolve("codex"), platform);

		PluginArtifactBuilder artifactBuilder = new PluginArtifactBuilder(PluginInfo.CAT_VERSION);
		LegalMaterialsBuilder legalMaterialsBuilder = new LegalMaterialsBuilder(PluginInfo.CAT_VERSION);
		Path catLicense = projectRoot.resolve("LICENSE.md");
		Path canonicalLicenses = projectRoot.resolve("client/legal/licenses");
		Path commonInstallers = projectRoot.resolve("client/common/lifecycle/src/main/resources/install");
		Path claudeInstallers = projectRoot.resolve("client/claude/lifecycle/src/main/resources/install");
		Path codexInstallers = projectRoot.resolve("client/codex/lifecycle/src/main/resources/install");
		String jacksonCoreCoordinates = jacksonCore.coordinates();
		List<ThirdPartyComponent> legalComponents = List.of(
			new ThirdPartyComponent("Apache Commons Compress", compress.coordinates(), "Apache-2.0.txt",
				compress.path(), List.of("META-INF/NOTICE.txt")),
			new ThirdPartyComponent("Apache Commons IO", commonsIo.coordinates(), "Apache-2.0.txt",
				commonsIo.path(), List.of("META-INF/NOTICE.txt")),
			new ThirdPartyComponent("Apache Commons Codec", commonsCodec.coordinates(), "Apache-2.0.txt",
				commonsCodec.path(), List.of("META-INF/NOTICE.txt")),
			new ThirdPartyComponent("Apache Commons Lang", commonsLang.coordinates(), "Apache-2.0.txt",
				commonsLang.path(), List.of("META-INF/NOTICE.txt")),
			new ThirdPartyComponent("java-diff-utils", javaDiff.coordinates(), "Apache-2.0.txt", javaDiff.path(),
				List.of()),
			new ThirdPartyComponent("Pouch", pouch.coordinates(), "Apache-2.0.txt", pouch.path(), List.of()),
			new ThirdPartyComponent("Requirements Annotation", requirementsAnnotation.coordinates(), "Apache-2.0.txt",
				requirementsAnnotation.path(), List.of()),
			new ThirdPartyComponent("Requirements Java", requirements.coordinates(), "Apache-2.0.txt",
				requirements.path(), List.of()),
			new ThirdPartyComponent("SLF4J API", slf4j.coordinates(), "MIT.txt", slf4j.path(),
				List.of("META-INF/LICENSE.txt")),
			new ThirdPartyComponent("SLF4J NOP Provider", slf4jNop.coordinates(), "MIT.txt", slf4jNop.path(),
				List.of("META-INF/LICENSE.txt")),
			new ThirdPartyComponent("Jackson Annotations", jacksonAnnotations.coordinates(), "Apache-2.0.txt",
				jacksonAnnotations.path(), List.of("META-INF/NOTICE")),
			new ThirdPartyComponent("Jackson Core", jacksonCoreCoordinates, "Apache-2.0.txt",
				jacksonCore.path(), List.of("META-INF/NOTICE", "META-INF/FastDoubleParser-LICENSE",
					"META-INF/FastDoubleParser-ThirdParty-LICENSE", "META-INF/Schubfach-LICENSE")),
			new ThirdPartyComponent("FastDoubleParser", "embedded in " + jacksonCoreCoordinates, "MIT.txt",
				jacksonCore.path(), List.of()),
			new ThirdPartyComponent("fast_double_parser", "embedded in " + jacksonCoreCoordinates,
				"Boost-1.0.txt", jacksonCore.path(), List.of()),
			new ThirdPartyComponent("fast_float", "embedded in " + jacksonCoreCoordinates, "MIT.txt",
				jacksonCore.path(), List.of()),
			new ThirdPartyComponent("bigint", "embedded in " + jacksonCoreCoordinates, "BSD-2-Clause.txt",
				jacksonCore.path(), List.of()),
			new ThirdPartyComponent("Schubfach", "embedded in " + jacksonCoreCoordinates, "MIT.txt",
				jacksonCore.path(), List.of()),
			new ThirdPartyComponent("Jackson Databind", jacksonDatabind.coordinates(), "Apache-2.0.txt",
				jacksonDatabind.path(), List.of("META-INF/NOTICE")),
			new ThirdPartyComponent("Jackson Dataformat YAML", jacksonYaml.coordinates(), "Apache-2.0.txt",
				jacksonYaml.path(), List.of("META-INF/NOTICE")),
			new ThirdPartyComponent("SnakeYAML Engine", snakeyaml.coordinates(), "Apache-2.0.txt",
				snakeyaml.path(), List.of()),
			new ThirdPartyComponent("Jackson Dataformat TOML", jacksonToml.coordinates(), "Apache-2.0.txt",
				jacksonToml.path(), List.of("META-INF/NOTICE")));
		Path legalMaterials = legalMaterialsBuilder.build(catLicense, legalComponents, canonicalLicenses,
			runtimeDirectory.resolve("legal"));
		Path claudeRoot = artifactBuilder.build(List.of(new ArtifactLayer("common-agents", commonAgents),
			new ArtifactLayer("common-plugin", commonPlugin, Set.of(), Set.of("hooks/session-start.sh",
				"hooks/subagent-start.sh")),
			new ArtifactLayer("common-skills", commonSkills), new ArtifactLayer("claude-skills", claudeSkills),
			new ArtifactLayer("common-rules", commonRules),
			new ArtifactLayer("claude-plugin", claudePlugin, Set.of(), Set.of("hooks/session-end.sh")),
			new ArtifactLayer("claude-rules", claudeRules)),
			claudeImage, claudeAgentLaunchers(), legalMaterials, List.of(commonInstallers, claudeInstallers),
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
			codexPluginLayer(codexPlugin), new ArtifactLayer("codex-rules", codexRules)), codexImage,
			codexAgentLaunchers(), legalMaterials, List.of(commonInstallers, codexInstallers),
			runtimeDirectory.resolve("codex"));
		try (ToolScope scope = new MainToolScope())
		{
			validateRuleDependencies(claudeRoot, codexRoot, projectRoot, scope.yaml());
			RuleArtifactRouter.route(claudeRoot, scope.yaml());
			RuleArtifactRouter.route(codexRoot, scope.yaml());
		}
		RuleReferences.requireResolvable(claudeRoot);
		RuleReferences.requireResolvable(codexRoot);

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
	 * Validates the rule graphs that each packaged harness will inject before modifying the assembled artifacts. Each
	 * artifact holds the POSIX and Windows fragments that its harness rules include, so the graphs are validated with
	 * each.
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
		for (Platform platform : List.of(Platform.LINUX_X64, Platform.WINDOWS_X64))
		{
			ProjectRuleLoader loader = new ProjectRuleLoader(yamlMapper, platform);
			loader.loadForMainAgent(claudeRoot, projectRoot, "claude");
			loader.loadForMainAgent(codexRoot, projectRoot, "codex");
		}
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
	 * Finds one third-party dependency JAR.
	 *
	 * @param directory the dependency directory
	 * @param groupId the Maven group identifier
	 * @param artifactId the Maven artifact identifier
	 * @return the single JAR whose filename starts with the artifact identifier
	 * @throws IOException if no matching JAR or more than one matching JAR is found
	 */
	private static DependencyJar findThirdParty(Path directory, String groupId, String artifactId) throws IOException
	{
		return new DependencyJar(groupId, artifactId, findDependencyJar(directory, artifactId));
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
	 * @param values the existing values
	 * @param added the value to append
	 * @return the combined list
	 */
	private static <T> List<T> append(List<T> values, T added)
	{
		return Stream.concat(values.stream(), Stream.of(added)).toList();
	}

	/**
	 * Appends values to a list.
	 *
	 * @param <T> the value type
	 * @param values the existing values
	 * @param added the values to append
	 * @return the combined list
	 */
	private static <T> List<T> append(List<T> values, List<T> added)
	{
		return Stream.concat(values.stream(), added.stream()).toList();
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
	 * Deletes the directory and all of its contents when it exists, then creates an empty directory at the same path.
	 *
	 * @param directory the directory to delete and recreate
	 * @throws IOException if the directory cannot be recreated
	 */
	private static void deleteAndRecreateDirectory(Path directory) throws IOException
	{
		if (Files.exists(directory, NOFOLLOW_LINKS))
		{
			try (Stream<Path> paths = Files.walk(directory))
			{
				for (Path path : paths.sorted(Comparator.reverseOrder()).toList())
					Files.delete(path);
			}
		}
		Files.createDirectories(directory);
	}

	/**
	 * Returns the layer of the Codex plugin's own resources, including the programs that commands run by
	 * {@code run-part} find first and the launcher of the PostToolUse hook.
	 *
	 * @param jar the Codex plugin resource JAR
	 * @return the layer
	 */
	static ArtifactLayer codexPluginLayer(Path jar)
	{
		return new ArtifactLayer("codex-plugin", jar, Set.of(), Set.of("client/run-part-overrides/rg",
			"hooks/post-tool-use.sh"));
	}

	/**
	 * Builds the Codex image. On Windows, the image holds only the batch launchers: Codex runs commands there in
	 * PowerShell, and CAT's skills name each launcher by its path without an extension, which PowerShell resolves to
	 * the POSIX launcher whenever it exists. The Claude image keeps both, because Claude Code runs commands in Git Bash.
	 * <p>
	 * The Codex image holds no symbolic links, because Codex's plugin installer drops them: each link becomes a copy of
	 * the file that it points to.
	 *
	 * @param builder  the image builder
	 * @param modules  the module-path entries of the image
	 * @param output   the image's directory
	 * @param platform the platform that the image runs on
	 * @return the image's directory
	 * @throws IOException if the image cannot be built
	 */
	static Path codexImage(JlinkImageBuilder builder, List<Path> modules, Path output, Platform platform)
		throws IOException
	{
		Path image = builder.build(modules, Set.of("com.holdmyspot.cat.codex.cli", "org.slf4j.nop"), codexLaunchers(),
			output);
		if (platform.isWindows())
			JlinkImageBuilder.removePosixLaunchers(codexLaunchers(), image);
		JlinkImageBuilder.replaceLinksWithCopies(image);
		return image;
	}

	/**
	 * Returns the launchers of the Claude image.
	 *
	 * @return the launchers
	 */
	static List<JlinkImageBuilder.Launcher> claudeLaunchers()
	{
		return append(sharedLaunchers(), List.of(
			new JlinkImageBuilder.Launcher("plugin-info", "com.holdmyspot.cat.claude.cli",
				"com.holdmyspot.cat.claude.ClaudePluginInfo"),
			new JlinkImageBuilder.Launcher("request-session", "com.holdmyspot.cat.common.cli",
				"com.holdmyspot.cat.common.runner.SessionRequestCli"),
			new JlinkImageBuilder.Launcher("claude-runner", "com.holdmyspot.cat.claude.cli",
				"com.holdmyspot.cat.claude.runner.ClaudeRunnerCli"),
			new JlinkImageBuilder.Launcher("claude-artifact", "com.holdmyspot.cat.claude.cli",
				"com.holdmyspot.cat.claude.runner.ClaudeArtifact"),
			new JlinkImageBuilder.Launcher("codex-artifact", "com.holdmyspot.cat.claude.cli",
				"com.holdmyspot.cat.claude.runner.CodexArtifact"),
			new JlinkImageBuilder.Launcher("codex-runner", "com.holdmyspot.cat.claude.cli",
				"com.holdmyspot.cat.claude.runner.CodexRunnerRelay"),
			new JlinkImageBuilder.Launcher("get-history", "com.holdmyspot.cat.claude.cli",
				"com.holdmyspot.cat.claude.ClaudeGetHistory"),
			new JlinkImageBuilder.Launcher("session-start", "com.holdmyspot.cat.claude.cli",
				"com.holdmyspot.cat.claude.ClaudeSessionStartHook"),
			new JlinkImageBuilder.Launcher("session-end", "com.holdmyspot.cat.claude.cli",
				"com.holdmyspot.cat.claude.ClaudeSessionEndHook"),
			new JlinkImageBuilder.Launcher("subagent-start", "com.holdmyspot.cat.claude.cli",
				"com.holdmyspot.cat.claude.ClaudeSubagentStartHook"),
			new JlinkImageBuilder.Launcher("prompt-optimizer", "com.holdmyspot.cat.claude.cli",
				"com.holdmyspot.cat.claude.optimizer.ClaudePromptOptimizer"),
			new JlinkImageBuilder.Launcher("wait-until", "com.holdmyspot.cat.claude.cli",
				"com.holdmyspot.cat.claude.ClaudeWaitUntil")));
	}

	/**
	 * Returns the launchers of the Claude image that agents run, which the plugin holds in {@code bin/}. They include
	 * {@code claude-artifact}, which reports this plugin as the Claude CAT artifact, {@code codex-artifact}, which
	 * finds the Codex CAT artifact, {@code claude-runner}, which the Codex plugin's {@code claude-runner} launcher runs
	 * from the Claude artifact, and {@code codex-runner}, which runs the runner of the Codex artifact, so that the
	 * runner skills of both harnesses run the same launchers.
	 *
	 * @return the launchers' names
	 */
	static Set<String> claudeAgentLaunchers()
	{
		return Set.of("git-topic-replay", "get-history", "plugin-info", "prompt-optimizer", "claude-artifact",
			"claude-runner", "codex-artifact", "codex-runner", "wait-until", "request-session");
	}

	/**
	 * Returns the launchers of the Codex image.
	 *
	 * @return the launchers
	 */
	static List<JlinkImageBuilder.Launcher> codexLaunchers()
	{
		return append(sharedLaunchers(), List.of(
			new JlinkImageBuilder.Launcher("plugin-info", "com.holdmyspot.cat.codex.cli",
				"com.holdmyspot.cat.codex.CodexPluginInfo"),
			new JlinkImageBuilder.Launcher("request-session", "com.holdmyspot.cat.common.cli",
				"com.holdmyspot.cat.common.runner.SessionRequestCli"),
			new JlinkImageBuilder.Launcher("codex-runner", "com.holdmyspot.cat.codex.cli",
				"com.holdmyspot.cat.codex.runner.CodexRunnerCli"),
			new JlinkImageBuilder.Launcher("codex-artifact", "com.holdmyspot.cat.codex.cli",
				"com.holdmyspot.cat.codex.runner.CodexArtifact"),
			new JlinkImageBuilder.Launcher("claude-artifact", "com.holdmyspot.cat.codex.cli",
				"com.holdmyspot.cat.codex.runner.ClaudeArtifact"),
			new JlinkImageBuilder.Launcher("claude-runner", "com.holdmyspot.cat.codex.cli",
				"com.holdmyspot.cat.codex.runner.ClaudeRunnerRelay"),
			new JlinkImageBuilder.Launcher("get-history", "com.holdmyspot.cat.codex.cli",
				"com.holdmyspot.cat.codex.CodexGetHistory"),
			new JlinkImageBuilder.Launcher("session-start", "com.holdmyspot.cat.codex.cli",
				"com.holdmyspot.cat.codex.CodexSessionStartHook"),
			new JlinkImageBuilder.Launcher("subagent-start", "com.holdmyspot.cat.codex.cli",
				"com.holdmyspot.cat.codex.CodexSubagentStartHook"),
			new JlinkImageBuilder.Launcher("post-tool-use", "com.holdmyspot.cat.codex.cli",
				"com.holdmyspot.cat.codex.CodexPostToolUseHook"),
			new JlinkImageBuilder.Launcher("run-part", "com.holdmyspot.cat.codex.cli",
				"com.holdmyspot.cat.codex.CodexRunPart"),
			new JlinkImageBuilder.Launcher("read-part", "com.holdmyspot.cat.codex.cli",
				"com.holdmyspot.cat.codex.CodexReadPart"),
			new JlinkImageBuilder.Launcher("prompt-optimizer", "com.holdmyspot.cat.codex.cli",
				"com.holdmyspot.cat.codex.optimizer.CodexPromptOptimizer"),
			new JlinkImageBuilder.Launcher("wait-until", "com.holdmyspot.cat.codex.cli",
				"com.holdmyspot.cat.codex.CodexWaitUntil")));
	}

	/**
	 * Returns the launchers of the Codex image that agents run, which the plugin holds in {@code bin/}. They include
	 * {@code codex-artifact}, which reports this plugin as the Codex CAT artifact, {@code claude-artifact}, which finds
	 * the Claude CAT artifact, {@code codex-runner}, which the Claude plugin's {@code codex-runner} launcher runs from
	 * the Codex artifact, and {@code claude-runner}, which runs the runner of the Claude artifact, so that the runner
	 * skills of both harnesses run the same launchers.
	 *
	 * @return the launchers' names
	 */
	static Set<String> codexAgentLaunchers()
	{
		return Set.of("git-topic-replay", "get-history", "plugin-info", "prompt-optimizer", "run-part", "read-part",
			"claude-artifact", "claude-runner", "codex-artifact", "codex-runner", "wait-until", "request-session");
	}

	/**
	 * Returns the launchers that both images contain.
	 *
	 * @return the launchers
	 */
	private static List<JlinkImageBuilder.Launcher> sharedLaunchers()
	{
		return List.of(
			new JlinkImageBuilder.Launcher("git-topic-replay", "com.holdmyspot.cat.common.cli",
				"com.holdmyspot.cat.common.git.replay.GitTopicReplayEditor"),
			new JlinkImageBuilder.Launcher("session-analyzer", "com.holdmyspot.cat.common.cli",
				"com.holdmyspot.cat.common.history.SessionHistory"));
	}
}
