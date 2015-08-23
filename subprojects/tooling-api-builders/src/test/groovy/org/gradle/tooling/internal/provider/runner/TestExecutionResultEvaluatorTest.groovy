/*
 * Copyright 2015 the original author or authors.
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
 */

package org.gradle.tooling.internal.provider.runner

import org.gradle.api.execution.internal.TaskOperationInternal
import org.gradle.api.internal.TaskInternal
import org.gradle.api.internal.tasks.testing.TestCompleteEvent
import org.gradle.api.internal.tasks.testing.TestDescriptorInternal
import org.gradle.api.tasks.testing.TestExecutionException
import org.gradle.api.tasks.testing.TestResult
import org.gradle.internal.progress.OperationStartEvent
import org.gradle.tooling.internal.provider.ProviderInternalTestExecutionRequest
import org.gradle.tooling.internal.protocol.test.InternalTestMethod
import org.gradle.tooling.internal.provider.events.DefaultTestDescriptor
import spock.lang.Specification

import static org.gradle.util.TextUtil.normaliseLineSeparators

class TestExecutionResultEvaluatorTest extends Specification {
    def "evaluate throws exception if no results tracked"() {
        given:
        def testExecutionRequest = Mock(ProviderInternalTestExecutionRequest)
        TestExecutionResultEvaluator evaluator = new TestExecutionResultEvaluator(testExecutionRequest)

        def testDescriptorInternal = Mock(TestDescriptorInternal)
        def defaultTestDescriptor = Mock(DefaultTestDescriptor)
        1 * defaultTestDescriptor.getDisplayName() >> "Some Test Descriptor"
        1 * defaultTestDescriptor.getTaskPath() >> ":someTestTask"

        def testResult = Mock(TestResult)
        def internalTestMethod = Mock(InternalTestMethod)
        1 * internalTestMethod.getClassName() >> "org.acme.SomeOtherTest"
        1 * internalTestMethod.getMethodName() >> "someTestMethod"

        when:
        evaluator.completed(testDescriptorInternal, testResult, Mock(TestCompleteEvent))
        evaluator.evaluate();
        then:
        def e = thrown(TestExecutionException)
        normaliseLineSeparators(e.message) == """No matching tests found in any candidate test task.
    Requested tests:
        Some Test Descriptor (Task: ':someTestTask')
        Test class acme.SomeTestClass
        Test method org.acme.SomeOtherTest.someTestMethod()"""

        and:
        1 * testExecutionRequest.getTestExecutionDescriptors()>> [defaultTestDescriptor]
        1 * testExecutionRequest.getExplicitRequestedTestClassNames(_) >> ["acme.SomeTestClass"]
        1 * testExecutionRequest.getTestMethods(_) >> [internalTestMethod]
    }

    def "evaluate throws exception if test failed"() {
        given:
        def testExecutionRequest = Mock(ProviderInternalTestExecutionRequest)
        TestExecutionResultEvaluator evaluator = new TestExecutionResultEvaluator(testExecutionRequest)

        def testDescriptorInternal = Mock(TestDescriptorInternal)

        testDescriptorInternal.getName() >> "someTest"
        testDescriptorInternal.getClassName() >> "acme.SomeTestClass"
        testDescriptorInternal.getOwnerBuildOperationId() >> 1

        def testResult = Mock(TestResult)
        1 * testResult.getTestCount() >> 1
        1 * testResult.getFailedTestCount() >> 1

        def testTask = Mock(TaskInternal)
        1 * testTask.getPath() >> ":someproject:someTestTask"
        TaskOperationInternal taskOperationInternal = new TaskOperationInternal(1, 2, testTask)

        when:
        evaluator.beforeExecute(taskOperationInternal, Mock(OperationStartEvent))
        evaluator.completed(testDescriptorInternal, testResult, Mock(TestCompleteEvent))
        evaluator.evaluate()

        then:
        def e = thrown(TestExecutionException)
        normaliseLineSeparators(e.message) == """Test failed.
    Failed tests:
        Test acme.SomeTestClass#someTest (Task: :someproject:someTestTask)"""
    }
}
