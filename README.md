# Figura Limit Unlocked (`figura-link`)

A lightweight, seamless companion mod for **Minecraft Figura** that unlocks avatar upload limits (> 100 KB) directly over Minecraft's existing network connection.

No port forwarding, no secondary web ports, and no extra Docker setups required. Everything runs inside Minecraft!

---

## Features

- **Unlock Avatar Upload Limits:** Allows uploading custom avatars larger than 100 KB (default set to **5 MB**, configurable to 10 MB, 20 MB, etc.).
- **Total Server Storage Guard:** Automatically prevents your server disk from filling up by setting a global avatar limit (e.g. 50 GB).
- **Zero Port Forwarding Needed:** Uses Minecraft's native custom packet channels. Works directly over port 25565!
- **Automatic Sync:** When players join the server, the server transmits the unlocked limit to their client automatically. When leaving, their client returns to standard limits.
- **Auto-Replacing Avatars:** Just like vanilla Figura, when a player changes avatars, the old avatar is automatically superseded.

---

## Configuration (`figura-limit.properties`)

When the server starts with this mod installed, it automatically generates `config/figura-limit.properties` (or in server root):

```properties
# ========================================================
# Figura Limit Unlocked Configuration
# ========================================================
# Maximum avatar file size allowed on this server
# Supports units like: 5MB, 10MB, 20MB, or raw bytes (e.g. 5242880)
max-avatar-size=5MB

# Total storage limit for all avatars on this server
# Supports units like: 50GB, 100GB, or raw bytes (e.g. 53687091200)
total-storage-limit=50GB
```

---

## How to Install

### On the Server (Fabric / Forge)
1. Install the official **Figura** mod in your server's `mods/` directory.
2. Place `figuralink.jar` in your server's `mods/` directory.
3. Start the server. You can adjust `max-avatar-size` in `config/figura-limit.properties` at any time!

### On the Client
1. Install the official **Figura** mod.
2. Place `figuralink.jar` in your client's `mods/` directory.
3. Join your server and enjoy uploading large avatars without the 100 KB restriction!

---

## Supported Versions
- **Minecraft 1.20.1:** Fabric & Forge
- **Minecraft 1.21.1:** Fabric & Forge
