package com.holdmyspot.cat.common.runner.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.FileLocks;
import com.holdmyspot.cat.common.TemporaryDirectories;
import com.holdmyspot.cat.common.runner.OwnerPolicyClient;
import com.holdmyspot.cat.common.runner.OwnerPolicyRequest;
import com.holdmyspot.cat.common.runner.WorktreeRequestClient;
import com.holdmyspot.cat.common.runner.fakes.test.RequestingNestedSession;
import com.holdmyspot.cat.common.runner.policy.HostAccess;
import com.holdmyspot.cat.common.runner.policy.ParentPolicy;
import com.holdmyspot.cat.common.runner.policy.SessionRefusedException;
import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * Tests the request for the policy of the session that runs a command, which a command inside the session's sandbox
 * sends because it cannot read the policy itself.
 */
public final class OwnerPolicyRequestTest
{
	private final JsonMapper json = JsonMapper.builder().build();

	/**
	 * Creates the test.
	 */
	public OwnerPolicyRequestTest()
	{
	}

	/**
	 * Returns policies that differ in how they grant hosts and whether they are sandboxed.
	 *
	 * @return the policies
	 */
	@DataProvider
	public static Object[][] policies()
	{
		Path root = FileSystems.getDefault().getRootDirectories().iterator().next();
		return new Object[][]
		{
			{ParentPolicy.UNSANDBOXED},
			{new ParentPolicy(true, List.of(root.resolve("write")), List.of(root.resolve("write/denied")),
				List.of(root.resolve("secret")), List.of(root.resolve("secret/open")),
				HostAccess.listed(Set.of("api.example.com", "*.example.org")), true, List.of(root.resolve("source")))},
			{new ParentPolicy(true, List.of(), List.of(), List.of(), List.of(), HostAccess.NONE, false, List.of())}
		};
	}

	/**
	 * Verifies that a policy that is written as an answer is read back as it was, including its granted hosts.
	 *
	 * @param policy the policy
	 * @throws IOException if the document cannot be read
	 */
	@Test(dataProvider = "policies")
	public void policiesSurviveTheAnswer(ParentPolicy policy) throws IOException
	{
		// Requirement: R-L21
		String document = OwnerPolicyRequest.encode(json, List.of(policy, policy));

		List<ParentPolicy> decoded = OwnerPolicyRequest.decode(json, document);

		requireThat(decoded, "decoded").isEqualTo(List.of(policy, policy));
	}

	/**
	 * Returns documents that no receiver writes.
	 *
	 * @return the documents
	 */
	@DataProvider
	public static Object[][] malformedDocuments()
	{
		return new Object[][]
		{
			{"not json"},
			{"{\"format_version\":2,\"policies\":[]}"},
			{"{\"format_version\":1}"},
			{"{\"format_version\":1,\"policies\":[{\"sandboxed\":true}]}"},
			{"{\"format_version\":1,\"policies\":[{\"sandboxed\":true,\"allow_local_binding\":false," +
				"\"write_roots\":[\"relative\"],\"write_denials\":[],\"read_denials\":[],\"read_reallowances\":[]," +
				"\"protected_sources\":[],\"hosts\":{\"kind\":\"none\"}}]}"},
			{"{\"format_version\":1,\"policies\":[{\"sandboxed\":true,\"allow_local_binding\":false," +
				"\"write_roots\":[],\"write_denials\":[],\"read_denials\":[],\"read_reallowances\":[]," +
				"\"protected_sources\":[],\"hosts\":{\"kind\":\"some\"}}]}"}
		};
	}

	/**
	 * Verifies that a command refuses a document that is not the answer that a receiver writes, rather than bounding a
	 * check program by a policy that it guessed.
	 *
	 * @param document the document
	 */
	@Test(dataProvider = "malformedDocuments")
	public void malformedAnswerIsRefused(String document)
	{
		// Requirement: R-L21
		try
		{
			OwnerPolicyRequest.decode(json, document);
			throw new AssertionError("The document was read: " + document);
		}
		catch (IOException e)
		{
			requireThat(e.getMessage(), "message").contains("owner policy");
		}
	}

	/**
	 * Verifies that a nested session's runner states the policy that it applied to the session's sandbox, and refuses
	 * to state any when it applied none that it can state.
	 *
	 * @throws IOException if a file cannot be written or the answer cannot be read
	 */
	@Test(timeOut = 30_000)
	public void nestedRunnerStatesItsPolicyOrRefuses() throws IOException
	{
		// Requirement: R-L21
		try (TemporaryDirectories scope = new TemporaryDirectories())
		{
			Path root = scope.add(Files.createTempDirectory("owner-policy-nested")).toRealPath();
			ParentPolicy owner = new ParentPolicy(true, List.of(root.resolve("session")), List.of(),
				List.of(root.resolve("secret")), List.of(), HostAccess.listed(Set.of("api.example.com")), false,
				List.of());
			try (RequestingNestedSession stated = new RequestingNestedSession(root.resolve("stated"), new FileLocks(), 1,
				owner);
			     RequestingNestedSession silent = new RequestingNestedSession(root.resolve("silent"), new FileLocks(), 1))
			{
				requireThat(client(stated.requests()).read(), "stated").isEqualTo(List.of(owner));

				try
				{
					client(silent.requests()).read();
					throw new AssertionError("The runner stated a policy that it was never given");
				}
				catch (SessionRefusedException e)
				{
					requireThat(e.getMessage(), "message").contains("states no policy");
				}
			}
		}
	}

	/**
	 * Creates a client that sends its requests into a nested session's requests directory.
	 *
	 * @param requests the nested session's requests directory
	 * @return the client
	 */
	private OwnerPolicyClient client(Path requests)
	{
		return new OwnerPolicyClient(() -> Optional.of(new NestedChannel(requests)), json, Thread::yield);
	}

	/**
	 * The channel to the runner of a nested session.
	 *
	 * @param requests the nested session's requests directory
	 */
	private record NestedChannel(Path requests) implements WorktreeRequestClient.Channel
	{
		@Override
		public boolean isServed()
		{
			return true;
		}

		@Override
		public boolean removesResponses()
		{
			return false;
		}

		@Override
		public void close()
		{
		}
	}
}
