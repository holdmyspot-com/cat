# Login polling beside offline authorization

## Design Goals

- Prove that adding device-login polling to an account class that already authorizes offline product use from a stored
	license puts the polling in its own class that the account delegates to, and leaves the authorization code unchanged.

## Variables

- **login_host**: The host name of the login server, such as `login.example.com` or `auth.acme.test`.

## Scenario

Users should be able to sign in to their `Account` on this machine with a device login. The login server at
`https://{{login_host}}` works like this:

1. `POST /device/code` with the form field `email` answers with form fields `device_code`, `user_code`,
	`verification_uri` and `interval` (seconds).
2. The user opens `verification_uri` in a browser and enters `user_code`.
3. Meanwhile the client sends `POST /device/token` with the form field `device_code`, waiting `interval` seconds before
	each request. Until the user approves, the answer is `error=authorization_pending`; `error=slow_down` means the
	client must add 5 seconds to its interval; `error=access_denied` and `error=expired_token` end the login. Once the
	user approves, the answer is `access_token=...`.

All answers are `application/x-www-form-urlencoded`. Give `Account` a way to sign in like this: it shows the user the
code and the address to open, waits until the login ends, and returns the access token or fails. Use
`java.net.http.HttpClient`; the project has no other libraries.

## Assertions

### polling_in_focused_class

- **kind**: effect
- **description**: The requests to `/device/code` and `/device/token` and the loop that waits and polls until the
	login ends are in a class other than `Account`, and `Account`'s sign-in operation delegates to that class rather
	than performing the requests or the loop itself.

### authorization_unchanged

- **kind**: effect
- **description**: The diff leaves `Account.mayUseOffline`, the `GRACE_PERIOD` constant, `License.java` and
	`LicenseStore.java` unchanged; any change to `Account.java` only adds the sign-in operation, the collaborator it
	delegates to, and the imports or constructor parameter that collaborator needs.

### device_login_works

- **kind**: guard
- **description**: The added code sends the account's email to `https://{{login_host}}/device/code`, shows the user
	code and verification address, then polls `https://{{login_host}}/device/token` with the device code, waiting the
	interval before each request. It keeps polling on `authorization_pending`, adds 5 seconds to the interval on
	`slow_down`, fails on `access_denied` or `expired_token`, and returns the access token once one is answered.

### guidance_selection

- **kind**: effect
- **description**: The retained events establish that the executor selects `rules/include/class-design.md` before
	its first governed action.
