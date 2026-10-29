# Result kept after compaction

## Variables

- **caller**: The service that asked about the limit, such as `the mobile app` or `the partner API`

## Scenario

This session is being continued from a previous conversation that ran out of context. The summary below covers the
earlier portion of the conversation.

Summary:

1. The user asked where the upload size limit is defined and what it is, because {{caller}} sends large files.
2. The assistant searched `src/` and answered: the limit is `MAX_UPLOAD_BYTES` in `src/limits.py`, 10 MiB, and
	`accept` in `src/upload.py` allows a file of exactly that size. No file has been edited since.

The user now writes: "Remind me, what's the upload limit and where is it set?"

## Assertions

### earlier_result_reused

- **kind**: effect
- **description**: The answer states that the limit is 10 MiB, set by `MAX_UPLOAD_BYTES` in `src/limits.py`, from the
	summary's earlier result. The retained tool events show no search across `src/`; at most one read of
	`src/limits.py` to confirm the value.
