/*
 * Copyright 2012-2024 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package dev.freya02.botcommands.internal.restart;

import org.apache.commons.logging.Log;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.devtools.restart.FailureHandler;
import org.springframework.boot.devtools.restart.FailureHandler.Outcome;
import org.springframework.boot.devtools.restart.RestartApplicationListener;
import org.springframework.boot.devtools.restart.RestartInitializer;
import org.springframework.boot.devtools.restart.classloader.ClassLoaderFiles;
import org.springframework.boot.devtools.restart.classloader.RestartClassLoader;
import org.springframework.boot.logging.DeferredLog;
import org.springframework.util.Assert;

import java.lang.Thread.UncaughtExceptionHandler;
import java.net.URL;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

public class Restarter {

	private static final Object INSTANCE_MONITOR = new Object();

	private static Restarter instance;

	private final Set<URL> urls = new LinkedHashSet<>();

	private final ClassLoaderFiles classLoaderFiles = new ClassLoaderFiles();

	private final BlockingDeque<LeakSafeThread> leakSafeThreads = new LinkedBlockingDeque<>();

	private final Lock stopLock = new ReentrantLock();

	private Log logger = new DeferredLog();

	private final URL[] initialUrls;

	private final String mainClassName;

	private final ClassLoader applicationClassLoader;

	private final String[] args;

	private final UncaughtExceptionHandler exceptionHandler;

	private final List<RestartListener> listeners = new CopyOnWriteArrayList<>();

	/**
	 * Internal constructor to create a new {@link Restarter} instance.
	 * @param thread the source thread
	 * @param args the application arguments
	 * @param initializer the restart initializer
	 * @see #initialize(String[])
	 */
	protected Restarter(Thread thread, String[] args, RestartInitializer initializer) {
        Assert.notNull(thread, "Thread must not be null");
		Assert.notNull(args, "Args must not be null");
		Assert.notNull(initializer, "Initializer must not be null");
		if (logger.isDebugEnabled()) {
			logger.debug("Creating new Restarter for thread " + thread);
		}
		SilentExitExceptionHandler.setup(thread);
		this.initialUrls = initializer.getInitialUrls(thread);
		this.mainClassName = getMainClassName(thread);
		this.applicationClassLoader = thread.getContextClassLoader();
		this.args = args;
		this.exceptionHandler = thread.getUncaughtExceptionHandler();
		this.leakSafeThreads.add(new LeakSafeThread());
	}

	public void addListener(RestartListener listener) {
		listeners.add(listener);
	}

	private String getMainClassName(Thread thread) {
		try {
			return new MainMethod(thread).getDeclaringClassName();
		}
		catch (Exception ex) {
			return null;
		}
	}

	protected void initialize(boolean restartOnInitialize) {
		if (this.initialUrls != null) {
			this.urls.addAll(Arrays.asList(this.initialUrls));
			if (restartOnInitialize) {
				this.logger.debug("Immediately restarting application");
				immediateRestart();
			}
		}
	}

	private void immediateRestart() {
		try {
			getLeakSafeThread().callAndWait(() -> {
				start(FailureHandler.NONE);
				return null;
			});
		}
		catch (Exception ex) {
			this.logger.warn("Unable to initialize restarter", ex);
		}
		SilentExitExceptionHandler.exitCurrentThread();
	}

	/**
	 * Return a {@link ThreadFactory} that can be used to create leak safe threads.
	 * @return a leak safe thread factory
	 */
	public ThreadFactory getThreadFactory() {
		return new LeakSafeThreadFactory();
	}

	/**
	 * Restart the running application.
	 */
	public void restart() {
		restart(FailureHandler.NONE);
	}

	/**
	 * Restart the running application.
	 * @param failureHandler a failure handler to deal with application that doesn't start
	 */
	public void restart(FailureHandler failureHandler) {
		this.logger.debug("Restarting application");
		getLeakSafeThread().call(() -> {
			Restarter.this.stop();
			Restarter.this.start(failureHandler);
			return null;
		});
	}

	/**
	 * Start the application.
	 * @param failureHandler a failure handler for application that won't start
	 * @throws Exception in case of errors
	 */
	protected void start(FailureHandler failureHandler) throws Exception {
		do {
			Throwable error = doStart();
			if (error == null) {
				return;
			}
			if (failureHandler.handle(error) == Outcome.ABORT) {
				return;
			}
		}
		while (true);
	}

	private Throwable doStart() throws Exception {
		Assert.notNull(this.mainClassName, "Unable to find the main class to restart");
		URL[] urls = this.urls.toArray(new URL[0]);
		ClassLoaderFiles updatedFiles = new ClassLoaderFiles(this.classLoaderFiles);
		ClassLoader classLoader = new RestartClassLoader(this.applicationClassLoader, urls, updatedFiles);
		if (this.logger.isDebugEnabled()) {
			this.logger.debug("Starting application " + this.mainClassName + " with URLs " + Arrays.asList(urls));
		}
		return relaunch(classLoader);
	}

	/**
	 * Relaunch the application using the specified classloader.
	 * @param classLoader the classloader to use
	 * @return any exception that caused the launch to fail or {@code null}
	 * @throws Exception in case of errors
	 */
	protected Throwable relaunch(ClassLoader classLoader) throws Exception {
		RestartLauncher launcher = new RestartLauncher(classLoader, this.mainClassName, this.args,
				this.exceptionHandler);
		launcher.start();
		launcher.join();
		return launcher.getError();
	}

	/**
	 * Stop the application.
     */
	protected void stop() {
		this.logger.debug("Stopping application");
		this.stopLock.lock();
		try {
			for (RestartListener listener : listeners) {
				listener.beforeStop();
			}
			listeners.clear();
		}
		finally {
			this.stopLock.unlock();
		}
		System.gc();
	}

	private LeakSafeThread getLeakSafeThread() {
		try {
			return this.leakSafeThreads.takeFirst();
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException(ex);
		}
	}

	public static void initialize(String[] args) {
		initialize(args, new DefaultRestartInitializer());
	}

	public static void initialize(String[] args, RestartInitializer initializer) {
		initialize(args, initializer, true);
	}

	/**
	 * Initialize restart support for the current application. Called automatically by
	 * {@link RestartApplicationListener} but can also be called directly if main
	 * application arguments are not the same as those passed to the
	 * {@link SpringApplication}.
	 * @param args main application arguments
	 * @param initializer the restart initializer
	 * @param restartOnInitialize if the restarter should be restarted immediately when
	 * the {@link RestartInitializer} returns non {@code null} results
	 */
	public static void initialize(String[] args, RestartInitializer initializer,
	                              boolean restartOnInitialize) {
		Restarter localInstance = null;
		synchronized (INSTANCE_MONITOR) {
			if (instance == null) {
				localInstance = new Restarter(Thread.currentThread(), args, initializer);
				instance = localInstance;
			}
		}
		if (localInstance != null) {
			localInstance.initialize(restartOnInitialize);
		}
	}

	/**
	 * Return the active {@link Restarter} instance. Cannot be called before
	 * {@link #initialize(String[]) initialization}.
	 * @return the restarter
	 */
	public static Restarter getInstance() {
		synchronized (INSTANCE_MONITOR) {
			Assert.state(instance != null, "Restarter has not been initialized");
			return instance;
		}
	}

	/**
	 * Thread that is created early so not to retain the {@link RestartClassLoader}.
	 */
	private class LeakSafeThread extends Thread {

		private Callable<?> callable;

		private Object result;

		LeakSafeThread() {
			setDaemon(false);
		}

		void call(Callable<?> callable) {
			this.callable = callable;
			start();
		}

		@SuppressWarnings("unchecked")
		<V> V callAndWait(Callable<V> callable) {
			this.callable = callable;
			start();
			try {
				join();
				return (V) this.result;
			}
			catch (InterruptedException ex) {
				Thread.currentThread().interrupt();
				throw new IllegalStateException(ex);
			}
		}

		@Override
		public void run() {
			// We are safe to refresh the ActionThread (and indirectly call
			// AccessController.getContext()) since our stack doesn't include the
			// RestartClassLoader
			try {
				Restarter.this.leakSafeThreads.put(new LeakSafeThread());
				this.result = this.callable.call();
			}
			catch (Exception ex) {
				ex.printStackTrace();
				System.exit(1);
			}
		}

	}

	/**
	 * {@link ThreadFactory} that creates a leak safe thread.
	 */
	private final class LeakSafeThreadFactory implements ThreadFactory {

		@Override
		public Thread newThread(Runnable runnable) {
			return getLeakSafeThread().callAndWait(() -> {
				Thread thread = new Thread(runnable);
				thread.setContextClassLoader(Restarter.this.applicationClassLoader);
				return thread;
			});
		}

	}

}
