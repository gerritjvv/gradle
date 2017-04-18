/*
 * Copyright 2016 the original author or authors.
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

package org.gradle.internal.progress;

import org.gradle.api.Action;
import org.gradle.api.Nullable;
import org.gradle.internal.operations.BuildOperation;
import org.gradle.internal.operations.BuildOperationContext;
import org.gradle.internal.operations.BuildOperationExecutor;
import org.gradle.internal.operations.BuildOperationQueue;
import org.gradle.internal.operations.BuildOperationState;
import org.gradle.internal.operations.BuildOperationWorker;
import org.gradle.internal.operations.CallableBuildOperation;
import org.gradle.internal.operations.RunnableBuildOperation;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * A BuildOperationExecutor for tests.
 * Simply execute given operations, does not support current/parent operations.
 */
public class TestBuildOperationExecutor implements BuildOperationExecutor {
    public final List<BuildOperationDescriptor> operations = new CopyOnWriteArrayList<BuildOperationDescriptor>();
    private BuildOperationState current = new BuildOperationState(BuildOperationDescriptor.displayName("current"), null, 0);

    @Override
    public BuildOperationState getCurrentOperation() {
        return current;
    }

    @Override
    public void setRootOperationOfCurrentThread(BuildOperationState buildOperationState) {
        current = buildOperationState;
    }

    @Override
    public void run(RunnableBuildOperation buildOperation) {
        operations.add(buildOperation.description().build());
        buildOperation.run(new TestBuildOperationContext());
    }

    @Override
    public <T> T call(CallableBuildOperation<T> buildOperation) {
        operations.add(buildOperation.description().build());
        return buildOperation.call(new TestBuildOperationContext());
    }

    @Override
    public <O extends RunnableBuildOperation> void runAll(Action<BuildOperationQueue<O>> schedulingAction) {
        throw new UnsupportedOperationException();
    }

    @Override
    public <O extends BuildOperation> void runAll(BuildOperationWorker<O> worker, Action<BuildOperationQueue<O>> schedulingAction) {
        throw new UnsupportedOperationException();
    }

    private static class TestBuildOperationContext implements BuildOperationContext {
        @Override
        public void failed(@Nullable Throwable failure) {
        }

        @Override
        public void setResult(@Nullable Object result) {
        }
    }
}
