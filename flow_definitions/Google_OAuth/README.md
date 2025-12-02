# Google_OAuth

This flow manages the OAuth 2.0 authentication process for Google services, facilitating the acquisition and storage of access tokens.

## Processors

-   **HandleHttpRequest** ![Built-in](https://img.shields.io/badge/built--in-grey): Starts an HTTP server to handle incoming login and callback requests.
-   **RouteOnAttribute** ![Built-in](https://img.shields.io/badge/built--in-grey): Routes requests based on the URI (e.g., `/login` or `/callback`).
-   **GoogleOAuthManager** ![Liquid Library](https://img.shields.io/badge/liquid--library-blue): Manages the interaction with Google's OAuth endpoints to generate the authorization URL and exchange the code for a token.
-   **HandleHttpResponse** ![Built-in](https://img.shields.io/badge/built--in-grey): Sends responses back to the client (e.g., redirecting to the Google login page or displaying a success message).
-   **PutFile** ![Built-in](https://img.shields.io/badge/built--in-grey): Saves the retrieved token to a file.
-   **Update HTTP-Header-Location** ![Built-in](https://img.shields.io/badge/built--in-grey): Sets the `Location` header for redirection.
-   **ReplaceText** ![Built-in](https://img.shields.io/badge/built--in-grey): Prepares the success HTML response body.

## Configuration

### HandleHttpRequest
-   **Listening Port**: The port on which the HTTP server listens (e.g., `8999`).
-   **Allowed Paths**: Paths to handle, typically `/login` and `/callback`.

### GoogleOAuthManager
-   Configured to handle the OAuth handshake.

### PutFile
-   **Directory**: Directory where the token file will be saved (e.g., `/files/`).

## Usage

1.  Start the flow.
2.  Navigate to `http://<nifi-host>:8999/login` in your browser.
3.  Complete the Google authentication.
4.  The token will be saved to `/files/token.json`, which can then be used by other flows like `GetGoogleMail` or `ListGMailInbox`.
