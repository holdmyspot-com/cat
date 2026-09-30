package io.github.cowwoc.cat.distribution.bundle;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static java.nio.charset.StandardCharsets.UTF_8;

import io.github.cowwoc.cat.common.TemporaryDirectories;
import io.github.cowwoc.cat.common.ToolScope;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.testng.annotations.Test;

/**
 * Verifies rule references continue to identify their packaged files after routing.
 */
public final class RuleArtifactRouterTest
{
	/**
	 * Verifies a moved index still locates its included rule after packaging.
	 */
	@Test
	public void routesMovedIndexAndRuleReference() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories(); ToolScope scope = new ToolScope())
		{
			Path artifact = artifact(cleanup);
			Path common = Files.createDirectories(artifact.resolve("rules/common"));
			Path include = Files.createDirectories(artifact.resolve("rules/include/java"));
			Files.writeString(common.resolve("index.md"), scoped("../include/java/slf4j.md", "Index"));
			Files.writeString(include.resolve("slf4j.md"), "Target marker A\n");
			RuleArtifactRouter.route(artifact, scope.yaml());
			assertReference(artifact.resolve("rules/include/common/index.md"), "Target marker A");
		}
	}

	/**
	 * Verifies an unmoved caller locates a routed rule in another rule directory.
	 */
	@Test
	public void routesUnmovedCallerToMovedRule() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories(); ToolScope scope = new ToolScope())
		{
			Path artifact = artifact(cleanup);
			Path common = Files.createDirectories(artifact.resolve("rules/common"));
			Path java = Files.createDirectories(artifact.resolve("rules/java"));
			Files.writeString(common.resolve("default-rules.md"), "See `../java/target.md`.\n");
			Files.writeString(java.resolve("target.md"), scoped("Target marker B"));
			RuleArtifactRouter.route(artifact, scope.yaml());
			assertReference(common.resolve("default-rules.md"), "Target marker B");
		}
	}

	/**
	 * Verifies absolute and unresolved variable references retain their original text.
	 */
	@Test
	public void preservesAbsoluteAndVariableReferences() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories(); ToolScope scope = new ToolScope())
		{
			Path artifact = artifact(cleanup);
			Path common = Files.createDirectories(artifact.resolve("rules/common"));
			String original = "`/opt/cat/rule.md` and `${CAT_PLUGIN_ROOT}/rule.md`\n";
			Files.writeString(common.resolve("default-rules.md"), original);
			Files.writeString(common.resolve("moved.md"), scoped("moved target"));
			RuleArtifactRouter.route(artifact, scope.yaml());
			requireThat(Files.readString(common.resolve("default-rules.md"), UTF_8), "caller").isEqualTo(original);
		}
	}

	/**
	 * Creates and registers the temporary artifact directory.
	 *
	 * @param cleanup the cleanup owner
	 * @return the artifact directory
	 * @throws IOException if the directory cannot be created
	 */
	private static Path artifact(TemporaryDirectories cleanup) throws IOException
	{
		return cleanup.add(Files.createTempDirectory("cat-rule-reference-"));
	}

	/**
	 * Adds routing metadata to one scoped rule body.
	 *
	 * @param body the rule body
	 * @return the scoped rule content
	 */
	private static String scoped(String body)
	{
		return "---\npaths: [\"**/*.java\"]\n---\n" + body + "\n";
	}

	/**
	 * Creates a scoped index that references a Markdown file.
	 *
	 * @param reference the relative Markdown locator
	 * @param body the index body
	 * @return the scoped index content
	 */
	private static String scoped(String reference, String body)
	{
		return scoped(body + " `" + reference + "`");
	}

	/**
	 * Resolves a Markdown locator from its declaring file and checks its target marker.
	 *
	 * @param declaringFile the rule declaring the reference
	 * @param marker the expected target marker
	 * @throws IOException if the source or target cannot be read
	 */
	private static void assertReference(Path declaringFile, String marker) throws IOException
	{
		String source = Files.readString(declaringFile, UTF_8);
		int start = source.indexOf('`');
		int end = source.indexOf('`', start + 1);
		if (start == -1 || end == -1)
			throw new AssertionError("No Markdown file reference in " + declaringFile);
		Path target = declaringFile.getParent().resolve(source.substring(start + 1, end)).normalize();
		requireThat(Files.readString(target, UTF_8), "referencedTarget").contains(marker);
	}
}
