/*
 * Copyright 2012 the original author or authors.
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

package org.gradle.execution.taskgraph;

import com.google.common.base.Function;
import com.google.common.base.Predicate;
import com.google.common.base.StandardSystemProperty;
import com.google.common.collect.*;
import org.gradle.api.*;
import org.gradle.api.file.FileCollection;
import org.gradle.api.internal.GradleInternal;
import org.gradle.api.internal.TaskInternal;
import org.gradle.api.internal.project.ProjectInternal;
import org.gradle.api.internal.tasks.CachingTaskDependencyResolveContext;
import org.gradle.api.internal.tasks.TaskContainerInternal;
import org.gradle.api.logging.Logger;
import org.gradle.api.logging.Logging;
import org.gradle.api.specs.Spec;
import org.gradle.api.specs.Specs;
import org.gradle.execution.MultipleBuildFailures;
import org.gradle.execution.TaskFailureHandler;
import org.gradle.initialization.BuildCancellationToken;
import org.gradle.internal.Pair;
import org.gradle.internal.UncheckedException;
import org.gradle.internal.graph.CachingDirectedGraphWalker;
import org.gradle.internal.graph.DirectedGraph;
import org.gradle.internal.graph.DirectedGraphRenderer;
import org.gradle.internal.graph.GraphNodeRenderer;
import org.gradle.internal.logging.text.StyledTextOutput;
import org.gradle.internal.resources.ResourceLock;
import org.gradle.internal.resources.ResourceLockCoordinationService;
import org.gradle.internal.work.WorkerLeaseRegistry.WorkerLease;
import org.gradle.internal.work.WorkerLeaseService;
import org.gradle.internal.resources.ResourceLockState;
import org.gradle.util.CollectionUtils;
import org.gradle.util.TextUtil;

import java.io.File;
import java.io.IOException;
import java.io.StringWriter;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.gradle.internal.resources.DefaultResourceLockCoordinationService.unlock;
import static org.gradle.internal.resources.ResourceLockState.Disposition.*;

/**
 * A reusable implementation of TaskExecutionPlan. The {@link #addToTaskGraph(java.util.Collection)} and {@link #clear()} methods are NOT threadsafe, and callers must synchronize access to these
 * methods.
 */
public class DefaultTaskExecutionPlan implements TaskExecutionPlan {
    private final static Logger LOGGER = Logging.getLogger(DefaultTaskExecutionPlan.class);

    private final Set<TaskInfo> tasksInUnknownState = new LinkedHashSet<TaskInfo>();
    private final Set<TaskInfo> entryTasks = new LinkedHashSet<TaskInfo>();
    private final TaskDependencyGraph graph = new TaskDependencyGraph();
    private final LinkedHashMap<Task, TaskInfo> executionPlan = new LinkedHashMap<Task, TaskInfo>();
    private final List<TaskInfo> executionQueue = new LinkedList<TaskInfo>();
    private final List<Throwable> failures = new ArrayList<Throwable>();
    private Spec<? super Task> filter = Specs.satisfyAll();

    private TaskFailureHandler failureHandler = new RethrowingFailureHandler();
    private final BuildCancellationToken cancellationToken;
    private final Set<TaskInternal> runningTasks = Sets.newIdentityHashSet();
    private final Map<Task, Set<String>> canonicalizedOutputCache = Maps.newIdentityHashMap();
    private final Map<Task, Set<String>> canonicalizedInputCache = Maps.newIdentityHashMap();
    private final Map<Task, Set<String>> canonicalizedDestroysCache = Maps.newIdentityHashMap();
    private final ResourceLockCoordinationService coordinationService;
    private final WorkerLeaseService workerLeaseService;
    private boolean tasksCancelled;

    public DefaultTaskExecutionPlan(BuildCancellationToken cancellationToken, ResourceLockCoordinationService coordinationService, WorkerLeaseService workerLeaseService) {
        this.cancellationToken = cancellationToken;
        this.coordinationService = coordinationService;
        this.workerLeaseService = workerLeaseService;
    }

