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

Local handshake (loopback login + game servers, no private shard required):

```bash
./gradlew run --args="--demo"
```

Dry run (validate `config/set.ini.example`, do not connect):

```bash
./gradlew run
```

Connect to a private server:

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
| `ServerId` | `1` | World id to select from the login server list |
| `Account` / `Password` | — | Test account on **your** server only. Account max 14 ASCII chars, password max 16 |

## Handshake

`LoginClient152` speaks an L2J-style Gracia Epilogue OOG login:

1. Decrypt `Init` with the static Blowfish key and XOR-pass.
2. Unscramble the RSA modulus and seal credentials (account at `0x5E`, password at `0x6C`).
3. `LoginOk` → server list → `PlayOk`.
4. Connect to the game server, read the plaintext key packet (`0x2E`), enable the XOR cipher.
5. Send protocol version **152** (`0x0E`) and game `AuthLogin` (`0x2B`).
6. Read `CharSelectionInfo` (`0x09`) count. A full paperdoll block is not parsed yet.

`./gradlew test` covers the crypt primitives and a loopback client/server handshake. That harness uses the same packet builders as the client, so it proves the pipeline runs; it is not a capture from a third-party L2J process.

## Related open-source projects (reference only)

- [L2-Net/L2Net](https://github.com/L2-Net/L2Net) — C# bot with historical OOG and protocol 152 support (different codebase; useful for packet behavior notes).
- [MyExt64 extender](https://bitbucket.org/l2shrine/extender-public) — server-side; documents protocol 152 for Gracia Epilogue (not a bot).
- [Ariakan79/L2Bot](https://github.com/Ariakan79/L2Bot) — OOG bot for High Five CT2.6 only; not compatible with Gracia without a port.

## Legal / ethics

Only use on servers where automation is allowed. Do not use this project to distribute or obtain pirated L2Walker builds.
