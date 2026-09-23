# L2Walker OOG Epilogue (protocol 152) — open scaffold

This directory is a **minimal, open-source out-of-game (OOG) client scaffold** for private Lineage 2 **Gracia Epilogue** servers that speak **protocol 152**. It is **not** L2Walker / towalker proprietary software and does not ship cracked binaries.

Use it as a starting point to implement login, game handshake, and bot logic on your own server, with your own credentials.

## Requirements

- JDK 21+
- A Gracia Epilogue-compatible private server (protocol 152 login handshake)

## Build

```bash
cd l2walker-oog-epilogue
./gradlew build
```

Run (loads `config/set.ini` by default):

```bash
./gradlew run --args="config/set.ini"
```

## Configuration (`set.ini`)

See `config/set.ini.example`. Key fields:

| Key | Example | Notes |
|-----|---------|--------|
| `ProtocolVersion` | `152` | Gracia Epilogue update 1 (common on l2off extenders such as MyExt64) |
| `LoginServerIP` | `127.0.0.1` | Your login server host |
| `LoginServerPort` | `2106` | Default L2 login port |
| `OOG` | `1` | Out-of-game mode flag (convention from classic bot configs) |
| `Account` / `Password` | — | Test account on **your** server only |

## Protocol stub

`LoginProtocol152` documents the high-level OOG login steps and validates config. Network I/O is intentionally stubbed until you wire Blowfish/XOR handling for your fork.

## Related open-source projects (reference only)

- [L2-Net/L2Net](https://github.com/L2-Net/L2Net) — C# bot with historical OOG and protocol 152 support (different codebase; useful for packet behavior notes).
- [MyExt64 extender](https://bitbucket.org/l2shrine/extender-public) — server-side; documents protocol 152 for Gracia Epilogue (not a bot).
- [Ariakan79/L2Bot](https://github.com/Ariakan79/L2Bot) — OOG bot for High Five CT2.6 only; not compatible with Gracia without a port.

## Legal / ethics

Only use on servers where automation is allowed. Do not use this project to distribute or obtain pirated L2Walker builds.
