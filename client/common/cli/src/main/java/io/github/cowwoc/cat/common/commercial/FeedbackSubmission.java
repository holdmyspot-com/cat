package io.github.cowwoc.cat.common.commercial;

import java.util.Optional;
import java.util.UUID;
import java.time.Instant;
import java.util.List;

/**
 * Owns the explicit preview, confirmation, cancellation, and delivery states of one private feedback report.
 */
public final class FeedbackSubmission
{
	private static final String DISCLOSURE = "Review the exact payload below before sending. " +
		"Your content will only be visible to CAT staff.";

	/**
	 * Prevents construction of this static workflow owner.
	 */
	private FeedbackSubmission()
	{
	}

	/**
	 * Creates the exact payload preview without sending it.
	 *
	 * @param draft the retained-history-derived report fields
	 * @return the reviewable preview
	 */
	public static Preview preview(Draft draft)
	{
		if (draft == null)
			throw new NullPointerException("draft must not be null");
		Payload payload = new Payload(UUID.randomUUID(), draft.triggeringRequest(), draft.failedResponse(),
			draft.retainedHistory(), draft.client(), draft.harness(), draft.toolEvents(),
			draft.promptFilesExplicitlyAddedByUser());
		return new Preview(payload, DISCLOSURE, UUID.randomUUID(), null);
	}

	/**
	 * Confirms the exact preview payload for transmission.
	 *
	 * @param preview the reviewed preview
	 * @return the confirmed submission
	 */
	public static Confirmed confirm(Preview preview)
	{
		if (preview == null)
			throw new NullPointerException("preview must not be null");
		Instant confirmedAt = preview.confirmedAt();
		if (confirmedAt == null)
			confirmedAt = Instant.now();
		return new Confirmed(preview.payload(), preview.idempotencyKey(), confirmedAt);
	}

	/**
	 * Cancels a preview without sending or queuing it.
	 *
	 * @param preview the preview to cancel
	 * @return the canceled terminal state
	 */
	public static Canceled cancel(Preview preview)
	{
		if (preview == null)
			throw new NullPointerException("preview must not be null");
		return new Canceled();
	}

	/**
	 * Sends a confirmed payload to CAT's feedback endpoint.
	 */
	@FunctionalInterface
	public interface Sender
	{
		/**
		 * Sends the exact confirmed payload.
		 *
		 * @param payload the confirmed payload
		 * @param idempotencyKey the stable key that identifies this confirmed submission
		 * @return the backend receipt
		 * @throws DeliveryException if delivery fails
		 */
		Receipt send(Payload payload, UUID idempotencyKey);
	}

	/**
	 * Names a delivery attempt's terminal state.
	 */
	public enum DeliveryStatus
	{
		SUBMITTED,
		FAILED_RETAINED_LOCALLY
	}

	/**
	 * Holds locally available feedback fields before preview.
	 *
	 * @param triggeringRequest the request that triggered the failure
	 * @param failedResponse the selected failed response
	 * @param retainedHistory bounded retained context, or {@code null} when unavailable
	 * @param client the CAT client identity
	 * @param harness the harness identity
	 * @param toolEvents the selected tool events
	 * @param promptFilesExplicitlyAddedByUser files the user selected for inclusion
	 */
	public record Draft(String triggeringRequest, String failedResponse, String retainedHistory, Client client,
		Harness harness, List<String> toolEvents,
		List<PromptFile> promptFilesExplicitlyAddedByUser)
	{
		/**
		 * Validates and snapshots one draft.
		 *
		 * @param triggeringRequest the request that triggered the failure
		 * @param failedResponse the selected failed response
		 * @param retainedHistory bounded retained context, or {@code null} when unavailable
		 * @param client the CAT client identity
		 * @param harness the harness identity
		 * @param toolEvents the selected tool events
		 * @param promptFilesExplicitlyAddedByUser files the user selected for inclusion
		 */
		public Draft
		{
			triggeringRequest = requireText(triggeringRequest, "triggeringRequest");
			failedResponse = requireText(failedResponse, "failedResponse");
			if (client == null)
				throw new NullPointerException("client must not be null");
			if (harness == null)
				throw new NullPointerException("harness must not be null");
			toolEvents = copyTextList(toolEvents, "toolEvents");
			promptFilesExplicitlyAddedByUser = copyPromptFiles(promptFilesExplicitlyAddedByUser);
		}
	}

