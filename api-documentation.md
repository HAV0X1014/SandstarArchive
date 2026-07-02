I fed WebServer.java into gemini and this is what it spat out for documentation. It is correct, but AI generated. I just didn't feel like writing out API docs by hand again.

# Sandstar Archive API Documentation

This document outlines the REST API endpoints provided by the Sandstar Archive web server.

## Table of Contents
- [Authentication & Authorization](#authentication--authorization)
- [Public Data Endpoints](#public-data-endpoints)
- [Authentication Endpoints](#authentication-endpoints)
- [User Endpoints (Requires Login)](#user-endpoints-requires-login)
- [Editor Endpoints (Requires Write Role)](#editor-endpoints-requires-write-role)
- [Admin Endpoints (Requires Execute Role)](#admin-endpoints-requires-execute-role)
- [Integration & External Endpoints](#integration--external-endpoints)

---

## Authentication & Authorization

The API uses token-based authentication. The token can be passed in two ways:
1.  **Cookie:** `SESSION_ID=<token>`
2.  **Header:** `Authorization: Bearer <token>`

### Role-Based Access Control (RBAC)
User permissions are divided into three levels:
*   **Level 1 (Read / Default):** Access to public data and basic user account management (`/api/me`).
*   **Level 2 (Editor / "Write" Role):** Allows modification of posts and media metadata. 
*   **Level 3 (Admin / "Execute" Role):** Full access to user management, account scraping, artist mapping, and invite key generation.

---

## Public Data Endpoints
*These routes do not require authentication.*

### `GET /api/config`
Retrieves the list of available safety and content ratings (excluding "Waiting").
*   **Response:** `{ "safety": ["Safe", "NSFW", ...], "content": ["Original", "Fanart", ...] }`

### `GET /api/posts`
Retrieves a paginated list of global posts.
*   **Query Parameters:**
    *   `limit` (int, default `20`)
    *   `offset` (int, default `0`)
    *   `content` (string/list, optional) - Content rating filter(s).
    *   `safety` (string/list, optional) - Safety rating filter(s).
    *   `sort` (string, optional)
*   **Response:** List of Post objects.

### `GET /api/posts/{id}`
Retrieves details for a specific post.
*   **Path Parameter:** `id` (Post ID string)

### `GET /api/accounts`
Retrieves a list of tracked Twitter accounts.
*   **Query Parameters:**
    *   `q` (string, optional) - Search query.
    *   `status` (string, optional) - Filter by status (e.g., `Active`).

### `GET /api/accounts/{id}`
Retrieves details for a specific account.
*   **Path Parameter:** `id` (Twitter Account ID string)

### `GET /api/accounts/{id}/posts`
Retrieves a paginated list of posts for a specific account.
*   **Query Parameters:** Inherits the same query parameters as `/api/posts`.

### `GET /api/artists`
Retrieves a list of all artists.
*   **Query Parameters:**
    *   `q` (string, optional) - Search query.

### `GET /api/artists/{id}`
Retrieves artist details by database ID.
*   **Path Parameter:** `id` (Integer)

### `GET /api/artists/slug/{name}`
Retrieves artist details by their exact name/slug.
*   **Path Parameter:** `name` (String)

### `POST /api/search/image`
Reverse image search using perceptual hashing (PHASH).
*   **Content-Type:** `multipart/form-data` (Max size: 10MB)
*   **Form Data:**
    *   `image` (File) - The image to search for.
*   **Query Parameters:**
    *   `threshold` (int, default `10`, max `15`, min `0`) - Maximum Hamming distance for a match.
*   **Response:** Array of matched media objects sorted by distance.

### `GET /api/media/{id}`
Retrieves metadata for a specific media item.
*   **Path Parameter:** `id` (Integer)

### `GET /api/media/{id}/thumbnail`
Retrieves or generates a cached 400px wide JPEG thumbnail for a media item. Streams video thumbnails as redirects.
*   **Path Parameter:** `id` (Integer)

---

## Authentication Endpoints

### `POST /api/auth/register`
Registers a new user.
*   **JSON Body:**
    *   `username` (string, required)
    *   `email` (string, required)
    *   `password` (string, required, min 6 chars)
    *   `inviteKey` (string, required)

### `POST /api/login`
Authenticates a user and establishes a session.
*   **JSON Body:**
    *   `identifier` (string) - Username or Email.
    *   `password` (string)
*   **Response:** Sets `SESSION_ID` cookie (90 days). Returns `{ "success": true, "role": "...", "username": "..." }`

### `POST /api/logout`
Clears the active user session cookie.

### `GET /api/auth/me`
Checks if the current session is valid. Returns user basic info without full profile lookup.

---

## User Endpoints (Requires Login)

### `GET /api/me`
Retrieves the fully detailed profile of the currently authenticated user.

### `PATCH /api/me`
Updates the profile of the current user. **Note:** Will clear the current session and require re-authentication.
*   **JSON Body (All fields optional):**
    *   `username` (string)
    *   `email` (string)
    *   `password` (string)
    *   `aboutMe` (string)
*   **Response:** `{ "success": true, "requiresReauth": true }`

### `DELETE /api/me`
Permanently deletes the current user account and invalidates all active sessions.

---

## Editor Endpoints (Requires Write Role)

### `PATCH /api/media/{id}`
Updates metadata for a specific media item.
*   **JSON Body (Optional fields):**
    *   `caption` (string)
    *   `contentRating` (string)
    *   `safetyRating` (string)

### `PATCH /api/posts/{id}`
Updates metadata for a specific post.
*   **JSON Body (Optional fields):**
    *   `contentRating` (string)
    *   `safetyRating` (string)

---

## Admin Endpoints (Requires Execute Role)

### `POST /api/accounts`
Queues an asynchronous task to register a new account for tracking.
*   **JSON Body:**
    *   `handle` (string) - Twitter screen name.
    *   `artist` (string) - Associated artist name.
    *   `download` (boolean) - Enable automatic downloading.
    *   `safety` (string) - Default safety rating.

### `PATCH /api/accounts/{handle}`
Edits an existing tracked account.
*   **JSON Body (Optional fields):**
    *   `displayName` (string)
    *   `accountStatus` (string)
    *   `isProtected` (boolean)
    *   `downloadStatus` (boolean)
    *   `safetyRating` (string)

### `DELETE /api/accounts/{handle}`
Deletes a tracked account from the database.

### `PATCH /api/artists/{name}`
Updates an artist's profile.
*   **JSON Body:**
    *   `description` (string)

### `POST /api/artists/{name}/aliases`
Adds an alias to an existing artist.
*   **JSON Body:**
    *   `aliasName` (string)
    *   `safetyRating` (string)

### `POST /api/tasks/scrape`
Queues a background task to scrape a specific Twitter post by ID.
*   **JSON Body:**
    *   `postId` (string)

### `POST /api/tasks/download`
Queues a background task to download a post via its URL.
*   **JSON Body:**
    *   `url` (string) - Full Twitter status URL.
    *   `contentRating` (string)
    *   `safetyRating` (string)

### `GET /api/keys`
Retrieves a paginated list of invite keys.
*   **Query Parameters:** `page` (int, default `1`)

### `POST /api/keys`
Generates a new invite key.
*   **JSON Body:**
    *   `role` (string)
    *   `maxUses` (int, default `-1` for unlimited)
    *   `expiresAt` (long, epoch time, default `-1` for no expiry)

### `PATCH /api/keys/{id}`
Updates an existing invite key.
*   **JSON Body:** Same as `POST /api/keys`.

### `DELETE /api/keys/{id}`
Deletes an invite key.

### `GET /api/users`
Retrieves a paginated list of registered users.
*   **Query Parameters:** `page` (int, default `1`)

### `PATCH /api/users/{id}`
Updates user administrative details (e.g., banning, role changes). Will terminate all active sessions for the user.
*   **JSON Body:**
    *   `role` (string)
    *   `banned` (boolean)
    *   `note` (string)

### `GET /api/users/{id}/token`
Retrieves the active bot API token for a specific user.

### `POST /api/users/{id}/token`
Generates a new bot API token for a user.

### `DELETE /api/users/{id}/token`
Revokes the current bot API token for a user.

---

## Integration & External Endpoints

The server also exposes specific routes tailored for social media embeds and federation formats.

*   **`GET /oembed`**
    Serves rich embedded data for external applications like Discord, Slack, or Telegram based on a provided `url` query parameter.
*   **`GET /activity/{id}`**
    Serves `application/activity+json` representations of posts designed to mock Mastodon instances for Discord parsing.
*   **`GET /activity/users/{screenName}`**
    Serves `application/activity+json` representations of a user/actor.
*   **`GET /post/{id}` & `GET /media/{id}`**
    These endpoints serve the Single Page Application (SPA) `index.html` but dynamically inject OpenGraph, Twitter Card, and ActivityPub meta tags tailored to the requested resource for precise unfurling in external chat clients.