    public void addToTaskGraph(Collection<? extends Task> tasks) {
        List<TaskInfo> queue = new ArrayList<TaskInfo>();

        List<Task> sortedTasks = new ArrayList<Task>(tasks);
        Collections.sort(sortedTasks);
        for (Task task : sortedTasks) {
            TaskInfo node = graph.addNode(task);
            if (node.isMustNotRun()) {
                requireWithDependencies(node);
            } else if (filter.isSatisfiedBy(task)) {
                node.require();
            }
            entryTasks.add(node);
            queue.add(node);
        }

        Set<TaskInfo> visiting = new HashSet<TaskInfo>();
        CachingTaskDependencyResolveContext context = new CachingTaskDependencyResolveContext();

        while (!queue.isEmpty()) {
            TaskInfo node = queue.get(0);
            if (node.getDependenciesProcessed()) {
                // Have already visited this task - skip it
                queue.remove(0);
                continue;
            }

            TaskInternal task = node.getTask();
            boolean filtered = !filter.isSatisfiedBy(task);
            if (filtered) {
                // Task is not required - skip it
                queue.remove(0);
                node.dependenciesProcessed();
                node.doNotRequire();
                continue;
            }

            if (visiting.add(node)) {
                // Have not seen this task before - add its dependencies to the head of the queue and leave this
                // task in the queue
                // Make sure it has been configured
                ((TaskContainerInternal) task.getProject().getTasks()).prepareForExecution(task);
                Set<? extends Task> dependsOnTasks = context.getDependencies(task);
                for (Task dependsOnTask : dependsOnTasks) {
                    TaskInfo targetNode = graph.addNode(dependsOnTask);
                    node.addDependencySuccessor(targetNode);
                    if (!visiting.contains(targetNode)) {
                        queue.add(0, targetNode);
                    }
                }
                for (Task finalizerTask : task.getFinalizedBy().getDependencies(task)) {
                    TaskInfo targetNode = graph.addNode(finalizerTask);
                    addFinalizerNode(node, targetNode);
                    if (!visiting.contains(targetNode)) {
                        queue.add(0, targetNode);
                    }
                }
                for (Task mustRunAfter : task.getMustRunAfter().getDependencies(task)) {
                    TaskInfo targetNode = graph.addNode(mustRunAfter);
                    node.addMustSuccessor(targetNode);
                }
                for (Task shouldRunAfter : task.getShouldRunAfter().getDependencies(task)) {
                    TaskInfo targetNode = graph.addNode(shouldRunAfter);
                    node.addShouldSuccessor(targetNode);
                }
                if (node.isRequired()) {
                    for (TaskInfo successor : node.getDependencySuccessors()) {
                        if (filter.isSatisfiedBy(successor.getTask())) {
                            successor.require();
                        }
                    }
                } else {
                    tasksInUnknownState.add(node);
                }
            } else {
                // Have visited this task's dependencies - add it to the graph
                queue.remove(0);
                visiting.remove(node);
                node.dependenciesProcessed();
            }
        }
        resolveTasksInUnknownState();
    }

    private void resolveTasksInUnknownState() {
        List<TaskInfo> queue = new ArrayList<TaskInfo>(tasksInUnknownState);
        Set<TaskInfo> visiting = new HashSet<TaskInfo>();

        while (!queue.isEmpty()) {
            TaskInfo task = queue.get(0);
            if (task.isInKnownState()) {
                queue.remove(0);
                continue;
            }

            if (visiting.add(task)) {
                for (TaskInfo hardPredecessor : task.getDependencyPredecessors()) {
                    if (!visiting.contains(hardPredecessor)) {
                        queue.add(0, hardPredecessor);
                    }
                }
            } else {
                queue.remove(0);
                visiting.remove(task);
                task.mustNotRun();
                for (TaskInfo predecessor : task.getDependencyPredecessors()) {
                    assert predecessor.isRequired() || predecessor.isMustNotRun();
                    if (predecessor.isRequired()) {
                        task.require();
                        break;
                    }
                }
            }
        }
    }

