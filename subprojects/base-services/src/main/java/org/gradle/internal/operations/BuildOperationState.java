/*
 * Copyright 2017 the original author or authors.
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

package org.gradle.internal.operations;

import org.gradle.internal.progress.BuildOperationDescriptor;

import java.util.concurrent.atomic.AtomicBoolean;

public class BuildOperationState {
    private final AtomicBoolean running = new AtomicBoolean();
    private final BuildOperationState parent;
    private final BuildOperationDescriptor description;
    private final long startTime;

    public BuildOperationState(BuildOperationDescriptor.Builder descriptorBuilder, BuildOperationState parent, long startTime) {
        this.parent = parent;
        this.startTime = startTime;
        this.description = descriptorBuilder.build();
    }

    public BuildOperationState getParent() {
        return parent;
    }

    public boolean isRunning() {
        return running.get();
    }

    public void setRunning(boolean running) {
        this.running.set(running);
    }

    public BuildOperationDescriptor getDescription() {
        return description;
    }

    public long getStartTime() {
        return startTime;
    }
}