	/**
	 * Holds the exact private feedback payload.
	 *
	 * @param reportId the stable client-generated report identifier
	 * @param triggeringRequest the triggering request
	 * @param selectedResponse the selected failed response
	 * @param retainedContextSnapshot bounded retained context, or {@code null}
	 * @param client the CAT client identity
	 * @param harness the harness identity
	 * @param toolEvents the selected tool events
	 * @param promptFilesExplicitlyAddedByUser files the user selected for inclusion
	 */
	public record Payload(UUID reportId, String triggeringRequest, String selectedResponse,
		String retainedContextSnapshot, Client client, Harness harness, List<String> toolEvents,
		List<PromptFile> promptFilesExplicitlyAddedByUser)
	{
		/**
		 * Validates and snapshots one payload.
		 *
		 * @param reportId the stable client-generated report identifier
		 * @param triggeringRequest the triggering request
		 * @param selectedResponse the selected failed response
		 * @param retainedContextSnapshot bounded retained context, or {@code null}
		 * @param client the CAT client identity
		 * @param harness the harness identity
		 * @param toolEvents the selected tool events
		 * @param promptFilesExplicitlyAddedByUser files the user selected for inclusion
		 */
		public Payload
		{
			if (reportId == null)
				throw new NullPointerException("reportId must not be null");
			triggeringRequest = requireText(triggeringRequest, "triggeringRequest");
			selectedResponse = requireText(selectedResponse, "selectedResponse");
			if (client == null)
				throw new NullPointerException("client must not be null");
			if (harness == null)
				throw new NullPointerException("harness must not be null");
			toolEvents = copyTextList(toolEvents, "toolEvents");
			promptFilesExplicitlyAddedByUser = copyPromptFiles(promptFilesExplicitlyAddedByUser);
		}
	}

	/**
	 * Holds the CAT client identity recorded with feedback.
	 *
	 * @param edition the licensed CAT edition
	 * @param version the CAT release version
	 */
	public record Client(Edition edition, String version)
	{
		/**
		 * Validates one client identity.
		 *
		 * @param edition the licensed CAT edition
		 * @param version the CAT release version
		 */
		public Client
		{
			if (edition == null)
				throw new NullPointerException("edition must not be null");
			version = requireText(version, "version");
		}
	}

	/**
	 * Names a CAT client edition that may submit private feedback.
	 */
	public enum Edition
	{
		COMMERCIAL("commercial");

		private final String wireValue;

		/**
		 * Creates one supported client edition.
		 *
		 * @param wireValue the edition text used in the feedback JSON contract
		 */
		Edition(String wireValue)
		{
			this.wireValue = wireValue;
		}

		/**
		 * Returns the edition text required by the feedback JSON contract.
		 *
		 * @return the JSON edition text
		 */
		String wireValue()
		{
			return wireValue;
		}

		/**
		 * Converts one JSON edition value at the document boundary.
		 *
		 * @param value the JSON edition text
		 * @return the supported edition
		 * @throws IllegalArgumentException if {@code value} is not a supported edition
		 */
		static Edition fromWireValue(String value)
		{
			return switch (value)
			{
				case "commercial" -> COMMERCIAL;
				default -> throw new IllegalArgumentException("Unsupported client edition: " + value);
			};
		}
	}

	/**
	 * Holds the harness identity recorded with feedback.
	 *
	 * @param name the supported harness
	 * @param version the harness version
	 */
	public record Harness(HarnessName name, String version)
	{
		/**
		 * Validates one harness identity.
		 *
		 * @param name the supported harness
		 * @param version the harness version
		 */
		public Harness
		{
			if (name == null)
				throw new NullPointerException("name must not be null");
			version = requireText(version, "version");
		}
	}

	/**
	 * Names a harness supported by the commercial feedback contract.
	 */
	public enum HarnessName
	{
		CODEX("codex"),
		CLAUDE("claude");

