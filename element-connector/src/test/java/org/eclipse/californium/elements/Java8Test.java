/*******************************************************************************
 * Copyright (c) 2020 Bosch.IO GmbH and others.
 * 
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v2.0
 * and Eclipse Distribution License v1.0 which accompany this distribution.
 * 
 * The Eclipse Public License is available at
 *    http://www.eclipse.org/legal/epl-v20.html
 * and the Eclipse Distribution License is available at
 *    http://www.eclipse.org/org/documents/edl-v10.html.
 * 
 * Contributors:
 *    Bosch.IO GmbH - initial creation
 ******************************************************************************/
package org.eclipse.californium.elements;

import static org.junit.Assert.fail;

import java.util.concurrent.ConcurrentHashMap;

import org.junit.Test;

/**
 * Using jdk8 and compiling for java 7 may use accidentally the java 8 API.
 * 
 * Executing such jars with java 7 may fail, if java 8 API is called. This test
 * ensures, that the compiler uses the java 7 API. The maven build will always
 * fail, with compile errors for real java 7 API, and with the failure "java 8",
 * if the java 8 API is used.
 * Only intended to verify the build setup.
 */
public class Java8Test {

	@Test
	public void testJava8Api() throws InterruptedException {
		ConcurrentHashMap<String, String> map = new ConcurrentHashMap<>();
		// call java 8 API, fails to compile with java 7 API
		map.mappingCount();
		fail("java 8 API used!");
	}

}
