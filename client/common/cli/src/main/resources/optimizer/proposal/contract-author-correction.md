CAT refused the proposal in `{{output_file}}` because of these problems:

{{problems}}

Each problem starts with the JSON path of the value it concerns, such as `$.tests[2].assertions[0]`, or names the test
or assertion. Correct every problem by editing only the values it concerns, and keep the rest of the file unless a
correction requires changing it. Read only the parts of the file that you need, for example with
`jq '.tests[2].assertions[0]' {{output_file}}`. Then check that the file still parses as JSON. CAT reads the proposal
only from the file, so do not repeat it in your reply.
