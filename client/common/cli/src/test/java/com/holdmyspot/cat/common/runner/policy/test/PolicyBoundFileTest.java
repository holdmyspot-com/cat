package com.holdmyspot.cat.common.runner.policy.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.TemporaryDirectories;
import com.holdmyspot.cat.common.fakes.test.TestJvmScope;
import com.holdmyspot.cat.common.runner.policy.ChildPolicy;
import com.holdmyspot.cat.common.runner.policy.PolicyBoundFile;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * Tests that a child's policy reaches its runner unchanged through its {@link PolicyBoundFile}, and that the runner
 * refuses a file that is not exactly such a document.
 */
public final class PolicyBoundFileTest
{
	private static final String VALID = """
		{"format_version":1,"write_roots":["/run"],"write_denials":[],"read_denials":["/secret"],\
		"read_reallowances":[],"hosts":["model.example"],"allow_local_binding":false}""";
	private final JsonMapper json = JsonMapper.builder().build();

	/**
	 * Creates the test.
	 */
	public PolicyBoundFileTest()
	{
	}

	/**
	 * Verifies that the policy that a launcher writes is the policy that its runner reads.
	 *
	 * @throws IOException if the file cannot be written or read
	 */
	@Test
	public void runnerReadsWrittenPolicy() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories();
			TestJvmScope jvm = new TestJvmScope())
		{
			Path root = cleanup.add(Files.createTempDirectory(jvm.temporaryDirectory(), "policy-bound-"));
			ChildPolicy policy = new ChildPolicy(List.of(root.resolve("run"), root.resolve("cache")),
				List.of(root.resolve("run/protected")), List.of(root.resolve("secret")),
				List.of(root.resolve("secret/shared")), Set.of("model.example", "localhost"), true);
			Path file = root.resolve(PolicyBoundFile.FILE_NAME);

			PolicyBoundFile.write(json, file, policy);

			requireThat(PolicyBoundFile.read(json, file), "read").isEqualTo(policy);
		}
	}

	/**
	 * Supplies documents that are not policy bounds, each with a word that the refusal names.
	 *
	 * @return the document and the word
	 */
	@DataProvider
	public Object[][] invalidDocuments()
	{
		return new Object[][]
			{
				{"[]", "object"},
				{"{", "JSON"},
				{VALID.replace("\"format_version\":1", "\"format_version\":2"), "format_version"},
				{VALID.replace(",\"allow_local_binding\":false", ""), "allow_local_binding"},
				{VALID.replace("}", ",\"extra\":1}"), "extra"},
				{VALID.replace("\"/run\"", "\"run\""), "absolute"},
				{VALID.replace("[\"model.example\"]", "[1]"), "hosts"},
				{VALID.replace("\"write_roots\":[\"/run\"]", "\"write_roots\":[]"), "writeRoots"},
				{VALID.replace("false}", "\"no\"}"), "allow_local_binding"}
			};
	}

	/**
	 * Verifies that a runner refuses a file that is not a version 1 policy bound with exactly its fields, naming what
	 * is wrong.
	 *
	 * @param document the file's content
	 * @param word     a word that the refusal names
	 * @throws IOException if the file cannot be written
	 */
	@Test(dataProvider = "invalidDocuments")
	public void refusesInvalidDocument(String document, String word) throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories();
			TestJvmScope jvm = new TestJvmScope())
		{
			Path root = cleanup.add(Files.createTempDirectory(jvm.temporaryDirectory(), "policy-bound-"));
			Path file = Files.writeString(root.resolve(PolicyBoundFile.FILE_NAME), document);
			try
			{
				PolicyBoundFile.read(json, file);
				throw new AssertionError("The document was read: " + document);
			}
			catch (IOException e)
			{
				requireThat(e.getMessage(), "message").contains(word).contains(file.toString());
			}
		}
	}

	/**
	 * Verifies that the runner of a bounded session gives each session that the bounded session requests a bound of
	 * its own: the bounded session's policy, widened only by the requested session's run root, in a file that the
	 * requested session's runner is told about.
	 *
	 * @throws IOException if the files cannot be written or read
	 */
	@Test
	public void requestedSessionGetsOwnBound() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories();
			TestJvmScope jvm = new TestJvmScope())
		{
			Path root = cleanup.add(Files.createTempDirectory(jvm.temporaryDirectory(), "policy-bound-")).toRealPath();
			Path run = Files.createDirectory(root.resolve("run"));
			Path secret = Files.createDirectory(root.resolve("secret"));
			Path requestedRoot = Files.createDirectories(root.resolve("requested/run-root"));
			Path directory = Files.createDirectories(root.resolve("requested/response"));
			ChildPolicy policy = new ChildPolicy(List.of(run), List.of(), List.of(secret), List.of(),
				Set.of("model.example"), false);
			List<String> arguments = List.of("--run-root", requestedRoot.toString(), "--model", "model-a");

			List<String> bounded = PolicyBoundFile.boundArguments(json, arguments, directory, policy);

			requireThat(bounded, "bounded").isEqualTo(List.of("--run-root", requestedRoot.toString(), "--model",
				"model-a", PolicyBoundFile.OPTION, directory.resolve(PolicyBoundFile.FILE_NAME).toString()));
			ChildPolicy bound = PolicyBoundFile.read(json, directory.resolve(PolicyBoundFile.FILE_NAME));
			requireThat(bound.writeRoots(), "writeRoots").isEqualTo(List.of(requestedRoot));
			requireThat(bound.readDenials(), "readDenials").isEqualTo(List.of(secret));
			requireThat(bound.hosts(), "hosts").isEqualTo(Set.of("model.example"));
		}
	}

	/**
	 * Verifies that the runner of a bounded session refuses to start a requested session whose arguments name no run
	 * root, since the requested session's bound could not include it.
	 *
	 * @throws IOException if the fixture cannot be created
	 */
	@Test(expectedExceptions = IOException.class, expectedExceptionsMessageRegExp = ".*--run-root.*")
	public void refusesRequestedSessionWithoutRunRoot() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories();
			TestJvmScope jvm = new TestJvmScope())
		{
			Path root = cleanup.add(Files.createTempDirectory(jvm.temporaryDirectory(), "policy-bound-")).toRealPath();
			ChildPolicy policy = new ChildPolicy(List.of(root), List.of(), List.of(), List.of(), Set.of(), false);

			PolicyBoundFile.boundArguments(json, List.of("--model", "model-a"), root, policy);
		}
	}
}
