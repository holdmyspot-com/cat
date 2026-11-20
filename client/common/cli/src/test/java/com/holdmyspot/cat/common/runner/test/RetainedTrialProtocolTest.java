package com.holdmyspot.cat.common.runner.test;

import com.holdmyspot.cat.common.TemporaryDirectories;
import com.holdmyspot.cat.common.runner.RequestArchive;
import com.holdmyspot.cat.common.runner.RequestLimits;
import com.holdmyspot.cat.common.runner.RetainedTrialRequest;
import com.holdmyspot.cat.common.runner.RetainedTrialResponse;
import com.holdmyspot.cat.common.runner.RetainedTrialResponse.HarnessSession;
import com.holdmyspot.cat.common.runner.RetainedTrialResponse.Identity;
import com.holdmyspot.cat.common.runner.RetainedTrialResponse.Status;
import com.holdmyspot.cat.common.runner.WorktreeResponse;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;
import tools.jackson.databind.json.JsonMapper;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

/**
 * Tests the request archives and replies exchanged during recovery of a retained trial.
 */
public final class RetainedTrialProtocolTest
{
	private static final UUID LAUNCH_ID = UUID.fromString("00000001-0001-0001-0001-000000000001");
	private static final UUID SLOT_GENERATION = UUID.fromString("00000002-0002-0002-0002-000000000002");
	private static final Identity SESSION = new Identity(SLOT_GENERATION,
		Optional.of(new HarnessSession("harness1", "Session:123")));
	private static final String RESPONSE_ID = RetainedTrialRequest.PREFIX + "00000003-0003-0003-0003-000000000003";
	private static final String REQUEST_FILE = "request.json";
	private final JsonMapper json = JsonMapper.builder().build();

	/**
	 * Creates the test.
	 */
	public RetainedTrialProtocolTest()
	{
	}

	/**
	 * Supplies request forms with and without an observed slot and harness session.
	 *
	 * @return status, unbound discard and bound discard requests
	 */
	@DataProvider
	public Object[][] requests()
	{
		return new Object[][]{
			{RetainedTrialRequest.status(LAUNCH_ID)},
			{RetainedTrialRequest.discard(LAUNCH_ID, Optional.empty())},
			{RetainedTrialRequest.discard(LAUNCH_ID, Optional.of(new Identity(SLOT_GENERATION, Optional.empty())))},
			{RetainedTrialRequest.discard(LAUNCH_ID, Optional.of(SESSION))}
		};
	}