    private void addFinalizerNode(TaskInfo node, TaskInfo finalizerNode) {
        if (filter.isSatisfiedBy(finalizerNode.getTask())) {
            node.addFinalizer(finalizerNode);
            if (!finalizerNode.isInKnownState()) {
                finalizerNode.mustNotRun();
            }
            finalizerNode.addMustSuccessor(node);
        }
    }

    private <T> void addAllReversed(List<T> list, TreeSet<T> set) {
        List<T> elements = CollectionUtils.toList(set);
        Collections.reverse(elements);
        list.addAll(elements);
    }

    private void requireWithDependencies(TaskInfo taskInfo) {
        if (taskInfo.isMustNotRun() && filter.isSatisfiedBy(taskInfo.getTask())) {
            taskInfo.require();
            for (TaskInfo dependency : taskInfo.getDependencySuccessors()) {
                requireWithDependencies(dependency);
            }
        }
    }

    public void determineExecutionPlan() {
        List<TaskInfoInVisitingSegment> nodeQueue = Lists.newArrayList(Iterables.transform(entryTasks, new Function<TaskInfo, TaskInfoInVisitingSegment>() {
            int index;

            public TaskInfoInVisitingSegment apply(TaskInfo taskInfo) {
                return new TaskInfoInVisitingSegment(taskInfo, index++);
            }
        }));
        int visitingSegmentCounter = nodeQueue.size();

        HashMultimap<TaskInfo, Integer> visitingNodes = HashMultimap.create();
        Stack<GraphEdge> walkedShouldRunAfterEdges = new Stack<GraphEdge>();
        Stack<TaskInfo> path = new Stack<TaskInfo>();
        HashMap<TaskInfo, Integer> planBeforeVisiting = new HashMap<TaskInfo, Integer>();

        while (!nodeQueue.isEmpty()) {
            TaskInfoInVisitingSegment taskInfoInVisitingSegment = nodeQueue.get(0);
            int currentSegment = taskInfoInVisitingSegment.visitingSegment;
            TaskInfo taskNode = taskInfoInVisitingSegment.taskInfo;

            if (taskNode.isIncludeInGraph() || executionPlan.containsKey(taskNode.getTask())) {
                nodeQueue.remove(0);
                visitingNodes.remove(taskNode, currentSegment);
                maybeRemoveProcessedShouldRunAfterEdge(walkedShouldRunAfterEdges, taskNode);
                continue;
            }

            boolean alreadyVisited = visitingNodes.containsKey(taskNode);
            visitingNodes.put(taskNode, currentSegment);

            if (!alreadyVisited) {
                // Have not seen this task before - add its dependencies to the head of the queue and leave this
                // task in the queue
                recordEdgeIfArrivedViaShouldRunAfter(walkedShouldRunAfterEdges, path, taskNode);
                removeShouldRunAfterSuccessorsIfTheyImposeACycle(visitingNodes, taskInfoInVisitingSegment);
                takePlanSnapshotIfCanBeRestoredToCurrentTask(planBeforeVisiting, taskNode);
                ArrayList<TaskInfo> successors = new ArrayList<TaskInfo>();
                addAllSuccessorsInReverseOrder(taskNode, successors);
                for (TaskInfo successor : successors) {
                    if (visitingNodes.containsEntry(successor, currentSegment)) {
                        if (!walkedShouldRunAfterEdges.empty()) {
                            //remove the last walked should run after edge and restore state from before walking it
                            GraphEdge toBeRemoved = walkedShouldRunAfterEdges.pop();
                            toBeRemoved.from.removeShouldRunAfterSuccessor(toBeRemoved.to);
                            restorePath(path, toBeRemoved);
                            restoreQueue(nodeQueue, visitingNodes, toBeRemoved);
                            restoreExecutionPlan(planBeforeVisiting, toBeRemoved);
                            break;
                        } else {
                            onOrderingCycle();
                        }
                    }
                    nodeQueue.add(0, new TaskInfoInVisitingSegment(successor, currentSegment));
                }
                path.push(taskNode);
            } else {
                // Have visited this task's dependencies - add it to the end of the plan
                nodeQueue.remove(0);
                maybeRemoveProcessedShouldRunAfterEdge(walkedShouldRunAfterEdges, taskNode);
                visitingNodes.remove(taskNode, currentSegment);
                path.pop();
                executionPlan.put(taskNode.getTask(), taskNode);
                // Add any finalizers to the queue
                ArrayList<TaskInfo> finalizerTasks = new ArrayList<TaskInfo>();
                addAllReversed(finalizerTasks, taskNode.getFinalizers());
                for (TaskInfo finalizer : finalizerTasks) {
                    if (!visitingNodes.containsKey(finalizer)) {
                        nodeQueue.add(finalizerTaskPosition(finalizer, nodeQueue), new TaskInfoInVisitingSegment(finalizer, visitingSegmentCounter++));
                    }
                }
            }
        }
        executionQueue.clear();
        executionQueue.addAll(executionPlan.values());
    }