		private final String wireValue;

		/**
		 * Creates one supported harness name.
		 *
		 * @param wireValue the harness text used in the feedback JSON contract
		 */
		HarnessName(String wireValue)
		{
			this.wireValue = wireValue;
		}

		/**
		 * Returns the harness text required by the feedback JSON contract.
		 *
		 * @return the JSON harness text
		 */
		String wireValue()
		{
			return wireValue;
		}

		/**
		 * Converts one JSON harness value at the document boundary.
		 *
		 * @param value the JSON harness text
		 * @return the supported harness name
		 * @throws IllegalArgumentException if {@code value} is not a supported harness
		 */
		static HarnessName fromWireValue(String value)
		{
			return switch (value)
			{
				case "codex" -> CODEX;
				case "claude" -> CLAUDE;
				default -> throw new IllegalArgumentException("Unsupported harness: " + value);
			};
		}
	}

	/**
	 * Holds one prompt file that the user explicitly chose to include in private feedback.
	 *
	 * @param path the project-relative file path
	 * @param content the selected file content
	 */
	public record PromptFile(String path, String content)
	{
		/**
		 * Validates one explicitly selected prompt file.
		 *
		 * @param path the project-relative file path
		 * @param content the selected file content
		 */
		public PromptFile
		{
			path = requireText(path, "path");
			if (path.startsWith("/") || path.contains("\\") || path.equals("..") || path.startsWith("../") ||
				path.contains("/../"))
			{
				throw new IllegalArgumentException("path must be project-relative without parent traversal");
			}
			content = requireText(content, "content");
		}
	}

	/**
	 * Holds a payload awaiting an explicit confirmation.
	 *
	 * @param payload the exact payload to display
	 * @param disclosure the privacy and confirmation disclosure
	 * @param idempotencyKey the stable UUID sent with every delivery attempt for this preview
	 * @param confirmedAt the preserved confirmation time, or {@code null} before confirmation
	 */
	public record Preview(Payload payload, String disclosure, UUID idempotencyKey, Instant confirmedAt)
	{
		/**
		 * Validates one preview.
		 *
		 * @param payload the exact payload to display
		 * @param disclosure the privacy and confirmation disclosure
		 * @param idempotencyKey the stable delivery key
		 * @param confirmedAt the nullable confirmation time
		 */
		public Preview
		{
			if (payload == null)
				throw new NullPointerException("payload must not be null");
			disclosure = requireText(disclosure, "disclosure");
			if (idempotencyKey == null)
				throw new NullPointerException("idempotencyKey must not be null");
		}
	}

	/**
	 * Holds the exact payload that the user authorized for transmission.
	 */
	public static final class Confirmed
	{
		private final Payload payload;
		private final UUID idempotencyKey;
		private final Instant confirmedAt;

		/**
		 * Creates one confirmed submission.
		 *
		 * @param payload the exact confirmed payload
		 * @param idempotencyKey the stable delivery key
		 * @param confirmedAt the preserved confirmation time
		 */
		private Confirmed(Payload payload, UUID idempotencyKey, Instant confirmedAt)
		{
			this.payload = payload;
			this.idempotencyKey = idempotencyKey;
			this.confirmedAt = confirmedAt;
		}

		/**
		 * Returns the persisted preview state for this confirmed submission.
		 *
		 * @return the confirmed preview
		 */
		public Preview preview()
		{
			return new Preview(payload, DISCLOSURE, idempotencyKey, confirmedAt);
		}

		/**
		 * Sends the confirmed payload and returns the backend receipt.
		 *
		 * @param sender the feedback endpoint boundary
		 * @return the backend receipt
		 * @throws DeliveryException if delivery fails
		 */
		public Receipt submit(Sender sender)
		{
			if (sender == null)
				throw new NullPointerException("sender must not be null");
			return sender.send(payload, idempotencyKey);
		}

