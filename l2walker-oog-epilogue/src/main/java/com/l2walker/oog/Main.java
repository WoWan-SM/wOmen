package com.l2walker.oog;

import com.l2walker.oog.config.SetIniConfig;
import com.l2walker.oog.protocol.LocalEpilogueDemo;
import com.l2walker.oog.protocol.LoginProtocol152;
import com.l2walker.oog.protocol.LoginResult;

import java.net.ConnectException;
import java.nio.file.Path;

public final class Main {

	public static void main(String[] args) throws Exception {
		if (args.length > 0 && "--demo".equals(args[0])) {
			LoginResult result = LocalEpilogueDemo.run("epilogue", "secret", "HumanFighter");
			System.out.println("Local Gracia Epilogue handshake succeeded");
			System.out.println("account=" + result.account()
					+ " serverId=" + result.serverId()
					+ " game=" + result.gameHost() + ":" + result.gamePort()
					+ " characters=" + result.characterCount()
					+ " names=" + result.characterNames());
			return;
		}

		boolean dryRun = args.length == 0;
		Path configPath = Path.of(dryRun ? "config/set.ini.example" : args[0]);
		SetIniConfig config = SetIniConfig.load(configPath);
		config.validateForEpilogueOog();
		LoginProtocol152 login = new LoginProtocol152(config);
		if (dryRun) {
			login.describe();
			return;
		}

		try {
			LoginResult result = login.login();
			System.out.println("Login succeeded for " + result.account()
					+ " via " + result.gameHost() + ":" + result.gamePort()
					+ " characters=" + result.characterCount());
		} catch (ConnectException ex) {
			System.err.println("Could not reach login server " + config.loginServerIp() + ":" + config.loginServerPort());
			System.err.println("Check LoginServerIP/LoginServerPort, or run: ./gradlew run --args=--demo");
			System.exit(2);
		}
	}

	private Main() {
	}
}