    private void maybeRemoveProcessedShouldRunAfterEdge(Stack<GraphEdge> walkedShouldRunAfterEdges, TaskInfo taskNode) {
        if (!walkedShouldRunAfterEdges.isEmpty() && walkedShouldRunAfterEdges.peek().to.equals(taskNode)) {
            walkedShouldRunAfterEdges.pop();
        }
    }

    private void restoreExecutionPlan(HashMap<TaskInfo, Integer> planBeforeVisiting, GraphEdge toBeRemoved) {
        Iterator<Map.Entry<Task, TaskInfo>> executionPlanIterator = executionPlan.entrySet().iterator();
        for (int i = 0; i < planBeforeVisiting.get(toBeRemoved.from); i++) {
            executionPlanIterator.next();
        }
        while (executionPlanIterator.hasNext()) {
            executionPlanIterator.next();
            executionPlanIterator.remove();
        }
    }

    private void restoreQueue(List<TaskInfoInVisitingSegment> nodeQueue, HashMultimap<TaskInfo, Integer> visitingNodes, GraphEdge toBeRemoved) {
        TaskInfoInVisitingSegment nextInQueue = null;
        while (nextInQueue == null || !toBeRemoved.from.equals(nextInQueue.taskInfo)) {
            nextInQueue = nodeQueue.get(0);
            visitingNodes.remove(nextInQueue.taskInfo, nextInQueue.visitingSegment);
            if (!toBeRemoved.from.equals(nextInQueue.taskInfo)) {
                nodeQueue.remove(0);
            }
        }
    }

    private void restorePath(Stack<TaskInfo> path, GraphEdge toBeRemoved) {
        TaskInfo removedFromPath = null;
        while (!toBeRemoved.from.equals(removedFromPath)) {
            removedFromPath = path.pop();
        }
    }

    private void addAllSuccessorsInReverseOrder(TaskInfo taskNode, ArrayList<TaskInfo> dependsOnTasks) {
        addAllReversed(dependsOnTasks, taskNode.getDependencySuccessors());
        addAllReversed(dependsOnTasks, taskNode.getMustSuccessors());
        addAllReversed(dependsOnTasks, taskNode.getShouldSuccessors());
    }

    private void removeShouldRunAfterSuccessorsIfTheyImposeACycle(final HashMultimap<TaskInfo, Integer> visitingNodes, final TaskInfoInVisitingSegment taskNodeWithVisitingSegment) {
        TaskInfo taskNode = taskNodeWithVisitingSegment.taskInfo;
        Iterables.removeIf(taskNode.getShouldSuccessors(), new Predicate<TaskInfo>() {
            public boolean apply(TaskInfo input) {
                return visitingNodes.containsEntry(input, taskNodeWithVisitingSegment.visitingSegment);
            }
        });
    }

    private void takePlanSnapshotIfCanBeRestoredToCurrentTask(HashMap<TaskInfo, Integer> planBeforeVisiting, TaskInfo taskNode) {
        if (taskNode.getShouldSuccessors().size() > 0) {
            planBeforeVisiting.put(taskNode, executionPlan.size());
        }
    }

