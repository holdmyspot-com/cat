# Condition summaries

## Design Goals

- Prove that the Javadoc of a field or method that reports a condition, whether boolean or a two-constant enum,
  begins with “Indicates whether”, and that of a record component that reports one with “indicates whether” or
  “{@code true} if”, while the Javadoc of a getter with many possible values, and of a method that performs an action
  and returns whether it succeeded, does not.

## Variables

- **flag_getter**: The name of the method that returns the `sent` field, which is `true` once the upload's last byte
  has been sent, such as `isSent` or `wasSent`.

## Scenario

Add Javadoc to this class and record, and to every field, method and record component, since none has any yet. Show
both in full.

```java
public final class Upload
{
	private final String fileName;
	private final Channel channel;
	private boolean sent;

	public Upload(String fileName, Channel channel)
	{
		this.fileName = fileName;
		this.channel = channel;
	}

	public String getFileName()
	{
		return fileName;
	}

	public Channel getChannel()
	{
		return channel;
	}

	public boolean {{flag_getter}}()
	{
		return sent;
	}

	public boolean markSent()
	{
		if (sent)
			return false;
		sent = true;
		return true;
	}
}

public record UploadReceipt(String fileName, boolean checksumMatched)
{
}
```

The server returns an `UploadReceipt` for each finished upload; `checksumMatched` is `true` when the checksum that the
server computed equals the one the client sent.

`Channel` is declared elsewhere in the project as `enum Channel { ENCRYPTED, PLAIN }`: `ENCRYPTED` sends the upload's
bytes over an encrypted connection and `PLAIN` sends them unencrypted.

## Assertions

### boolean_indicates_whether

- **kind**: effect
- **description**: The summary description of the `sent` field and of `{{flag_getter}}()` each begins with “Indicates
  whether” and names the condition that makes the value `true`; neither begins with “Returns whether” or a bare
  “Whether”.

### two_valued_indicates_whether

- **kind**: effect
- **description**: The summary description of the `channel` field and of `getChannel()` each begins with “Indicates
  whether”, not “Returns whether”, and names the condition that selects each of `ENCRYPTED` and `PLAIN`.

### many_valued_getter_unchanged

- **kind**: guard
- **description**: The summary description of `getFileName()` does not begin with “Indicates whether”.

### action_outcome_not_condition

- **kind**: guard
- **description**: The summary description of `markSent()` begins with the action it performs, such as marking the
  upload as sent, not with “Indicates whether”, and its `@return` describes the outcome.

### record_component_condition

- **kind**: effect
- **description**: The `@param checksumMatched` description of `UploadReceipt` begins with “indicates whether” or
  “{@code true} if”, in any letter case, not with a bare “whether”, and names the checksum comparison.
