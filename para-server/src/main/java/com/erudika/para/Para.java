/*
 * Copyright 2013-2017 Erudika. https://erudika.com
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * For issues and patches go to: https://github.com/erudika
 */
package com.erudika.para;

import com.erudika.para.cache.Cache;
import com.erudika.para.core.App;
import com.erudika.para.persistence.DAO;
import com.erudika.para.queue.Queue;
import com.erudika.para.rest.CustomResourceHandler;
import com.erudika.para.search.Search;
import com.erudika.para.utils.Config;
import com.erudika.para.utils.VersionInfo;
import com.google.inject.Guice;
import com.google.inject.Injector;
import com.google.inject.Module;
import com.google.inject.Stage;
import com.google.inject.util.Modules;
import java.io.File;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.TreeMap;
import java.util.concurrent.*;

import org.apache.commons.io.FileUtils;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * This is the main utility class and entry point.
 * Dependency injection is initialized with the provided modules.
 * @author Alex Bogdanovski [alex@erudika.com]
 */
public final class Para {

	/**
	 * The ASCII logo.
	 */
	public static final String LOGO;
	static {
		boolean printVer = Config.getConfigBoolean("print_version", true);
		String[] logo = {"",
			"      ____  ___ _ ____ ___ _ ",
			"     / __ \\/ __` / ___/ __` /",
			"    / /_/ / /_/ / /  / /_/ / ",
			"   / .___/\\__,_/_/   \\__,_/  " + (printVer ? "v" + getVersion() : ""),
			"  /_/                        ", ""
		};
		StringBuilder sb = new StringBuilder();
		for (String line : logo) {
			sb.append(line).append("\n");
		}
		LOGO = sb.toString();
	}

	private static final Logger logger = LoggerFactory.getLogger(Para.class);
	private static final List<DestroyListener> DESTROY_LISTENERS = new LinkedList<DestroyListener>();
	private static final List<InitializeListener> INIT_LISTENERS = new LinkedList<InitializeListener>();
	private static final List<IOListener> IO_LISTENERS = new LinkedList<IOListener>();
	private static final ExecutorService EXECUTOR = Executors.newFixedThreadPool(Config.EXECUTOR_THREADS);
	private static final ScheduledExecutorService SCHEDULER = Executors.newScheduledThreadPool(Config.EXECUTOR_THREADS);
	private static Injector injector;
	private static ClassLoader paraClassLoader;

	/**
	 * No-args constructor.
	 */
	private Para() { }

	/**
	 * Initializes the Para core modules and allows the user to override them. Call this method first.
	 *
	 * @param modules a list of modules that override the main modules
	 */
	public static void initialize(Module... modules) {
		if (injector == null) {
			printLogo();
			try {
				logger.info("--- Para.initialize() [{}] ---", Config.ENVIRONMENT);
				Stage stage = Config.IN_PRODUCTION ? Stage.PRODUCTION : Stage.DEVELOPMENT;

				List<Module> coreModules = Arrays.asList(modules);
				List<Module> externalModules = getExternalModules();

				if (coreModules.isEmpty() && externalModules.isEmpty()) {
					logger.warn("No implementing modules found. Aborting...");
					destroy();
					return;
				}

				if (!externalModules.isEmpty()) {
					injector = Guice.createInjector(stage, Modules.override(coreModules).with(externalModules));
				} else {
					injector = Guice.createInjector(stage, coreModules);
				}

				for (InitializeListener initListener : INIT_LISTENERS) {
					if (initListener != null) {
						injectInto(initListener);
						initListener.onInitialize();
						logger.debug("Executed {}.onInitialize().", initListener.getClass().getName());
					}
				}
				// this enables the "River" feature - polls the deault queue for objects and imports them into Para
				if (Config.getConfigBoolean("queue_link_enabled", false)) {
					injector.getInstance(Queue.class).startPolling();
				}

				logger.info("Instance #{} initialized.", Config.WORKER_ID);
			} catch (Exception e) {
				logger.error(null, e);
			}
		}
	}

