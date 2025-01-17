/*
 * #%L
 * JSR-223-compliant JavaScript scripting language plugin.
 * %%
 * Copyright (C) 2008 - 2017 Board of Regents of the University of
 * Wisconsin-Madison, Broad Institute of MIT and Harvard, and Max Planck
 * Institute of Molecular Cell Biology and Genetics.
 * %%
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 * 
 * 1. Redistributions of source code must retain the above copyright notice,
 *    this list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 * 
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE
 * ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDERS OR CONTRIBUTORS BE
 * LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
 * CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
 * SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS
 * INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN
 * CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
 * ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE
 * POSSIBILITY OF SUCH DAMAGE.
 * #L%
 */

package org.scijava.plugins.scripting.javascript;

import java.lang.reflect.InvocationTargetException;

import javax.script.ScriptEngine;
import javax.script.ScriptException;

import org.scijava.log.LogService;
import org.scijava.plugin.Parameter;
import org.scijava.plugin.Plugin;
import org.scijava.script.AdaptedScriptLanguage;
import org.scijava.script.ScriptLanguage;
import org.scijava.util.ClassUtils;

/**
 * An adapter of the JavaScript interpreter to the SciJava scripting interface.
 * 
 * @author Curtis Rueden
 * @see ScriptEngine
 */
@Plugin(type = ScriptLanguage.class, name = "JavaScript")
public class JavaScriptScriptLanguage extends AdaptedScriptLanguage {

	@Parameter
	private LogService log;

	public JavaScriptScriptLanguage() {
		super("javascript");
	}

	// -- JavaScriptScriptLanguage methods --

	/**
	 * Returns true iff this script language is using the <a
	 * href="http://openjdk.java.net/projects/nashorn/">Nashorn</a> JavaScript
	 * engine. This is the case for Java 8.
	 */
	public boolean isNashorn() {
		return getEngineName().contains("Nashorn");
	}

	/**
	 * Returns true iff this script language is using the <a
	 * href="https://developer.mozilla.org/en-US/docs/Mozilla/Projects/Rhino"
	 * >Rhino</a> JavaScript engine. This is the case for Java 6 and Java 7.
	 */
	public boolean isRhino() {
		return getEngineName().contains("Rhino");
	}

	/** Returns true iff the JVM appears to be the OpenJDK version of Java. */
	public boolean isOpenJDK() {
		final String vmName = System.getProperty("java.vm.name");
		return vmName != null && vmName.startsWith("OpenJDK");
	}

	// -- ScriptEngineFactory methods --

	@Override
	public ScriptEngine getScriptEngine() {
		final ScriptEngine engine = super.getScriptEngine();
		try {
			if (isNashorn()) {
				// for Rhino compatibility, importClass and importPackage in particular
				engine.eval("load(\"nashorn:mozilla_compat.js\");");
			}
			if (isRhino()) {
				// for the load function, which is somehow otherwise unavailable (?)
				engine.eval("function load(path) {\n" +
					"  importClass(Packages." + contextClass(engine) + ");\n" +
					"  importClass(Packages.java.io.FileReader);\n" +
					"  var cx = Context.getCurrentContext();\n" +
					"  cx.evaluateReader(this, new FileReader(path), path, 1, null);\n" +
					"}");
			}
		}
		catch (final ScriptException e) {
			e.printStackTrace();
		}
		return engine;
	}

	@Override
	public Object decode(final Object object) {
		// NB: JavaScript objects come out of the engine wrapped as
		// JavaScript-specific objects (e.g., NativeJavaObject), which must be
		// unwrapped. Unfortunately, we don't necessarily have direct compile-time
		// access to the JavaScript Wrapper interface implemented by the
		// NativeJavaObject wrapper. But we can access it via reflection. It is
		// typically org.mozilla.javascript.Wrapper, except for Oracle's shaded
		// implementation, which is sun.org.mozilla.javascript.internal.Wrapper.
		// Either way, the package will match that of the wrapped object itself.
		if (object == null) return null;
		final Class<?> objectClass = object.getClass();
		final String packageName = objectClass.getPackage().getName();
		final Class<?> wrapperClass =
			ClassUtils.loadClass(packageName + ".Wrapper");
		if (wrapperClass == null || !wrapperClass.isAssignableFrom(objectClass)) {
			return object;
		}
		try {
			return wrapperClass.getMethod("unwrap").invoke(object);
		}
		catch (final IllegalArgumentException exc) {
			log.warn(exc);
		}
		catch (final IllegalAccessException exc) {
			log.warn(exc);
		}
		catch (final InvocationTargetException exc) {
			log.warn(exc);
		}
		catch (final NoSuchMethodException exc) {
			log.warn(exc);
		}
		return null;
	}

	// -- Helper methods --

	private String contextClass(final ScriptEngine engine) {
		if (isNashorn()) return "jdk.nashorn.internal.runtime.Context";

		final String engineClassName = engine.getClass().getName();

		if (isRhino()) {
			if (engineClassName.startsWith("com.sun.")) {
				if (isOpenJDK()) {
					return "sun.org.mozilla.javascript.Context";
				}
				else {
					// assume Oracle version of Java
					return "sun.org.mozilla.javascript.internal.Context";
				}
			}
			// assume vanilla Mozilla-flavored Rhino script engine
			return "org.mozilla.javascript.Context";
		}

		throw new UnsupportedOperationException("Unknown JavaScript flavor: " +
			engineClassName);
	}

}