	/**
	 * A request decoded from the same archive format as a launch names the original launch UUID and exact identity.
	 *
	 * @param request the operation and original launch to recover
	 * @throws IOException if a test directory or archive cannot be accessed
	 */
	@Test(dataProvider = "requests")
	public void roundTripsRequestArchive(RetainedTrialRequest request) throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("retained-trial-request"));
			String id = request.publish(json, root);
			Path inputs = Files.createDirectory(root.resolve("inputs"));
			RequestArchive.unpack(root.resolve(id + ".tar"), inputs, RequestLimits.IN_NESTED_SESSION);
			RetainedTrialRequest result = RetainedTrialRequest.read(json, inputs);
			requireThat(result, "result").isEqualTo(request, "request");
		}
	}

	/**
	 * Supplies representations invalid for status or discard requests.
	 *
	 * @return request fields following the version and before the closing object delimiter
	 */
	@DataProvider
	public Object[][] malformedRequests()
	{
		return new Object[][]{
			{"""
				"operation": "STATUS", "request_id": "%s"
				""".formatted(LAUNCH_ID)},
			{"""
				"operation": "status", "request_id": "1-1-1-1-1"
				"""},
			{"""
				"operation": "status", "request_id": 1
				"""},
			{"""
				"operation": "status", "request_id": "%s", "identity": null
				""".formatted(LAUNCH_ID)},
			{"""
				"operation": "status", "request_id": "%s", "identity": {"slot_generation": "%s"}
				""".formatted(LAUNCH_ID, SLOT_GENERATION)},
			{"""
				"operation": "discard", "request_id": "%s", "identity": {"slot_generation": "not-a-uuid"}
				""".formatted(LAUNCH_ID)},
			{"""
				"operation": "discard", "request_id": "%s",
				"identity": {"slot_generation": "00000000-0000-0000-0000-00000000000A"}
				""".formatted(LAUNCH_ID)},
			{"""
				"operation": "discard", "request_id": "%s",
				"identity": {"slot_generation": "%s", "session_id": "Session:123"}
				""".formatted(LAUNCH_ID, SLOT_GENERATION)},
			{"""
				"operation": "discard", "request_id": "%s",
				"identity": {"slot_generation": "%s", "harness": "harness1", "session_id": "../session"}
				""".formatted(LAUNCH_ID, SLOT_GENERATION)},
			{"""
				"operation": "discard", "request_id": "%s",
				"identity": {"slot_generation": "%s", "slot_path": "/private/slot"}
				""".formatted(LAUNCH_ID, SLOT_GENERATION)},
			{"""
				"operation": "status", "operation": "discard", "request_id": "%s"
				""".formatted(LAUNCH_ID)}
		};
	}

	/**
	 * The archive reader refuses malformed operations, UUIDs, identities and extra authority.
	 *
	 * @param fields the malformed request fields
	 * @throws IOException if the reader rejects the request or the fixture cannot be written
	 */
	@Test(dataProvider = "malformedRequests", expectedExceptions = IOException.class,
		expectedExceptionsMessageRegExp = ".*retained trial.*")
	public void rejectsMalformedRequest(String fields) throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path inputs = cleanup.add(Files.createTempDirectory("retained-trial-request"));
			Files.writeString(inputs.resolve(REQUEST_FILE), """
				{"format_version": 1, %s}
				""".formatted(fields));
			RetainedTrialRequest.read(json, inputs);
		}
	}

	/**
	 * Supplies the status and discard replies callers need for recovery.
	 *
	 * @return request and the expected response associated with it
	 */
	@DataProvider
	public Object[][] replies()
	{
		RetainedTrialRequest inspect = RetainedTrialRequest.status(LAUNCH_ID);
		RetainedTrialRequest discard = RetainedTrialRequest.discard(LAUNCH_ID, Optional.of(SESSION));
		return new Object[][]{
			{inspect, new RetainedTrialResponse(LAUNCH_ID, Status.BUSY, Optional.empty(), OptionalInt.empty())},
			{inspect, new RetainedTrialResponse(LAUNCH_ID, Status.UNSAFE,
				Optional.of(new Identity(SLOT_GENERATION, Optional.empty())), OptionalInt.empty())},
			{inspect, new RetainedTrialResponse(LAUNCH_ID, Status.READY, Optional.of(SESSION), OptionalInt.of(0))},
			{inspect, new RetainedTrialResponse(LAUNCH_ID, Status.READY, Optional.of(SESSION), OptionalInt.of(2))},
			{discard, new RetainedTrialResponse(LAUNCH_ID, Status.DISCARDED, Optional.empty(), OptionalInt.empty())},
			{discard, new RetainedTrialResponse(LAUNCH_ID, Status.ABSENT, Optional.empty(), OptionalInt.empty())}
		};
	}

	/**
	 * An authenticated response directory delivers the status, identity and completed-turn count to the caller.
	 *
	 * @param request  the request that the client published
	 * @param response the broker's response to the request
	 * @throws IOException if the response cannot be written or read
	 */
	@Test(dataProvider = "replies")
	public void roundTripsResponse(RetainedTrialRequest request, RetainedTrialResponse response) throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("retained-trial-response"));
			Path directory = Files.createDirectory(root.resolve(RESPONSE_ID));
			requireThat(RetainedTrialResponse.read(json, directory, request), "missingReply").isEmpty();
			response.write(json, RESPONSE_ID, directory);
			RetainedTrialResponse result = RetainedTrialResponse.read(json, directory, request).orElseThrow();
			requireThat(result, "result").isEqualTo(response, "response");
		}
	}

	/**
	 * Supplies responses whose discriminators, fields or target contradict the pending status request.
	 *
	 * @return the echoed envelope ID, original launch UUID and status fields for each invalid response
	 */
	@DataProvider
	public Object[][] malformedReplies()
	{
		String identity = """
			"identity": {"slot_generation": "%s", "harness": "harness1", "session_id": "Session:123"}
			""".formatted(SLOT_GENERATION);
		return new Object[][]{
			{RESPONSE_ID, LAUNCH_ID, """
				"status": "unknown"
				"""},
			{RESPONSE_ID, LAUNCH_ID, """
				"status": "ready", "completed_turns": 1
				"""},
			{RESPONSE_ID, LAUNCH_ID, """
				"status": "ready", "completed_turns": -1, %s
				""".formatted(identity)},
			{RESPONSE_ID, LAUNCH_ID, """
				"status": "ready", "completed_turns": 0.0, %s
				""".formatted(identity)},
			{RESPONSE_ID, LAUNCH_ID, """
				"status": "ready", "completed_turns": 2147483648, %s
				""".formatted(identity)},
			{RESPONSE_ID, LAUNCH_ID, """
				"status": "ready", "completed_turns": 0, "identity": {"slot_generation": "%s"}
				""".formatted(SLOT_GENERATION)},
			{RESPONSE_ID, LAUNCH_ID, """
				"status": "busy", "completed_turns": null
				"""},
			{RESPONSE_ID, LAUNCH_ID, """
				"status": "absent", %s
				""".formatted(identity)},
			{RESPONSE_ID, LAUNCH_ID, """
				"status": "discarded"
				"""},
			{RetainedTrialRequest.PREFIX + LAUNCH_ID, LAUNCH_ID, """
				"status": "absent"
				"""},
			{RESPONSE_ID, SLOT_GENERATION, """
				"status": "absent"
				"""}
		};
	}

	/**
	 * A status reply with invalid fields or a different request identity cannot authorize recovery.
	 *
	 * @param replyId the envelope ID in the response
	 * @param launch  the original launch UUID in the response
	 * @param fields  the response fields
	 * @throws IOException if the reader rejects the response or the fixture cannot be written
	 */
	@Test(dataProvider = "malformedReplies", expectedExceptions = IOException.class,
		expectedExceptionsMessageRegExp = ".*retained trial.*")
	public void rejectsMalformedReply(String replyId, UUID launch, String fields) throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("retained-trial-response"));
			Path directory = Files.createDirectory(root.resolve(RESPONSE_ID));
			Files.writeString(directory.resolve(WorktreeResponse.FILE_NAME), """
				{"format_version": 1, "id": "%s", "request_id": "%s", %s}
				""".formatted(replyId, launch, fields));
			RetainedTrialResponse.read(json, directory, RetainedTrialRequest.status(LAUNCH_ID));
		}
	}
}