	/**
	 * Calls all registered listeners on exit. Call this method last.
	 */
	public static void destroy() {
		try {
			logger.info("--- Para.destroy() ---");
			for (DestroyListener destroyListener : DESTROY_LISTENERS) {
				if (destroyListener != null) {
					if (injector != null) {
						injectInto(destroyListener);
					}
					destroyListener.onDestroy();
					logger.debug("Executed {}.onDestroy().", destroyListener.getClass().getName());
				}
			}
			injector = null;
			if (!EXECUTOR.isShutdown()) {
				EXECUTOR.shutdown();
			}
			if (!SCHEDULER.isShutdown()) {
				SCHEDULER.shutdown();
			}
			CompletableFuture<Void> all = CompletableFuture.allOf(CompletableFuture.runAsync(() -> {
				try {
					EXECUTOR.awaitTermination(5, TimeUnit.SECONDS);
				} catch (InterruptedException ignored) {
				}
			}), CompletableFuture.runAsync(() -> {
				try {
					SCHEDULER.awaitTermination(5, TimeUnit.SECONDS);
				} catch (InterruptedException ignored) {
				}
			}));
			all.get();
		} catch (Exception e) {
			logger.error(null, e);
		}
	}

	/**
	 * Inject dependencies into a given object.
	 *
	 * @param obj the object we inject into
	 */
	public static void injectInto(Object obj) {
		if (obj == null) {
			return;
		}
		if (injector == null) {
			handleNotInitializedError();
		}
		injector.injectMembers(obj);
	}

	/**
	 * Return an instance of some class if it has been wired through DI.
	 * @param <T> any type
	 * @param type any type
	 * @return an object
	 */
	public static <T> T getInstance(Class<T> type) {
		if (injector == null) {
			handleNotInitializedError();
		}
		return injector.getInstance(type);
	}

	/**
	 * @return an instance of the core persistence class.
	 * @see DAO
	 */
	public static DAO getDAO() {
		return getInstance(DAO.class);
	}

	/**
	 * @return an instance of the core search class.
	 * @see Search
	 */
	public static Search getSearch() {
		return getInstance(Search.class);
	}

	/**
	 * @return an instance of the core cache class.
	 * @see Cache
	 */
	public static Cache getCache() {
		return getInstance(Cache.class);
	}

	/**
	 * Registers a new initialization listener.
	 *
	 * @param il the listener
	 */
	public static void addInitListener(InitializeListener il) {
		if (il != null) {
			INIT_LISTENERS.add(il);
		}
	}

	/**
	 * Registers a new destruction listener.
	 *
	 * @param dl the listener
	 */
	public static void addDestroyListener(DestroyListener dl) {
		if (dl != null) {
			DESTROY_LISTENERS.add(dl);
		}
	}

	/**
	 * Registers a new Para I/O listener.
	 *
	 * @param iol the listener
	 */
	public static void addIOListener(IOListener iol) {
		if (iol != null) {
			IO_LISTENERS.add(iol);
		}
	}

	/**
	 * Returns a list of I/O listeners (callbacks).
	 * @return the list of registered listeners
	 */
	public static List<IOListener> getIOListeners() {
		return IO_LISTENERS;
	}

	/**
	 * Returns the Para executor service.
	 * @return a fixed thread executor service
	 */
	public static ExecutorService getExecutorService() {
		return EXECUTOR;
	}

	/**
	 * Returns the Para scheduled executor service.
	 * @return a scheduled executor service
	 */
	public static ScheduledExecutorService getScheduledExecutorService() {
		return SCHEDULER;
	}

	/**
	 * Executes a {@link java.lang.Runnable} asynchronously.
	 * @param runnable a task
	 */
	public static void asyncExecute(Runnable runnable) {
		if (runnable != null) {
			try {
				Para.getExecutorService().execute(runnable);
			} catch (RejectedExecutionException ex) {
				logger.warn(ex.getMessage());
				try {
					runnable.run();
				} catch (Exception e) {
					logger.error(null, e);
				}
			}
		}
	}

