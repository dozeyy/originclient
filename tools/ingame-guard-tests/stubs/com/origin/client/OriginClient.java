package com.origin.client;

// Test stub: ModsConfig only logs through OriginClient.LOGGER.
public final class OriginClient {
	public static final Log LOGGER = new Log();

	public static final class Log {
		public void info(String msg) {
			System.out.println("  [info] " + msg);
		}

		public void warn(String msg) {
			System.out.println("  [warn] " + msg);
		}

		public void warn(String msg, Throwable t) {
			System.out.println("  [warn] " + msg + " (" + t + ")");
		}
	}
}
