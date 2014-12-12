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

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.aludratest.testcase.TestStatus;
import org.apache.maven.surefire.report.CategorizedReportEntry;
import org.apache.maven.surefire.report.PojoStackTraceWriter;
import org.apache.maven.surefire.report.ReportEntry;
import org.apache.maven.surefire.report.RunListener;
import org.apache.maven.surefire.report.SimpleReportEntry;
import org.apache.maven.surefire.report.StackTraceWriter;
import org.apache.maven.surefire.suite.RunResult;
import org.apache.maven.surefire.util.ReflectionUtils;

/**
 * Will be used as "RunnerListener" implementation via the Java InvocationHandler Proxy API, to bypass Classloading issues.
 * 
 * @author falbrech
 * 
 */
public class AludraTestReportListener implements InvocationHandler {

    private AtomicInteger testsRun = new AtomicInteger();

    private AtomicInteger testsSkipped = new AtomicInteger();

    private AtomicInteger testsFailed = new AtomicInteger();

    private AtomicInteger testErrors = new AtomicInteger();

    private Map<Object, TestStatus> testStates = new HashMap<Object, TestStatus>();

    private Map<Object, Long> testStartTimes = new HashMap<Object, Long>();

    private Map<Object, Throwable> testThrowables = new HashMap<Object, Throwable>();

    private RunListener reporter;

    public AludraTestReportListener(RunListener reporter) {
        this.reporter = reporter;
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
        String methodName = method.getName();

        if ("startingTestProcess".equals(methodName)) {
            handleStartingTestProcess(args[0]);
        }

        if ("startingTestGroup".equals(methodName)) {
            handleStartingRunnerGroup(args[0]);
        }
        if ("startingTestLeaf".equals(methodName)) {
            handleStartingRunnerLeaf(args[0]);
        }
        if ("finishedTestLeaf".equals(methodName)) {
            handleFinishedRunnerLeaf(args[0]);
        }
        if ("finishedTestGroup".equals(methodName)) {
            handleFinishedRunnerGroup(args[0]);
        }
        if ("finishedTestProcess".equals(methodName)) {
            handleFinishedTestProcess(args[0]);
        }

        if ("newTestStep".equals(methodName)) {
            handleTestStep(args[0], args[1]);
        }

        // equals must be implemented, for adding to listener registry
        if ("equals".equals(methodName)) {
            return args[0] == proxy;
        }

        // it's only a "listener" interface, no need to delegate
        return null;
    }

    private void extractTestState(Object runnerLeaf, Object testStepInfo) throws Exception {

        Object objStatus = testStepInfo.getClass().getMethod("getTestStatus").invoke(testStepInfo);

        // convert to our class loader
        TestStatus status = TestStatus.valueOf(objStatus.toString());

        // only set if it's "worse" than the current one, or equal state
        TestStatus prevStatus = testStates.get(runnerLeaf);

        if (isWorseThanOrEqual(status, prevStatus)) {
            testStates.put(runnerLeaf, status);
        }
    }

    private boolean isWorseThanOrEqual(TestStatus status1, TestStatus status2) {
        if (status2 == null || status2 == TestStatus.IGNORED || status1 == status2) {
            return true;
        }

        if (status2 == TestStatus.PASSED) {
            return status1.isFailure();
        }

        if (status2 == TestStatus.FAILED || status2 == TestStatus.FAILEDPERFORMANCE) {
            return status1.isFailure() && (status1 != TestStatus.FAILED && status1 != TestStatus.FAILEDPERFORMANCE);
        }

        return false;
    }

    public RunResult createRunResult() {
        return new RunResult(testsRun.get(), testErrors.get(), testsFailed.get(), testsSkipped.get());
    }

    private String getSourceName(Object runnerLeaf) {
        return AludraTestReflectionUtil.getTestClassName(runnerLeaf);
    }

    private String getTestName(Object runnerLeaf) {
        return AludraTestReflectionUtil.getName(runnerLeaf);
    }

    private void handleStartingTestProcess(Object runnerTree) {
        // currently, nothing to do
    }

    private void handleStartingRunnerGroup(Object runnerGroup) {
        // only handle if group contains leafs
        if (AludraTestReflectionUtil.groupContainsLeafs(runnerGroup)) {
            ReportEntry entry = new SimpleReportEntry(AludraTestReflectionUtil.getParentName(runnerGroup),
                    AludraTestReflectionUtil.getName(runnerGroup));
            reporter.testSetStarting(entry);
        }
    }

    private void handleStartingRunnerLeaf(Object runnerLeaf) {
        testStartTimes.put(runnerLeaf, System.currentTimeMillis());

        // extract "ignored" attribute from leaf
        boolean ignored = AludraTestReflectionUtil.isIgnored(runnerLeaf);
        if (ignored) {
            return;
        }

        // TODO add group here as soon as AludraTest supports it (e.g. "Approved / In Work" etc.)
        ReportEntry entry = new CategorizedReportEntry(getSourceName(runnerLeaf), getTestName(runnerLeaf), null);

        reporter.testStarting(entry);
    }

    private void handleFinishedRunnerLeaf(Object runnerLeaf) {
        testsRun.incrementAndGet();

        ReportEntry entry;

        String sourceName = getSourceName(runnerLeaf);
        String name = getTestName(runnerLeaf);

        StackTraceWriter stackTrace = null;
        Throwable t = testThrowables.get(runnerLeaf);
        if (t != null) {
            stackTrace = new PojoStackTraceWriter(sourceName, name, t);
        }

        // get test state
        TestStatus status = testStates.get(runnerLeaf);
        if (AludraTestReflectionUtil.isIgnored(runnerLeaf)) {
            status = TestStatus.IGNORED;
        }
        if (status == null) {
            status = TestStatus.PASSED;
        }

        if (status.isFailure()) {
            entry = CategorizedReportEntry.withException(sourceName, name, stackTrace);
            if (status == TestStatus.FAILED || status == TestStatus.FAILEDPERFORMANCE) {
                testsFailed.incrementAndGet();
                reporter.testFailed(entry);
            }
            else {
                testErrors.incrementAndGet();
                reporter.testError(entry);
            }
        }
        else {
            Long startTime = testStartTimes.get(runnerLeaf);
            Integer elapsed = null;
            if (startTime != null) {
                elapsed = Long.valueOf(System.currentTimeMillis() - startTime.longValue()).intValue();
            }
            entry = new CategorizedReportEntry(sourceName, sourceName, name, null, elapsed);
            if (status == TestStatus.IGNORED) {
                reporter.testSkipped(entry);
            }
            else {
                reporter.testSucceeded(entry);
            }
        }
    }

    private void handleFinishedRunnerGroup(Object runnerGroup) {
        if (AludraTestReflectionUtil.groupContainsLeafs(runnerGroup)) {
            ReportEntry entry = new SimpleReportEntry(AludraTestReflectionUtil.getParentName(runnerGroup),
                    AludraTestReflectionUtil.getName(runnerGroup));
            reporter.testSetCompleted(entry);
        }
    }

    private void handleFinishedTestProcess(Object runnerTree) {
        // currently, nothing to do.
    }

    private void handleTestStep(Object runnerLeaf, Object testStep) throws Exception {
        // extract test status from step
        extractTestState(runnerLeaf, testStep);

        // if there is a Throwable attached, store it
        Throwable t = (Throwable) ReflectionUtils.invokeGetter(testStep, "getError");
        if (t != null) {
            testThrowables.put(runnerLeaf, t);
        }
    }

}
