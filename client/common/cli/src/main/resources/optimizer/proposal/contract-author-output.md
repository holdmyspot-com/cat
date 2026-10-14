Write the complete proposal now to the file `{{output_file}}` in the working directory, as one JSON object that
satisfies the output schema in the first message. CAT reads the proposal only from that file, so do not repeat it in
your reply. Before you end the turn, check that the file parses as JSON, for example with `jq empty {{output_file}}`.
