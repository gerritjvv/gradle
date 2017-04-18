/*
 * Copyright 2014 the original author or authors.
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

package org.gradle.internal.progress

import org.gradle.internal.concurrent.ExecutorFactory
import org.gradle.internal.concurrent.GradleThread
import org.gradle.internal.logging.progress.ProgressLogger
import org.gradle.internal.logging.progress.ProgressLoggerFactory
import org.gradle.internal.operations.BuildOperationContext
import org.gradle.internal.operations.BuildOperationQueueFactory
import org.gradle.internal.operations.CallableBuildOperation
import org.gradle.internal.operations.RunnableBuildOperation
import org.gradle.internal.time.TimeProvider
import org.gradle.test.fixtures.concurrent.ConcurrentSpec

import static org.gradle.internal.progress.BuildOperationDescriptor.displayName

class DefaultBuildOperationExecutorTest extends ConcurrentSpec {
    def listener = Mock(BuildOperationListener)
    def timeProvider = Mock(TimeProvider)
    def progressLoggerFactory = Mock(ProgressLoggerFactory)
    def operationExecutor = new DefaultBuildOperationExecutor(listener, timeProvider, progressLoggerFactory, Mock(BuildOperationQueueFactory), Mock(ExecutorFactory), 1)

    def "fires events when operation starts and finishes successfully"() {
        setup:
        GradleThread.setManaged()

        and:
        def buildOperation = Mock(CallableBuildOperation)
        def progressLogger = Mock(ProgressLogger)
        def descriptor = "some-thing"
        def operationDetailsBuilder = displayName("<some-operation>").name("<op>").progressDisplayName("<some-op>").operationDescriptor(descriptor)
        def id

        when:
        def result = operationExecutor.call(buildOperation)

        then:
        result == "result"

        then:
        1 * buildOperation.description() >> operationDetailsBuilder
        1 * timeProvider.currentTime >> 123L
        1 * listener.started(_, _) >> { BuildOperationInternal operation, OperationStartEvent start ->
            id = operation.id
            assert operation.id != null
            assert operation.parentId == null
            assert operation.name == "<op>"
            assert operation.displayName == "<some-operation>"
            assert operation.operationDescriptor == descriptor
            assert start.startTime == 123L
        }

        then:
        1 * progressLoggerFactory.newOperation(_) >> progressLogger
        1 * progressLogger.setDescription("<some-operation>")
        1 * progressLogger.setShortDescription("<some-op>")
        1 * progressLogger.started()

        then:
        1 * buildOperation.call(_) >> "result"

        then:
        1 * progressLogger.completed()

        then:
        1 * timeProvider.currentTime >> 124L
        1 * listener.finished(_, _) >> { BuildOperationInternal operation, OperationResult opResult ->
            assert operation.parentId == null
            assert operation.id == id
            assert operation.name == "<op>"
            assert operation.displayName == "<some-operation>"
            assert operation.operationDescriptor == descriptor
            assert opResult.startTime == 123L
            assert opResult.endTime == 124L
            assert opResult.failure == null
        }

        cleanup:
        GradleThread.setUnmanaged()
    }

    def "fires events when operation starts and fails"() {
        setup:
        GradleThread.setManaged()

        and:
        def buildOperation = Mock(RunnableBuildOperation)
        def operationDescriptionBuilder = displayName("<some-operation>").progressDisplayName("<some-op>")
        def failure = new RuntimeException()
        def progressLogger = Mock(ProgressLogger)
        def id

        when:
        operationExecutor.run(buildOperation)

        then:
        def e = thrown(RuntimeException)
        e == failure

        then:
        1 * buildOperation.description() >> operationDescriptionBuilder
        1 * timeProvider.currentTime >> 123L
        1 * listener.started(_, _) >> { BuildOperationInternal operation, OperationStartEvent start ->
            assert operation.id != null
            id = operation.id
            assert operation.parentId == null
            assert operation.displayName == "<some-operation>"
            assert start.startTime == 123L
        }

        then:
        1 * progressLoggerFactory.newOperation(_) >> progressLogger
        1 * progressLogger.setDescription("<some-operation>")
        1 * progressLogger.setShortDescription("<some-op>")
        1 * progressLogger.started()

        then:
        1 * buildOperation.run(_) >> { throw failure }

        then:
        1 * progressLogger.completed()

        then:
        1 * timeProvider.currentTime >> 124L
        1 * listener.finished(_, _) >> { BuildOperationInternal operation, OperationResult opResult ->
            assert operation.id == id
            assert opResult.startTime == 123L
            assert opResult.endTime == 124L
            assert opResult.failure == failure
        }

        cleanup:
        GradleThread.setUnmanaged()
    }

    def "action can mark operation as failed without throwing an exception"() {
        setup:
        GradleThread.setManaged()

        and:
        def buildOperation = Mock(RunnableBuildOperation)
        def failure = new RuntimeException()

        when:
        operationExecutor.run(buildOperation)

        then:
        1 * buildOperation.run(_) >> { BuildOperationContext context -> context.failed(failure) }

        then:
        1 * listener.finished(_, _) >> { BuildOperationInternal operation, OperationResult opResult ->
            assert opResult.failure == failure
        }

        cleanup:
        GradleThread.setUnmanaged()
    }

    def "action can provide operation result"() {
        setup:
        GradleThread.setManaged()

        and:
        def buildOperation = Mock(RunnableBuildOperation)
        def result = "SomeResult"

        when:
        operationExecutor.run(buildOperation)

        then:
        1 * buildOperation.run(_) >> { BuildOperationContext context -> context.result = result }

        then:
        1 * listener.finished(_, _) >> { BuildOperationInternal operation, OperationResult opResult ->
            assert opResult.result == result
        }

        cleanup:
        GradleThread.setUnmanaged()
    }

    def "does not generate progress logging when operation has no progress display name"() {
        def buildOperation = Mock(CallableBuildOperation)

        when:
        def result = operationExecutor.call(buildOperation)

        then:
        result == "result"

        then:
        1 * buildOperation.call(_) >> "result"
        0 * progressLoggerFactory._
    }

    def "multiple threads can run independent operations concurrently"() {
        def id1
        def id2

        when:
        async {
            start {
                GradleThread.setManaged()
                operationExecutor.run(runnableBuildOperation("<thread-1>") {
                    instant.action1Started
                    thread.blockUntil.action2Started
                })
            }
            thread.blockUntil.action1Started
            GradleThread.setManaged()
            operationExecutor.run(runnableBuildOperation("<thread-2>") {
                instant.action2Started
                thread.blockUntil.action1Finished
            })
        }

        then:
        1 * listener.started(_, _) >> { BuildOperationInternal operation, OperationStartEvent start ->
            id1 = operation.id
            assert operation.id != null
            assert operation.parentId == null
            assert operation.displayName == "<thread-1>"
        }
        1 * listener.started(_, _) >> { BuildOperationInternal operation, OperationStartEvent start ->
            id2 = operation.id
            assert operation.id != null
            assert operation.parentId == null
            assert operation.displayName == "<thread-2>"
        }
        1 * listener.finished(_, _) >> { BuildOperationInternal operation, OperationResult opResult ->
            assert operation.id == id1
            assert opResult.failure == null
            instant.action1Finished
        }
        1 * listener.finished(_, _) >> { BuildOperationInternal operation, OperationResult opResult ->
            assert operation.id == id2
            assert opResult.failure == null
        }
    }

    def "multiple threads can run child operations concurrently"() {
        setup:
        GradleThread.setManaged()

        and:
        def parentId
        def id1
        def id2

        when:
        operationExecutor.run(runnableBuildOperation("<main>") {
            def parentOp = operationExecutor.currentOperation
            parentId = parentOp.description.id
            async {
                start {
                    operationExecutor.setRootOperationOfCurrentThread(parentOp)
                    operationExecutor.run(runnableBuildOperation("<thread-1>") {
                        instant.action1Started
                        thread.blockUntil.action2Started
                    })
                }
                start {
                    thread.blockUntil.action1Started
                    operationExecutor.setRootOperationOfCurrentThread(parentOp)
                    operationExecutor.run(runnableBuildOperation("<thread-2>") {
                        instant.action2Started
                        thread.blockUntil.action1Finished
                    })
                }
            }
        })

        then:
        1 * listener.started(_, _) >> { BuildOperationInternal operation, OperationStartEvent start ->
            assert operation.id != null
            assert operation.parentId == null
            assert operation.displayName == "<main>"
        }
        1 * listener.started(_, _) >> { BuildOperationInternal operation, OperationStartEvent start ->
            id1 = operation.id
            assert operation.id != null
            assert operation.parentId == parentId
            assert operation.displayName == "<thread-1>"
        }
        1 * listener.started(_, _) >> { BuildOperationInternal operation, OperationStartEvent start ->
            id2 = operation.id
            assert operation.id != null
            assert operation.parentId == parentId
            assert operation.displayName == "<thread-2>"
        }
        1 * listener.finished(_, _) >> { BuildOperationInternal operation, OperationResult opResult ->
            assert operation.id == id1
            assert opResult.failure == null
            instant.action1Finished
        }
        1 * listener.finished(_, _) >> { BuildOperationInternal operation, OperationResult opResult ->
            assert operation.id == id2
            assert opResult.failure == null
        }
        1 * listener.finished(_, _) >> { BuildOperationInternal operation, OperationResult opResult ->
            assert operation.id == parentId
            assert opResult.failure == null
        }

        cleanup:
        GradleThread.setUnmanaged()
    }

    def "cannot start child operation when parent has completed"() {
        def operation

        given:
        operationExecutor.run(runnableBuildOperation("parent") {
            operation = operationExecutor.currentOperation
        })

        when:
        operationExecutor.execute(runnableBuildOperation("child") {}, operation)

        then:
        def e = thrown(IllegalStateException)
        e.message == 'Cannot start operation (child) as parent operation (parent) has already completed.'
    }

    def "child fails when parent completes while child is still running"() {
        when:
        async {
            operationExecutor.run(runnableBuildOperation("parent") {
                def operation = operationExecutor.currentOperation
                start {
                    operationExecutor.execute(runnableBuildOperation("child") {
                        instant.childStarted
                        thread.blockUntil.parentCompleted
                    }, operation)
                }
                thread.blockUntil.childStarted
            })
            instant.parentCompleted
        }

        then:
        def e = thrown(IllegalStateException)
        e.message == 'Parent operation (parent) completed before this operation (child).'
    }

    def "can query operation id from inside operation"() {
        given:
        GradleThread.setManaged()

        def parentOperation = Mock(RunnableBuildOperation)
        def childOperation = Mock(RunnableBuildOperation)
        def id

        when:
        operationExecutor.run(parentOperation)

        then:
        1 * parentOperation.run(_) >> {
            assert operationExecutor.currentOperation.description.id != null
            assert operationExecutor.currentOperation.description.parentId == null
            id = operationExecutor.currentOperation.description.id
            operationExecutor.run(childOperation)
        }
        1 * childOperation.run(_) >> {
            assert operationExecutor.currentOperation.description.id != null
            assert operationExecutor.currentOperation.description.id != id
            assert operationExecutor.currentOperation.description.parentId == id
        }

        cleanup:
        GradleThread.setUnmanaged()
    }

    def "cannot query operation id when no operation running on current managed thread"() {
        when:
        async {
            start {
                operationExecutor.run(runnableBuildOperation("operation") {
                    instant.operationRunning
                    thread.blockUntil.queried
                })
            }
            thread.blockUntil.operationRunning
            GradleThread.setManaged()
            try {
                operationExecutor.currentOperation.description
            } finally {
                instant.queried
                GradleThread.setUnmanaged()
            }
        }

        then:
        IllegalStateException e = thrown()
        e.message == "No operation is currently running."
    }

    def "cannot query current operation description when no operation running on current unmanaged thread"() {
        when:
        BuildOperationDescriptor op
        async {
            assert !GradleThread.managed
            op = operationExecutor.currentOperation.description
        }

        then:
        def ex = thrown(IllegalStateException)
        ex.message == 'No operation is currently running.'
    }


    def "can nest operations on unmanaged threads"() {
        when:
        async {
            assert !GradleThread.managed

            operationExecutor.run(new RunnableBuildOperation() {
                void run(BuildOperationContext outerContext) {
                    assert operationExecutor.currentOperation.description != null
                    assert operationExecutor.currentOperation.description.parentId < 0

                    operationExecutor.run(new RunnableBuildOperation() {
                        void run(BuildOperationContext innerContext) { }

                        BuildOperationDescriptor.Builder description() { displayName('inner') }
                    })
                }

                BuildOperationDescriptor.Builder description() { displayName('outer') }
            })
        }

        then:
        noExceptionThrown()
    }

    def "attaches parent id when operation is nested inside another"() {
        setup:
        GradleThread.setManaged()

        and:
        def operation1 = Mock(CallableBuildOperation)
        def operation2 = Mock(CallableBuildOperation)
        def operation3 = Mock(CallableBuildOperation)
        def parentId
        def child1Id
        def child2Id

        when:
        def result = operationExecutor.call(operation1)

        then:
        result == "result"

        1 * listener.started(_, _) >> { BuildOperationInternal operation, OperationStartEvent start ->
            assert operation.id != null
            parentId = operation.id
            assert operation.parentId == null
        }
        1 * operation1.call(_) >> {
            return operationExecutor.call(operation2)
        }

        and:
        1 * listener.started(_, _) >> { BuildOperationInternal operation, OperationStartEvent start ->
            child1Id = operation.id
            assert operation.parentId == parentId
        }
        1 * operation2.call(_) >> {
            return operationExecutor.call(operation3)
        }

        and:
        1 * listener.started(_, _) >> { BuildOperationInternal operation, OperationStartEvent start ->
            child2Id = operation.id
            assert operation.parentId == child1Id
        }
        1 * operation3.call(_) >> {
            return "result"
        }

        and:
        1 * listener.finished(_, _) >> { BuildOperationInternal operation, OperationResult opResult ->
            assert operation.id == child2Id
        }

        and:
        1 * listener.finished(_, _) >> { BuildOperationInternal operation, OperationResult opResult ->
            assert operation.id == child1Id
        }

        and:
        1 * listener.finished(_, _) >> { BuildOperationInternal operation, OperationResult opResult ->
            assert operation.id == parentId
        }

        cleanup:
        GradleThread.setUnmanaged()
    }

    def "attaches correct parent id when multiple threads run nested operations"() {
        def parent1Id
        def parent2Id
        def child1Id
        def child2Id

        when:
        async {
            start {
                GradleThread.setManaged()
                operationExecutor.run(runnableBuildOperation("<parent-1>") {
                    operationExecutor.run(runnableBuildOperation("<child-1>") {
                        instant.child1Started
                        thread.blockUntil.child2Started
                    })
                })
            }
            start {
                GradleThread.setManaged()
                operationExecutor.run(runnableBuildOperation("<parent-2>") {
                    operationExecutor.run(runnableBuildOperation("<child-2>") {
                        instant.child2Started
                        thread.blockUntil.child1Started
                    })
                })
            }
        }

        then:
        1 * listener.started({it.displayName == "<parent-1>" }, _) >> { BuildOperationInternal operation, OperationStartEvent start ->
            assert operation.id != null
            parent1Id = operation.id
            assert operation.parentId == null
        }
        1 * listener.started({it.displayName == "<parent-2>" }, _) >> { BuildOperationInternal operation, OperationStartEvent start ->
            assert operation.id != null
            parent2Id = operation.id
            assert operation.parentId == null
        }
        1 * listener.started({it.displayName == "<child-1>" }, _) >> { BuildOperationInternal operation, OperationStartEvent start ->
            assert operation.id != null
            child1Id = operation.id
            assert operation.parentId == parent1Id
        }
        1 * listener.started({it.displayName == "<child-2>" }, _) >> { BuildOperationInternal operation, OperationStartEvent start ->
            assert operation.id != null
            child2Id = operation.id
            assert operation.parentId == parent2Id
        }

        and:
        1 * listener.finished({ it.id == child1Id }, _)
        1 * listener.finished({ it.id == child2Id }, _)
        1 * listener.finished({ it.id == parent1Id }, _)
        1 * listener.finished({ it.id == parent2Id }, _)
    }

    def "attaches parent id when sibling operation fails"() {
        setup:
        GradleThread.setManaged()

        and:
        def operation1 = Mock(CallableBuildOperation)
        def operation2 = Mock(CallableBuildOperation)
        def operation3 = Mock(CallableBuildOperation)
        def parentId
        def child1Id
        def child2Id

        when:
        def result = operationExecutor.call(operation1)

        then:
        result == "result"

        1 * listener.started(_, _) >> { BuildOperationInternal operation, OperationStartEvent start ->
            parentId = operation.id
            assert operation.parentId == null
        }
        1 * operation1.call(_) >> {
            try {
                operationExecutor.call(operation2)
            } catch (RuntimeException) {
                // Ignore
            }
            return operationExecutor.call(operation3)
        }

        and:
        1 * listener.started(_, _) >> { BuildOperationInternal operation, OperationStartEvent start ->
            child1Id = operation.id
            assert operation.parentId == parentId
        }
        1 * operation2.call(_) >> { throw new RuntimeException() }
        1 * listener.finished(_, _) >> { BuildOperationInternal operation, OperationResult opResult ->
            assert operation.id == child1Id
        }

        and:
        1 * listener.started(_, _) >> { BuildOperationInternal operation, OperationStartEvent start ->
            child2Id = operation.id
            assert operation.parentId == parentId
        }
        1 * operation3.call(_) >> {
            return "result"
        }
        1 * listener.finished(_, _) >> { BuildOperationInternal operation, OperationResult opResult ->
            assert operation.id == child2Id
        }

        and:
        1 * listener.finished(_, _) >> { BuildOperationInternal operation, OperationResult opResult ->
            assert operation.id == parentId
        }

        cleanup:
        GradleThread.setUnmanaged()
    }

    def runnableBuildOperation(String name, Closure cl) {
        new RunnableBuildOperation() {
            void run(BuildOperationContext context) {
                cl.run()
            }

            BuildOperationDescriptor.Builder description() {
                return displayName(name)
            }
        }
    }

}