    private void recordEdgeIfArrivedViaShouldRunAfter(Stack<GraphEdge> walkedShouldRunAfterEdges, Stack<TaskInfo> path, TaskInfo taskNode) {
        if (!path.empty() && path.peek().getShouldSuccessors().contains(taskNode)) {
            walkedShouldRunAfterEdges.push(new GraphEdge(path.peek(), taskNode));
        }
    }

    /**
     * Given a finalizer task, determine where in the current node queue that it should be inserted.
     * The finalizer should be inserted after any of it's preceding tasks.
     */
    private int finalizerTaskPosition(TaskInfo finalizer, final List<TaskInfoInVisitingSegment> nodeQueue) {
        if (nodeQueue.size() == 0) {
            return 0;
        }

        Set<TaskInfo> precedingTasks = getAllPrecedingTasks(finalizer);
        Set<Integer> precedingTaskIndices = CollectionUtils.collect(precedingTasks, new Transformer<Integer, TaskInfo>() {
            public Integer transform(final TaskInfo dependsOnTask) {
                return Iterables.indexOf(nodeQueue, new Predicate<TaskInfoInVisitingSegment>() {
                    public boolean apply(TaskInfoInVisitingSegment taskInfoInVisitingSegment) {
                        return taskInfoInVisitingSegment.taskInfo.equals(dependsOnTask);
                    }
                });
            }
        });
        return Collections.max(precedingTaskIndices) + 1;
    }

    private Set<TaskInfo> getAllPrecedingTasks(TaskInfo finalizer) {
        Set<TaskInfo> precedingTasks = new HashSet<TaskInfo>();
        Deque<TaskInfo> candidateTasks = new ArrayDeque<TaskInfo>();

        // Consider every task that must run before the finalizer
        candidateTasks.addAll(finalizer.getDependencySuccessors());
        candidateTasks.addAll(finalizer.getMustSuccessors());
        candidateTasks.addAll(finalizer.getShouldSuccessors());

        // For each candidate task, add it to the preceding tasks.
        while (!candidateTasks.isEmpty()) {
            TaskInfo precedingTask = candidateTasks.pop();
            if (precedingTasks.add(precedingTask)) {
                // Any task that the preceding task must run after is also a preceding task.
                candidateTasks.addAll(precedingTask.getMustSuccessors());
            }
        }

        return precedingTasks;
    }

    private void onOrderingCycle() {
        CachingDirectedGraphWalker<TaskInfo, Void> graphWalker = new CachingDirectedGraphWalker<TaskInfo, Void>(new DirectedGraph<TaskInfo, Void>() {
            public void getNodeValues(TaskInfo node, Collection<? super Void> values, Collection<? super TaskInfo> connectedNodes) {
                connectedNodes.addAll(node.getDependencySuccessors());
                connectedNodes.addAll(node.getMustSuccessors());
            }
        });
        graphWalker.add(entryTasks);
        final List<TaskInfo> firstCycle = new ArrayList<TaskInfo>(graphWalker.findCycles().get(0));
        Collections.sort(firstCycle);

        DirectedGraphRenderer<TaskInfo> graphRenderer = new DirectedGraphRenderer<TaskInfo>(new GraphNodeRenderer<TaskInfo>() {
            public void renderTo(TaskInfo node, StyledTextOutput output) {
                output.withStyle(StyledTextOutput.Style.Identifier).text(node.getTask().getPath());
            }
        }, new DirectedGraph<TaskInfo, Object>() {
            public void getNodeValues(TaskInfo node, Collection<? super Object> values, Collection<? super TaskInfo> connectedNodes) {
                for (TaskInfo dependency : firstCycle) {
                    if (node.getDependencySuccessors().contains(dependency) || node.getMustSuccessors().contains(dependency)) {
                        connectedNodes.add(dependency);
                    }
                }
            }
        });
        StringWriter writer = new StringWriter();
        graphRenderer.renderTo(firstCycle.get(0), writer);
        throw new CircularReferenceException(String.format("Circular dependency between the following tasks:%n%s", writer.toString()));
    }

