package com.origin.client.client.hooks;

import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.FMLCommonHandler;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The registry that wires each area's Forge-event hooks class onto the event
 * buses. 1.8.9's Fabric-callback stand-in: every area keeps ALL its @SubscribeEvent
 * methods in one class (`com.origin.client.client.hooks.<Area>Hooks`, public
 * no-arg constructor), and this class instantiates and registers it.
 *
 * WHY Class.forName: the areas are ported independently and land at different
 * times; a missing or broken hooks class must cost only that area (logged, then
 * skipped), never the rest of Origin or the game (mandate 4).
 *
 * WHY both buses: Forge 1.8.9 posts TickEvent / InputEvent / FMLNetworkEvent on
 * FMLCommonHandler.instance().bus() and everything else on MinecraftForge.EVENT_BUS.
 * (In this Forge the two may be the same EventBus object; registering one object
 * twice on one bus would double every handler, so the second registration is
 * skipped when they are identical. Forge's EventBus also ignores a repeat
 * register of the same object.)
 */
public final class OriginHooks {
	/** Every area's hooks class, in registration order. */
	public static final String[] AREAS = {
			"com.origin.client.client.hooks.MenuHooks",
			"com.origin.client.client.hooks.ModMenuHooks",
			"com.origin.client.client.hooks.HudHooks",
			"com.origin.client.client.hooks.WorldHooks",
			"com.origin.client.client.hooks.SocialHooks",
			"com.origin.client.client.hooks.ServerHooks",
	};

	private static final List<Object> REGISTERED = new ArrayList<>();
	private static boolean done = false;

	private OriginHooks() {
	}

	/** Registers every area once. Safe to call again (no-op). */
	public static synchronized void registerAll() {
		if (done) {
			return;
		}
		done = true;
		for (String name : AREAS) {
			register(name);
		}
	}

	/** Registers one already-built handler object on both buses (the hub uses this for itself). */
	public static boolean registerObject(Object handler) {
		if (handler == null) {
			return false;
		}
		try {
			Object forgeBus = MinecraftForge.EVENT_BUS;
			MinecraftForge.EVENT_BUS.register(handler);
			Object fmlBus = null;
			try {
				fmlBus = FMLCommonHandler.instance().bus();
			} catch (Throwable ignored) {
				// no separate FML bus on this Forge: the Forge bus is enough
			}
			if (fmlBus != null && fmlBus != forgeBus) {
				FMLCommonHandler.instance().bus().register(handler);
			}
			synchronized (REGISTERED) {
				REGISTERED.add(handler);
			}
			return true;
		} catch (Throwable t) {
			log("hooks " + handler.getClass().getName() + " failed to register; that area runs vanilla", t);
			return false;
		}
	}

	/** The registered hooks objects (read-only), e.g. for the dev-shots harness. */
	public static List<Object> registered() {
		synchronized (REGISTERED) {
			return Collections.unmodifiableList(new ArrayList<>(REGISTERED));
		}
	}

	private static void register(String className) {
		Class<?> c;
		try {
			c = Class.forName(className);
		} catch (ClassNotFoundException e) {
			com.origin.client.OriginClient.LOGGER.info("Origin hooks " + className + " not present; skipped");
			return;
		} catch (Throwable t) {
			log("hooks " + className + " failed to load; that area runs vanilla", t);
			return;
		}
		Object handler;
		try {
			handler = c.newInstance();
		} catch (Throwable t) {
			log("hooks " + className + " failed to construct; that area runs vanilla", t);
			return;
		}
		if (registerObject(handler)) {
			com.origin.client.OriginClient.LOGGER.info("Origin hooks registered: " + c.getSimpleName());
		}
	}

	private static void log(String msg, Throwable t) {
		try {
			com.origin.client.OriginClient.LOGGER.error(msg, t);
		} catch (Throwable ignored) {
			// never let logging break registration
		}
	}
}
