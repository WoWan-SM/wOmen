package com.l2walker.oog;

import com.l2walker.oog.config.SetIniConfig;
import com.l2walker.oog.protocol.LoginProtocol152;

import java.nio.file.Path;

public final class Main {

	public static void main(String[] args) throws Exception {
		Path configPath = Path.of(args.length > 0 ? args[0] : "config/set.ini.example");
		SetIniConfig config = SetIniConfig.load(configPath);
		config.validateForEpilogueOog();

		System.out.println("L2Walker OOG Epilogue scaffold — protocol " + config.protocolVersion());
		System.out.println("Login target: " + config.loginServerIp() + ":" + config.loginServerPort());
		System.out.println("OOG mode: " + (config.oog() ? "enabled" : "disabled"));

		LoginProtocol152 login = new LoginProtocol152(config);
		login.runLoginSequenceStub();
	}

	private Main() {
	}
}
