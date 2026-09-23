package com.l2walker.oog.config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Minimal parser for L2Walker-style {@code set.ini} key=value files (no sections).
 */
public record SetIniConfig(
		int protocolVersion,
		String loginServerIp,
		int loginServerPort,
		String gameServerIp,
		int gameServerPort,
		boolean oog,
		boolean official,
		String account,
		String password,
		int characterSlot,
		String logLevel
) {

	public static final int GRACIA_EPILOGUE_PROTOCOL = 152;

	public static SetIniConfig load(Path path) throws IOException {
		Map<String, String> values = new HashMap<>();
		for (String line : Files.readAllLines(path)) {
			String trimmed = line.trim();
			if (trimmed.isEmpty() || trimmed.startsWith(";") || trimmed.startsWith("#")) {
				continue;
			}
			int eq = trimmed.indexOf('=');
			if (eq <= 0) {
				continue;
			}
			String key = trimmed.substring(0, eq).trim();
			String value = trimmed.substring(eq + 1).trim();
			values.put(key.toLowerCase(Locale.ROOT), value);
		}

		return new SetIniConfig(
				parseInt(values, "protocolversion", GRACIA_EPILOGUE_PROTOCOL),
				values.getOrDefault("loginserverip", "127.0.0.1"),
				parseInt(values, "loginserverport", 2106),
				values.getOrDefault("gameserverip", ""),
				parseInt(values, "gameserverport", 7777),
				parseInt(values, "oog", 1) != 0,
				parseInt(values, "official", 0) != 0,
				values.getOrDefault("account", ""),
				values.getOrDefault("password", ""),
				parseInt(values, "characterslot", 0),
				values.getOrDefault("loglevel", "INFO")
		);
	}

	public void validateForEpilogueOog() {
		if (protocolVersion != GRACIA_EPILOGUE_PROTOCOL) {
			throw new IllegalArgumentException(
					"Expected ProtocolVersion=" + GRACIA_EPILOGUE_PROTOCOL + " for Gracia Epilogue, got " + protocolVersion);
		}
		if (!oog) {
			throw new IllegalArgumentException("This scaffold targets OOG mode; set OOG=1 in set.ini");
		}
		if (loginServerIp.isBlank()) {
			throw new IllegalArgumentException("LoginServerIP must be set");
		}
		if (loginServerPort <= 0 || loginServerPort > 65535) {
			throw new IllegalArgumentException("LoginServerPort out of range: " + loginServerPort);
		}
	}

	private static int parseInt(Map<String, String> values, String key, int defaultValue) {
		String raw = values.get(key);
		if (raw == null || raw.isEmpty()) {
			return defaultValue;
		}
		return Integer.parseInt(raw);
	}
}
