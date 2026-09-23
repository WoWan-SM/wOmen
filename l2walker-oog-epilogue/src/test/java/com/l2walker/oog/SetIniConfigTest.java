package com.l2walker.oog;

import com.l2walker.oog.config.SetIniConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SetIniConfigTest {

	@Test
	void loadsEpilogueDefaults(@TempDir Path dir) throws Exception {
		Path ini = dir.resolve("set.ini");
		Files.writeString(ini, """
				ProtocolVersion=152
				LoginServerIP=10.0.0.5
				LoginServerPort=2106
				OOG=1
				Account=test
				Password=secret
				""");

		SetIniConfig config = SetIniConfig.load(ini);
		assertEquals(152, config.protocolVersion());
		assertEquals("10.0.0.5", config.loginServerIp());
		assertEquals(2106, config.loginServerPort());
		config.validateForEpilogueOog();
	}

	@Test
	void rejectsWrongProtocol(@TempDir Path dir) throws Exception {
		Path ini = dir.resolve("set.ini");
		Files.writeString(ini, "ProtocolVersion=83\nOOG=1\nLoginServerIP=127.0.0.1\n");

		SetIniConfig config = SetIniConfig.load(ini);
		assertThrows(IllegalArgumentException.class, config::validateForEpilogueOog);
	}
}
