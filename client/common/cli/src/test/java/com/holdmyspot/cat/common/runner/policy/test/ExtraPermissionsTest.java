package com.holdmyspot.cat.common.runner.policy.test;

import com.holdmyspot.cat.common.TemporaryDirectories;
import com.holdmyspot.cat.common.runner.policy.ChildNeeds;
import com.holdmyspot.cat.common.runner.policy.ChildPolicy;
import com.holdmyspot.cat.common.runner.policy.ExtraPermissions;
import com.holdmyspot.cat.common.runner.policy.HostAccess;
import com.holdmyspot.cat.common.runner.policy.MissingPermissionsException;
import com.holdmyspot.cat.common.runner.policy.ParentPolicy;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;
import tools.jackson.databind.json.JsonMapper;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static org.testng.Assert.expectThrows;

/**
 * Tests that the file of extra permissions that a caller names gives a child the writes and hosts it lists, and is
 * rejected when it is malformed, names a relative path or names a host pattern, as {@link ChildNeeds} rejects them.
 */
public final class ExtraPermissionsTest
{
	private final JsonMapper json = JsonMapper.builder().build();

	/**
	 * Creates the test.
	 */
	public ExtraPermissionsTest()
	{
	}

	/**
	 * Writes a permissions file.
	 *
	 * @param directory the directory that receives the file
	 * @param content   the file's content
	 * @return the file
	 * @throws IOException if the file cannot be written
	 */
	private static Path file(Path directory, String content) throws IOException
	{
		return Files.writeString(directory.resolve("permissions.json"), content);
	}