    public void clear() {
        coordinationService.withStateLock(new Transformer<ResourceLockState.Disposition, ResourceLockState>() {
            @Override
            public ResourceLockState.Disposition transform(ResourceLockState resourceLockState) {
                graph.clear();
                entryTasks.clear();
                executionPlan.clear();
                executionQueue.clear();
                failures.clear();
                canonicalizedOutputCache.clear();
                runningTasks.clear();
                return FINISHED;
            }
        });
    }

    public List<Task> getTasks() {
        return new ArrayList<Task>(executionPlan.keySet());
    }

    public void useFilter(Spec<? super Task> filter) {
        this.filter = filter;
    }

    public void useFailureHandler(TaskFailureHandler handler) {
        this.failureHandler = handler;
    }

    @Override
    public boolean executeWithTask(WorkerLease parentWorkerLease, final Action<TaskInfo> taskExecution) {
        final AtomicReference<TaskInfo> selected = new AtomicReference<TaskInfo>();
        final AtomicBoolean canExecute = new AtomicBoolean();
        final ResourceLock workerLease = parentWorkerLease.createChild();
        coordinationService.withStateLock(new Transformer<ResourceLockState.Disposition, ResourceLockState>() {
            @Override
            public ResourceLockState.Disposition transform(ResourceLockState resourceLockState) {
                if (cancellationToken.isCancellationRequested()) {
                    if (abortExecution()) {
                        tasksCancelled = true;
                    }
                }

                final Iterator<TaskInfo> iterator = executionQueue.iterator();
                while (iterator.hasNext()) {
                    final TaskInfo taskInfo = iterator.next();
                    if (taskInfo.isReady() && taskInfo.allDependenciesComplete()) {
                        coordinationService.withStateLock(new Transformer<ResourceLockState.Disposition, ResourceLockState>() {
                            @Override
                            public ResourceLockState.Disposition transform(ResourceLockState resourceLockState) {
                                ResourceLock projectLock = getProjectLock(taskInfo);
                                // TODO: convert output file checks to a resource lock
                                if (projectLock.tryLock() && workerLease.tryLock() && canRunWithWithCurrentlyExecutedTasks(taskInfo)) {
                                    selected.set(taskInfo);
                                    iterator.remove();
                                    if (taskInfo.allDependenciesSuccessful()) {
                                        taskInfo.startExecution();
                                        recordTaskStarted(taskInfo);
                                        canExecute.set(true);
                                    } else {
                                        taskInfo.skipExecution();
                                    }
                                    return FINISHED;
                                } else {
                                    return FAILED;
                                }
                            }
                        });

                        if (selected.get() != null) {
                            break;
                        }
                    }
                }

                if (selected.get() == null && !allTasksComplete()) {
                    return RETRY;
                } else {
                    return FINISHED;
                }
            }
        });


        if (selected.get() != null) {
            try {
                if (canExecute.get()) {
                    taskExecution.execute(selected.get());
                }
            } finally {
                TaskInfo taskInfo = selected.get();
                ResourceLock projectLock = getProjectLock(taskInfo);
                coordinationService.withStateLock(unlock(projectLock, workerLease));
            }
        }

        // If all tasks are complete, we're done
        return !allTasksComplete();
    }

    private ResourceLock getProjectLock(TaskInfo taskInfo) {
        Project project = taskInfo.getTask().getProject();
        String gradlePath = ((GradleInternal) project.getGradle()).getIdentityPath().toString();
        String projectPath = ((ProjectInternal) project).getIdentityPath().toString();
        return workerLeaseService.getProjectLock(gradlePath, projectPath);
    }

    private boolean canRunWithWithCurrentlyExecutedTasks(TaskInfo taskInfo) {
        TaskInternal task = taskInfo.getTask();

        Pair<TaskInternal, String> overlap = firstTaskWithOverlappingOutput(task);
        if (overlap != null) {
            LOGGER.info("Cannot execute task {} in parallel with task {} due to overlapping output: {}", task.getPath(), overlap.left.getPath(), overlap.right);
            return false;
        }

        overlap = firstTaskWithOverlappingInputDestroys(task);
        if (overlap != null) {
            LOGGER.info("Cannot execute task {} in parallel with task {} due to overlapping input/destroys: {}", task.getPath(), overlap.left.getPath(), overlap.right);
            return false;
        }

        return true;
    }

