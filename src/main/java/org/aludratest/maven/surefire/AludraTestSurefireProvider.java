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

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.Iterator;
import java.util.Properties;

import org.aludratest.scheduler.RunnerListener;
import org.apache.commons.collections.IteratorUtils;
import org.apache.maven.surefire.providerapi.AbstractProvider;
import org.apache.maven.surefire.providerapi.ProviderParameters;
import org.apache.maven.surefire.report.ReporterException;
import org.apache.maven.surefire.report.ReporterFactory;
import org.apache.maven.surefire.report.RunListener;
import org.apache.maven.surefire.suite.RunResult;
import org.apache.maven.surefire.testset.TestRequest;
import org.apache.maven.surefire.testset.TestSetFailedException;
import org.apache.maven.surefire.util.ReflectionUtils;
import org.apache.maven.surefire.util.RunOrderCalculator;
import org.apache.maven.surefire.util.ScanResult;
import org.apache.maven.surefire.util.TestsToRun;

public class AludraTestSurefireProvider extends AbstractProvider {

    private ClassLoader testClassLoader;

    private ScanResult scanResult;

    private RunOrderCalculator runOrderCalculator;

    private AludraTestTestChecker scannerFilter;

    private final TestRequest testRequest;

    private TestsToRun testsToRun;

    private ReporterFactory reporterFactory;

    private Properties providerProperties;

    public AludraTestSurefireProvider(ProviderParameters providerParameters) {
        this.testClassLoader = providerParameters.getTestClassLoader();
        this.scanResult = providerParameters.getScanResult();
        this.runOrderCalculator = providerParameters.getRunOrderCalculator();
        this.scannerFilter = new AludraTestTestChecker(testClassLoader);
        this.testRequest = providerParameters.getTestRequest();
        this.reporterFactory = providerParameters.getReporterFactory();
        this.providerProperties = providerParameters.getProviderProperties();
    }

    @SuppressWarnings("rawtypes")
    @Override
    public Iterator getSuites() {
        // no special forkmode supported yet.
        return IteratorUtils.EMPTY_ITERATOR;
    }

    private Object prepareAludraTest(AludraTestReportListener reportListener) throws ClassNotFoundException,
    InvocationTargetException {
        Object aludraTest = AludraTestReflectionUtil.startFramework(testClassLoader);

        // create our very own RunnerListener
        Class<?> runnerListenerClass = testClassLoader.loadClass(RunnerListener.class.getName());
        Object runnerListenerProxy = Proxy.newProxyInstance(testClassLoader, new Class<?>[] { runnerListenerClass },
                reportListener);

        // register it to RunnerListenerRegistry
        AludraTestReflectionUtil.registerRunnerListener(runnerListenerProxy, aludraTest, runnerListenerClass);

        return aludraTest;
    }

    @Override
    public RunResult invoke(Object forkTestSet) throws TestSetFailedException, ReporterException, InvocationTargetException {
        if (forkTestSet != null) {
            throw new IllegalArgumentException("AludraTest Surefire Provider does not expect forkTestSet parameter");
        }

        RunListener reporter = reporterFactory.createReporter();
        AludraTestReportListener reportListener = new AludraTestReportListener(reporter);

        // check if there is a filter / category configuration
        if (providerProperties != null && providerProperties.getProperty("aludratest.filter") != null
                && !"".equals(providerProperties.getProperty("aludratest.filter").trim())) {
            String filter = providerProperties.getProperty("aludratest.filter").trim();
            String categoryTree = providerProperties.getProperty("aludratest.categories");
            if (categoryTree != null) {
                categoryTree = categoryTree.trim();
            }
            if ("".equals(categoryTree)) {
                categoryTree = null;
            }

            Object aludraTest = null;
            try {
                aludraTest = prepareAludraTest(reportListener);
                AludraTestReflectionUtil.runAludraTest(aludraTest, testRequest.getTestSourceDirectory(), filter, categoryTree,
                        testClassLoader);

                // extract results from listener
                return reportListener.createRunResult();
            }
            catch (ClassNotFoundException e) {
                throw new InvocationTargetException(e);
            }
            finally {
                if (aludraTest != null) {
                    AludraTestReflectionUtil.stopFramework(aludraTest);
                }
            }
        }

        // check direct test request first
        String directTest = testRequest.getRequestedTest();
        String directTestMethod = testRequest.getRequestedTestMethod();
        if (directTest != null) {
            directTest = directTest.trim();
            while (directTest.endsWith(",")) {
                directTest = directTest.substring(0, directTest.length() - 1);
                directTest = directTest.trim();
            }
            Class<?> classToTest = ReflectionUtils.tryLoadClass(testClassLoader, directTest);
            if (classToTest == null) {
                throw new IllegalArgumentException("Class " + directTest + " not found in test scope");
            }

            Object aludraTest = null;
            try {
                aludraTest = prepareAludraTest(reportListener);
                AludraTestReflectionUtil.runAludraTest(aludraTest, classToTest);

                // extract results from listener
                return reportListener.createRunResult();
            }
            catch (ClassNotFoundException e) {
                throw new InvocationTargetException(e);
            }
            finally {
                if (aludraTest != null) {
                    AludraTestReflectionUtil.stopFramework(aludraTest);
                }
            }
        }
        else if (directTestMethod != null) {
            // TODO support for method only invocation
            throw new TestSetFailedException("Running AludraTest test methods currently not supported (" + directTestMethod
                    + " cannot be executed)");
        }
        else {
            if (testsToRun == null) {
                // if (forkTestSet instanceof TestsToRun) {
                // testsToRun = (TestsToRun) forkTestSet;
                // }
                // else if (forkTestSet instanceof Class) {
                // testsToRun = TestsToRun.fromClass((Class<?>) forkTestSet);
                // }
                // else {
                testsToRun = scanClassPath();
                // }
            }

            throw new TestSetFailedException(
                    "Running multiple AludraTest test classes currently not supported. Please use -Dtest=<testOrSuiteClass>");
        }
    }

    private TestsToRun scanClassPath() {
        final TestsToRun scanned = scanResult.applyFilter(scannerFilter, testClassLoader);
        return runOrderCalculator.orderTestClasses(scanned);
    }

}