	/**
	 * Executes a {@link java.lang.Runnable} at a fixed interval, asynchronously.
	 * @param task a task
	 * @param delay run after
	 * @param interval run at this interval of time
	 * @param t time unit
	 * @return a Future
	 */
	public static ScheduledFuture<?> asyncExecutePeriodically(Runnable task, long delay, long interval, TimeUnit t) {
		if (task != null) {
			try {
				return Para.getScheduledExecutorService().scheduleAtFixedRate(task, delay, interval, t);
			} catch (RejectedExecutionException ex) {
				logger.warn(ex.getMessage());
			}
		}
		return null;
	}

	/**
	 * Try loading external {@link com.erudika.para.rest.CustomResourceHandler} classes.
	 * These will handle custom API requests.
	 * via {@link java.util.ServiceLoader#load(java.lang.Class)}.
	 * @return a loaded list of  ServletContextListener class.
	 */
	public static List<CustomResourceHandler> getCustomResourceHandlers() {
		ServiceLoader<CustomResourceHandler> loader = ServiceLoader.
				load(CustomResourceHandler.class, Para.getParaClassLoader());
		List<CustomResourceHandler> externalResources = new ArrayList<>();
		for (CustomResourceHandler handler : loader) {
			if (handler != null) {
				injectInto(handler);
				externalResources.add(handler);
			}
		}
		return externalResources;
	}

	private static List<Module> getExternalModules() {
		ServiceLoader<Module> moduleLoader = ServiceLoader.load(Module.class, Para.getParaClassLoader());
		List<Module> externalModules = new ArrayList<>();
		for (Module module : moduleLoader) {
			externalModules.add(module);
		}
		return externalModules;
	}

	/**
	 * Returns the {@link URLClassLoader} classloader for Para.
	 * Used for loading JAR files from 'lib/*.jar'.
	 * @return a classloader
	 */
	public static ClassLoader getParaClassLoader() {
		if (paraClassLoader == null) {
			try {
				ClassLoader currentClassLoader = Thread.currentThread().getContextClassLoader();
				List<URL> jars = new ArrayList<>();
				File lib = new File(Config.getConfigParam("plugin_folder", "lib/"));
				if (lib.exists() && lib.isDirectory()) {
					for (File file : FileUtils.listFiles(lib, new String[]{"jar"}, false)) {
						jars.add(file.toURI().toURL());
					}
				}
				paraClassLoader = new URLClassLoader(jars.toArray(new URL[0]), currentClassLoader);
				// Thread.currentThread().setContextClassLoader(paraClassLoader);
			} catch (Exception e) {
				logger.error(null, e);
			}
		}
		return paraClassLoader;
	}

	private static void handleNotInitializedError() {
		throw new IllegalStateException("Call Para.initialize() first!");
	}

	/**
	 * Creates the root application and returns the credentials for it.
	 * @return credentials for the root app
	 */
	public static Map<String, String> setup() {
		return newApp(Config.getRootAppIdentifier(), Config.APP_NAME, false, false);
	}

	/**
	 * Creates a new application and returns the credentials for it.
	 * @param appid the app identifier
	 * @param name the full name of the app
	 * @param sharedTable false if the app should have its own table
	 * @param sharedIndex false if the app should have its own index
	 * @return credentials for the root app
	 */
	public static Map<String, String> newApp(String appid, String name, boolean sharedTable, boolean sharedIndex) {
		Map<String, String> creds = new TreeMap<>();
		creds.put("message", "All set!");
		if (StringUtils.isBlank(appid)) {
			return creds;
		}
		App app = new App(appid);
		if (!app.exists()) {
			app.setName(name);
			app.setSharingTable(sharedTable);
			app.setSharingIndex(sharedIndex);
			app.setActive(true);
			app.create();
			logger.info("Created new app '{}', sharingTable = {}, sharingIndex = {}.",
					app.getAppIdentifier(), sharedTable, sharedIndex);
			creds.putAll(app.getCredentials());
			creds.put("message", "Save the secret key - it is shown only once!");
		}
		return creds;
	}

	/**
	 * Prints the Para logo to System.out.
	 */
	public static void printLogo() {
		if (Config.getConfigBoolean("print_logo", true)) {
			System.out.print(LOGO);
		}
	}

	/**
	 * The current version of Para.
	 * @return version string, from pom.xml
	 */
	public static String getVersion() {
		return VersionInfo.getVersion();
	}

}