    private Set<String> canonicalizedPaths(Map<Task, Set<String>> cache, FileCollection files, TaskInternal task) {
        Set<String> paths = cache.get(task);
        if (paths == null) {
            Function<File, String> canonicalize = new Function<File, String>() {
                @Override
                public String apply(File file) {
                    String path;
                    try {
                        path = file.getCanonicalPath();
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                    return path;
                }
            };
            paths = Sets.newHashSet(Iterables.transform(files, canonicalize));
            cache.put(task, paths);
        }

        return paths;
    }

    @Nullable
    private Pair<TaskInternal, String> firstTaskWithOverlappingOutput(TaskInternal candidateTask) {
        if (runningTasks.isEmpty()) {
            return null;
        }

        Set<String> candidateTaskOutputs = getOutputPaths(candidateTask);
        for (TaskInternal runningTask : runningTasks) {
            Set<String> runningTaskOutputs = getOutputPaths(runningTask);
            String firstOverlap = findFirstOverlap(candidateTaskOutputs, runningTaskOutputs);
            if (firstOverlap != null) {
                return Pair.of(runningTask, firstOverlap);
            }
        }

        return null;
    }

    @Nullable
    private Pair<TaskInternal, String> firstTaskWithOverlappingInputDestroys(TaskInternal candidateTask) {
        if (runningTasks.isEmpty()) {
            return null;
        }

        Set<String> candidateTaskInputs = getInputPaths(candidateTask);
        Set<String> candidateTaskDestroys = getDestroyPaths(candidateTask);

        for (TaskInternal runningTask : runningTasks) {
            Set<String> runningTaskInputs = getInputPaths(runningTask);
            Set<String> runningTaskDestroys = getDestroyPaths(runningTask);

            String firstOverlap = findFirstOverlap(candidateTaskInputs, runningTaskDestroys);
            if (firstOverlap != null) {
                return Pair.of(runningTask, firstOverlap);
            }

            firstOverlap = findFirstOverlap(candidateTaskDestroys, runningTaskInputs);
            if (firstOverlap != null) {
                return Pair.of(runningTask, firstOverlap);
            }
        }

        return null;
    }

    private String findFirstOverlap(Set<String> paths1, Set<String> paths2) {
        for (String path1 : paths1) {
            for (String path2 : paths2) {
                if (pathsOverlap(path1, path2)) {
                    return TextUtil.shorterOf(path1, path2);
                }
            }
        }

        return null;
    }

    private Set<String> getOutputPaths(TaskInternal task) {
        Set<String> outputPaths = Sets.newHashSet(canonicalizedPaths(canonicalizedOutputCache, task.getOutputs().getFiles(), task));
        outputPaths.addAll(canonicalizedPaths(canonicalizedDestroysCache, task.getDestroys().getFiles(), task));
        return outputPaths;
    }

    private Set<String> getInputPaths(TaskInternal task) {
        return canonicalizedPaths(canonicalizedInputCache, task.getInputs().getFiles(), task);
    }

    private Set<String> getDestroyPaths(TaskInternal task) {
        return canonicalizedPaths(canonicalizedDestroysCache, task.getDestroys().getFiles(), task);
    }

    private boolean pathsOverlap(String firstPath, String secondPath) {
        if (firstPath.equals(secondPath)) {
            return true;
        }

        String shorter;
        String longer;
        if (firstPath.length() > secondPath.length()) {
            shorter = secondPath;
            longer = firstPath;
        } else {
            shorter = firstPath;
            longer = secondPath;
        }
        return longer.startsWith(shorter + StandardSystemProperty.FILE_SEPARATOR.value());
    }

    private void recordTaskStarted(TaskInfo taskInfo) {
        TaskInternal task = taskInfo.getTask();
        runningTasks.add(task);
    }

    private void recordTaskCompleted(TaskInfo taskInfo) {
        TaskInternal task = taskInfo.getTask();
        canonicalizedOutputCache.remove(task);
        runningTasks.remove(task);
    }

    public void taskComplete(final TaskInfo taskInfo) {
        coordinationService.withStateLock(new Transformer<ResourceLockState.Disposition, ResourceLockState>() {
            @Override
            public ResourceLockState.Disposition transform(ResourceLockState resourceLockState) {
                enforceFinalizerTasks(taskInfo);
                if (taskInfo.isFailed()) {
                    handleFailure(taskInfo);
                }

                taskInfo.finishExecution();
                recordTaskCompleted(taskInfo);

                return FINISHED;
            }
        });
    }

    private void enforceFinalizerTasks(TaskInfo taskInfo) {
        for (TaskInfo finalizerNode : taskInfo.getFinalizers()) {
            if (finalizerNode.isRequired() || finalizerNode.isMustNotRun()) {
                enforceWithDependencies(finalizerNode, Sets.<TaskInfo>newHashSet());
            }
        }
    }

    private void enforceWithDependencies(TaskInfo nodeInfo, Set<TaskInfo> enforcedTasks) {
        Deque<TaskInfo> candidateNodes = new ArrayDeque<TaskInfo>();
        candidateNodes.add(nodeInfo);

        while (!candidateNodes.isEmpty()) {
            TaskInfo node = candidateNodes.pop();
            if (!enforcedTasks.contains(node)) {
                enforcedTasks.add(node);

                candidateNodes.addAll(node.getDependencySuccessors());

                if (node.isMustNotRun() || node.isRequired()) {
                    node.enforceRun();
                }
            }
        }
    }

    private void handleFailure(TaskInfo taskInfo) {
        Throwable executionFailure = taskInfo.getExecutionFailure();
        if (executionFailure != null) {
            // Always abort execution for an execution failure (as opposed to a task failure)
            abortExecution();
            this.failures.add(executionFailure);
            return;
        }

        // Task failure
        try {
            failureHandler.onTaskFailure(taskInfo.getTask());
            this.failures.add(taskInfo.getTaskFailure());
        } catch (Exception e) {
            // If the failure handler rethrows exception, then execution of other tasks is aborted. (--continue will collect failures)
            abortExecution();
            this.failures.add(e);
        }
    }

    private boolean abortExecution() {
        // Allow currently executing and enforced tasks to complete, but skip everything else.
        boolean aborted = false;
        for (TaskInfo taskInfo : executionPlan.values()) {
            if (taskInfo.isRequired()) {
                taskInfo.skipExecution();
                aborted = true;
            }
        }
        return aborted;
    }

    public void awaitCompletion() {
        coordinationService.withStateLock(new Transformer<ResourceLockState.Disposition, ResourceLockState>() {
            @Override
            public ResourceLockState.Disposition transform(ResourceLockState resourceLockState) {
                if (allTasksComplete()) {
                    rethrowFailures();
                    return FINISHED;
                } else {
                    return RETRY;
                }
            }
        });
    }

    private void rethrowFailures() {
        if (tasksCancelled) {
            failures.add(new BuildCancelledException());
        }
        if (failures.isEmpty()) {
            return;
        }

        if (failures.size() > 1) {
            throw new MultipleBuildFailures(failures);
        }

        throw UncheckedException.throwAsUncheckedException(failures.get(0));
    }

    private boolean allTasksComplete() {
        for (TaskInfo taskInfo : executionPlan.values()) {
            if (!taskInfo.isComplete()) {
                return false;
            }
        }
        return true;
    }

    private static class GraphEdge {
        private final TaskInfo from;
        private final TaskInfo to;

        private GraphEdge(TaskInfo from, TaskInfo to) {
            this.from = from;
            this.to = to;
        }
    }

    private static class TaskInfoInVisitingSegment {
        private final TaskInfo taskInfo;
        private final int visitingSegment;

        private TaskInfoInVisitingSegment(TaskInfo taskInfo, int visitingSegment) {
            this.taskInfo = taskInfo;
            this.visitingSegment = visitingSegment;
        }
    }

    private static class RethrowingFailureHandler implements TaskFailureHandler {
        public void onTaskFailure(Task task) {
            task.getState().rethrowFailure();
        }
    }
}