	/**
	 * Verifies that a file that lists absolute writes and hosts gives exactly those writes, and the hosts in lower
	 * case.
	 *
	 * @throws IOException if the fixture cannot be created
	 */
	@Test
	public void readsWritesAndHosts() throws IOException
	{
		// Requirement: R-I11
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path directory = cleanup.add(Files.createTempDirectory("cat-extra-")).toRealPath();
			Path cache = directory.resolve("npm-cache");
			Path file = file(directory, """
				{"format_version": 1, "writes": [%s], "hosts": ["Registry.Example.com"]}
				""".formatted(json.writeValueAsString(cache.toString())));

			ExtraPermissions permissions = ExtraPermissions.read(json, file);

			requireThat(permissions, "permissions").isEqualTo(new ExtraPermissions(List.of(cache),
				Set.of("registry.example.com")));
		}
	}

	/**
	 * Verifies that a file may leave out either list, which then requests nothing of that kind.
	 *
	 * @throws IOException if the fixture cannot be created
	 */
	@Test
	public void listsAreOptional() throws IOException
	{
		// Requirement: R-I11
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path directory = cleanup.add(Files.createTempDirectory("cat-extra-")).toRealPath();

			ExtraPermissions permissions = ExtraPermissions.read(json, file(directory, """
				{"format_version": 1, "hosts": ["registry.example.com"]}
				"""));

			requireThat(permissions, "permissions").isEqualTo(new ExtraPermissions(List.of(),
				Set.of("registry.example.com")));
		}
	}

	/**
	 * Returns files that the project's JSON rules reject, with the text that the rejection names.
	 *
	 * @return each file's content and the text of the rejection
	 */
	@DataProvider(name = "rejectedFiles")
	public Object[][] rejectedFiles()
	{
		return new Object[][]
			{
				{"{\"format_version\": 1, \"writes\": [], \"hosts\": [], \"logins\": []}", "logins"},
				// The rejection names a path in the form of the platform's separators
				{"{\"format_version\": 1, \"writes\": [\"relative/cache\"]}", Path.of("relative/cache").toString()},
				{"{\"format_version\": 1, \"hosts\": [\"*.example.com\"]}", "*.example.com"},
				{"{\"format_version\": 1, \"hosts\": [\" \"]}", "host"},
				{"{\"format_version\": 2, \"hosts\": []}", "format_version"},
				{"{\"hosts\": []}", "format_version"},
				{"{\"format_version\": 1, \"writes\": \"/cache\"}", "writes"},
				{"{\"format_version\": 1, \"hosts\": [7]}", "hosts"},
				{"[]", "JSON object"},
				{"{\"format_version\": ", "JSON"}
			};
	}

	/**
	 * Verifies that a file with an unknown field, a relative path, a host pattern, a missing or unsupported version, or
	 * a value of the wrong type is rejected, and the rejection names the problem and the file.
	 *
	 * @param content the file's content
	 * @param problem the text that the rejection names
	 * @throws IOException if the fixture cannot be created
	 */
	@Test(dataProvider = "rejectedFiles")
	public void rejectsInvalidFile(String content, String problem) throws IOException
	{
		// Requirement: R-I11
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path directory = cleanup.add(Files.createTempDirectory("cat-extra-")).toRealPath();
			Path file = file(directory, content);

			IOException rejection = expectThrows(IOException.class, () -> ExtraPermissions.read(json, file));

			requireThat(rejection.getMessage(), "message").contains(problem).contains(file.toString());
		}
	}

	/**
	 * Verifies that permissions written to a file are read back unchanged, so that a launcher passes a request's
	 * permissions on to the runner that serves the child.
	 *
	 * @throws IOException if the fixture cannot be created
	 */
	@Test
	public void writtenPermissionsReadBack() throws IOException
	{
		// Requirement: R-I11
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path directory = cleanup.add(Files.createTempDirectory("cat-extra-")).toRealPath();
			ExtraPermissions permissions = new ExtraPermissions(List.of(directory.resolve("a"), directory.resolve("b")),
				Set.of("b.example", "a.example"));
			Path file = directory.resolve("written.json");

			permissions.write(json, file);

			requireThat(ExtraPermissions.read(json, file), "read").isEqualTo(permissions);
		}
	}

	/**
	 * Verifies that no permissions request nothing.
	 */
	@Test
	public void noPermissionsAreEmpty()
	{
		// Requirement: R-I11
		requireThat(ExtraPermissions.NONE.isEmpty(), "NONE.isEmpty()").isTrue();
		requireThat(new ExtraPermissions(List.of(), Set.of("a.example")).isEmpty(), "isEmpty()").isFalse();
	}

	/**
	 * Verifies that extra permissions join a child's needs and leave the rest of them as they were.
	 *
	 * @throws IOException if the fixture cannot be created
	 */
	@Test
	public void joinChildNeeds() throws IOException
	{
		// Requirement: R-I11
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("cat-extra-")).toRealPath();
			Path run = root.resolve("run");
			Path cache = root.resolve("cache");
			Path registry = root.resolve("registry");
			Path denied = root.resolve("denied");
			ChildNeeds needs = new ChildNeeds(run, List.of(cache), List.of(denied), Set.of("model.example"));

			ChildNeeds joined = needs.with(new ExtraPermissions(List.of(registry, cache), Set.of("npm.example")));

			requireThat(joined, "joined").isEqualTo(new ChildNeeds(run, List.of(cache, registry), List.of(denied),
				Set.of("model.example", "npm.example")));
			requireThat(needs.with(ExtraPermissions.NONE), "withNone").isEqualTo(needs);
		}
	}

	/**
	 * Verifies that a parent whose sandbox allows the extra permissions gives them to the child.
	 *
	 * @throws IOException if the fixture cannot be created
	 */
	@Test
	public void parentGrantsAllowedPermissions() throws IOException
	{
		// Requirement: R-I11
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("cat-extra-")).toRealPath();
			Path run = Files.createDirectory(root.resolve("run"));
			Path registry = Files.createDirectory(root.resolve("registry"));
			ParentPolicy parent = new ParentPolicy(true, List.of(registry), List.of(), List.of(), List.of(),
				HostAccess.listed(Set.of("npm.example")), false, List.of());
			ChildNeeds needs = new ChildNeeds(run, List.of(), List.of(), Set.of()).
				with(new ExtraPermissions(List.of(registry), Set.of("npm.example")));

			ChildPolicy child = ChildPolicy.intersect(parent, needs);

			requireThat(child.writeRoots(), "writeRoots").isEqualTo(List.of(run, registry));
			requireThat(child.hosts(), "hosts").isEqualTo(Set.of("npm.example"));
		}
	}

	/**
	 * Verifies that a parent whose sandbox lacks an extra write or host refuses the child, naming both.
	 *
	 * @throws IOException if the fixture cannot be created
	 */
	@Test
	public void parentRefusesMissingPermissions() throws IOException
	{
		// Requirement: R-I11
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("cat-extra-")).toRealPath();
			Path run = Files.createDirectory(root.resolve("run"));
			Path registry = Files.createDirectory(root.resolve("registry"));
			ParentPolicy parent = new ParentPolicy(true, List.of(run), List.of(), List.of(), List.of(),
				HostAccess.NONE, false, List.of());
			ChildNeeds needs = new ChildNeeds(run, List.of(), List.of(), Set.of()).
				with(new ExtraPermissions(List.of(registry), Set.of("npm.example")));

			MissingPermissionsException refusal = expectThrows(MissingPermissionsException.class,
				() -> ChildPolicy.intersect(parent, needs));

			requireThat(refusal.getMessage(), "message").contains(registry.toString()).contains("npm.example");
		}
	}

	/**
	 * Verifies that the refusal of a session that a run's session requested tells the caller to name the missing write
	 * and host with {@code --extra-permissions} on the runner launcher command that started the requesting session,
	 * and does not send it to sandbox settings, which would change nothing for that session.
	 *
	 * @throws IOException if the fixture cannot be created
	 */
	@Test
	public void refusalNamesExtraPermissionsOption() throws IOException
	{
		// Requirement: R-I11
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("cat-extra-")).toRealPath();
			Path run = Files.createDirectory(root.resolve("run"));
			Path registry = Files.createDirectory(root.resolve("registry"));
			ParentPolicy parent = new ParentPolicy(true, List.of(run), List.of(), List.of(), List.of(),
				HostAccess.NONE, false, List.of());
			ChildNeeds needs = new ChildNeeds(run, List.of(), List.of(), Set.of()).
				with(new ExtraPermissions(List.of(registry), Set.of("npm.example")));

			MissingPermissionsException refusal = expectThrows(MissingPermissionsException.class,
				() -> ChildPolicy.intersect(parent, needs));

			requireThat(refusal.getMessage(), "message").contains(ExtraPermissions.OPTION).
				contains("runner launcher").doesNotContain("sandbox settings");
		}
	}

	/**
	 * Verifies that the refusal for a write inside a path that the requesting session's sandbox denies says that
	 * {@code --extra-permissions} does not lift the denial, and names the denied path.
	 *
	 * @throws IOException if the fixture cannot be created
	 */
	@Test
	public void denialNotLiftedByExtraPermissions() throws IOException
	{
		// Requirement: R-I11
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("cat-extra-")).toRealPath();
			Path run = Files.createDirectory(root.resolve("run"));
			Path registry = Files.createDirectory(root.resolve("registry"));
			Path denied = Files.createDirectory(registry.resolve("secret"));
			ParentPolicy parent = new ParentPolicy(true, List.of(run, registry), List.of(denied), List.of(),
				List.of(), HostAccess.NONE, false, List.of());
			ChildNeeds needs = new ChildNeeds(run, List.of(), List.of(), Set.of()).
				with(new ExtraPermissions(List.of(denied.resolve("cache")), Set.of()));

			MissingPermissionsException refusal = expectThrows(MissingPermissionsException.class,
				() -> ChildPolicy.intersect(parent, needs));

			requireThat(refusal.getMessage(), "message").contains(denied.toString()).
				contains(ExtraPermissions.OPTION + " does not lift");
		}
	}
}
