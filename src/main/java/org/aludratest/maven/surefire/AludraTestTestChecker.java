/*
 * Copyright (C) 2010-2014 Hamburg Sud and the contributors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.aludratest.maven.surefire;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;

import org.aludratest.testcase.AludraTestCase;
import org.aludratest.testcase.Test;
import org.apache.maven.surefire.util.ScannerFilter;

public class AludraTestTestChecker implements ScannerFilter {

	private final Class<?> aludraTestCaseClass;
	
	private final Class<?> testAnnotationClass;

	public AludraTestTestChecker(ClassLoader testClassLoader) {
		this.aludraTestCaseClass = AludraTestReflectionUtil.getAludraTestClass(testClassLoader, AludraTestCase.class.getName());
		this.testAnnotationClass = AludraTestReflectionUtil.getAludraTestClass(testClassLoader, Test.class.getName());
	}

	@Override
	public boolean accept(@SuppressWarnings("rawtypes") Class testClass) {
		return isValidAludraTestClass(testClass);
	}

	public boolean isValidAludraTestClass(Class<?> testClass) {
		if (aludraTestCaseClass != null && aludraTestCaseClass.isAssignableFrom(testClass)) {
			// must also contain at least one Test method
			Class<?> classToCheck = testClass;
			while (classToCheck != null) {
				if (checkforTestAnnotatedMethod(classToCheck)) {
					return true;
				}
				classToCheck = classToCheck.getSuperclass();
			}
		}
		return false;
	}

	private boolean checkforTestAnnotatedMethod(Class<?> testClass) {
		for (Method lMethod : testClass.getDeclaredMethods()) {
			for (Annotation lAnnotation : lMethod.getAnnotations()) {
				if (testAnnotationClass.isAssignableFrom(lAnnotation.annotationType())) {
					return true;
				}
			}
		}
		return false;
	}

}
