itch.io API reference for GameNative integration

Documented endpoints (https://itch.io/docs/api/serverside):
- GET /profile (scope profile:me) — verifies the personal API key and returns the user.
- GET /profile/owned-keys (scope profile:owned) — paginated list of owned download keys; each names its game.

Reverse-engineered but stable endpoints (same ones itch-dl relies on):
- GET /games/{game_id}/uploads?download_key_id={key} — uploads (platform builds) for an owned game.
- GET /uploads/{upload_id}/download?download_key_id={key} — resolves the time-limited download URL for an upload.

Auth: user's own personal Bearer API key from https://itch.io/user/settings (API Keys). No OAuth.

Refs: itch.io docs/api/serverside; itch-dl (https://github.com/DragoonAethis/itch-dl).
