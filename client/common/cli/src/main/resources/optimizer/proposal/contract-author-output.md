Send the complete proposal now, as one JSON object that satisfies the output schema in the first message. Write
the object as the whole message: its first character is `{` and its last character is `}`. Do not open with a lead-in
sentence or close with a note. CAT parses this message as JSON and rejects the proposal if any other text surrounds the
object.
