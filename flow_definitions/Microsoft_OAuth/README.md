# Microsoft_OAuth

This flow implements a Microsoft OAuth 2.0 authentication mechanism using NiFi. It handles the entire OAuth lifecycle, from directing the user to the login page to exchanging the authorization code for an access token and saving it.

## Overview

The flow consists of two main parts handled by a local HTTP server listening on port 8999:

1.  **Login Initiation (`/login`)**: Redirects the user to Microsoft's OAuth 2.0 authorization endpoint.
2.  **Callback Handling (`/callback`)**: Receives the authorization code from Microsoft, exchanges it for an access token, saves the token to a file, and displays a success message to the user.

## Processors

### HTTP Handling
-   **HandleHttpRequest** ![Built-in](https://img.shields.io/badge/built--in-grey): Listens on port `8999` for incoming HTTP requests.
-   **RouteOn http.request.uri** ![Built-in](https://img.shields.io/badge/built--in-grey): Routes requests based on the URI path (`/login` or `/callback`).
-   **HandleHttpResponse 307** ![Built-in](https://img.shields.io/badge/built--in-grey): Redirects the user to the Microsoft login page.
-   **HandleHttpResponse 200** ![Built-in](https://img.shields.io/badge/built--in-grey): Returns a "Login Successful" HTML page to the user.

### OAuth Logic
-   **MicrosoftOAuthManager** ![Liquid Library](https://img.shields.io/badge/liquid--library-blue): This custom Python processor is used in two places:
    -   To generate the authorization URL for the initial redirect.
    -   To exchange the authorization code for an access token.

### Token Management
-   **Update filename** ![Built-in](https://img.shields.io/badge/built--in-grey): Sets the filename for the token file to `microsoft-token.json`.
-   **PutFile** ![Built-in](https://img.shields.io/badge/built--in-grey): Saves the obtained access token to `/files/MicrosoftOAuthManager`.

### Response Formatting
-   **Update Location** ![Built-in](https://img.shields.io/badge/built--in-grey): Sets the `Location` header for the 307 redirect.
-   **ReplaceText** ![Built-in](https://img.shields.io/badge/built--in-grey): Generates a user-friendly HTML success page.
-   **Update Content-Type** ![Built-in](https://img.shields.io/badge/built--in-grey): Sets the `Content-Type` header to `text/html`.

## Configuration

-   **Listening Port**: 8999
-   **Endpoints**:
    -   `http://localhost:8999/login`
    -   `http://localhost:8999/callback`
-   **Token Storage Path**: `/files/MicrosoftOAuthManager/microsoft-token.json`
-   **Microsoft OAuth Settings** (configured in `MicrosoftOAuthManager`):
    -   Client ID
    -   Tenant ID
    -   Scopes (`User.Read`, `Mail.ReadWrite`)
    -   Redirect URI (`http://localhost:8999/callback`)