		/**
		 * Attempts delivery while retaining an explicit local retry state on failure.
		 *
		 * @param sender the feedback endpoint boundary
		 * @return the delivery result
		 */
		public Delivery trySubmit(Sender sender)
		{
			try
			{
				return new Delivery(DeliveryStatus.SUBMITTED, Optional.of(submit(sender)), Optional.empty(),
					Optional.empty());
			}
			catch (DeliveryException e)
			{
				return new Delivery(DeliveryStatus.FAILED_RETAINED_LOCALLY, Optional.empty(), Optional.of(payload),
					Optional.of(e.getMessage()));
			}
		}
	}

	/**
	 * Holds a canceled preview that cannot be transmitted.
	 */
	public static final class Canceled
	{
		/**
		 * Creates one canceled terminal state without retaining the preview payload.
		 */
		private Canceled()
		{
		}

		/**
		 * Rejects transmission after cancellation.
		 *
		 * @param sender an unused delivery boundary
		 * @return never returns normally
		 */
		public Receipt submit(Sender sender)
		{
			if (sender == null)
				throw new NullPointerException("sender must not be null");
			throw new IllegalStateException("Canceled feedback cannot be submitted");
		}
	}

	/**
	 * Holds a successful backend receipt.
	 *
	 * @param identifier the feedback receipt identifier
	 */
	public record Receipt(String identifier)
	{
		/**
		 * Validates one receipt.
		 *
		 * @param identifier the feedback receipt identifier
		 */
		public Receipt
		{
			identifier = requireText(identifier, "identifier");
		}
	}

	/**
	 * Holds a delivery result.
	 *
	 * @param status the terminal delivery state
	 * @param receipt the receipt when submitted
	 * @param confirmedPayload the retained payload when delivery failed
	 * @param failureMessage the actionable failure message
	 */
	public record Delivery(DeliveryStatus status, Optional<Receipt> receipt, Optional<Payload> confirmedPayload,
		Optional<String> failureMessage)
	{
		/**
		 * Validates one delivery result.
		 *
		 * @param status the terminal delivery state
		 * @param receipt the receipt when submitted
		 * @param confirmedPayload the retained payload when delivery failed
		 * @param failureMessage the actionable failure message
		 */
		public Delivery
		{
			if (status == null || receipt == null || confirmedPayload == null || failureMessage == null)
				throw new NullPointerException("Delivery fields must not be null");
		}
	}

	/**
	 * Reports a feedback delivery failure without silently queuing the payload.
	 */
	public static final class DeliveryException extends RuntimeException
	{
		private static final long serialVersionUID = 0;

		/**
		 * Creates a delivery failure.
		 *
		 * @param message the actionable failure message
		 */
		public DeliveryException(String message)
		{
			super(requireText(message, "message"));
		}
	}

	/**
	 * Validates open text.
	 *
	 * @param value the value
	 * @param name the parameter name
	 * @return the unchanged value
	 */
	private static String requireText(String value, String name)
	{
		if (value == null)
			throw new NullPointerException(name + " must not be null");
		if (value.isBlank())
			throw new IllegalArgumentException(name + " must not be blank");
		return value;
	}

	/**
	 * Validates and snapshots a list of required text values.
	 *
	 * @param values the source values
	 * @param name the diagnostic field name
	 * @return an immutable value list
	 */
	private static List<String> copyTextList(List<String> values, String name)
	{
		if (values == null)
			throw new NullPointerException(name + " must not be null");
		for (String value : values)
			requireText(value, name + " value");
		return List.copyOf(values);
	}

	/**
	 * Validates and snapshots explicitly selected prompt files.
	 *
	 * @param values the source prompt files
	 * @return an immutable file list
	 */
	private static List<PromptFile> copyPromptFiles(List<PromptFile> values)
	{
		if (values == null)
			throw new NullPointerException("promptFilesExplicitlyAddedByUser must not be null");
		for (PromptFile value : values)
		{
			if (value == null)
				throw new NullPointerException("promptFilesExplicitlyAddedByUser value must not be null");
		}
		return List.copyOf(values);
	}

	/**
	 * Returns the exact disclosure shown before transmission.
	 *
	 * @return the preview disclosure
	 */
	static String expectedDisclosure()
	{
		return DISCLOSURE;
	}
}
