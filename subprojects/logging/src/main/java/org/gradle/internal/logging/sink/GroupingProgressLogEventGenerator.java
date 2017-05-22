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

package org.gradle.internal.logging.sink;

import com.google.common.base.Objects;
import org.gradle.api.Nullable;
import org.gradle.internal.logging.events.BatchOutputEventListener;
import org.gradle.internal.logging.events.EndOutputEvent;
import org.gradle.internal.logging.events.LogEvent;
import org.gradle.internal.logging.events.OperationIdentifier;
import org.gradle.internal.logging.events.OutputEvent;
import org.gradle.internal.logging.events.OutputEventListener;
import org.gradle.internal.logging.events.ProgressCompleteEvent;
import org.gradle.internal.logging.events.ProgressEvent;
import org.gradle.internal.logging.events.ProgressStartEvent;
import org.gradle.internal.logging.events.RenderableOutputEvent;
import org.gradle.internal.logging.events.StyledTextOutputEvent;
import org.gradle.internal.logging.format.LogHeaderFormatter;
import org.gradle.internal.progress.BuildOperationCategory;
import org.gradle.internal.time.TimeProvider;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * An {@code org.gradle.logging.internal.OutputEventListener} implementation which generates output events to log the
 * progress of operations.
 */
public class GroupingProgressLogEventGenerator extends BatchOutputEventListener {
    static final long LONG_RUNNING_TASK_OUTPUT_FLUSH_TIMEOUT = TimeUnit.SECONDS.toMillis(5);

    private final OutputEventListener listener;
    private final TimeProvider timeProvider;
    private final ScheduledExecutorService executor;
    private final LogHeaderFormatter headerFormatter;
    private final boolean alwaysRenderTasks;

    // Maintain a hierarchy of all build operation ids — heads up: this is a *forest*, not just 1 tree
    private final Map<Object, Object> buildOpIdHierarchy = new HashMap<Object, Object>();
    private final Map<Object, OperationGroup> operationsInProgress = new LinkedHashMap<Object, OperationGroup>();
    private final Map<OperationIdentifier, Object> progressToBuildOpIdMap = new HashMap<OperationIdentifier, Object>();

    private Object lastRenderedBuildOpId;
    private ScheduledFuture future;

    public GroupingProgressLogEventGenerator(OutputEventListener listener, TimeProvider timeProvider, LogHeaderFormatter headerFormatter, boolean alwaysRenderTasks) {
        this(listener, timeProvider, Executors.newSingleThreadScheduledExecutor(), headerFormatter, alwaysRenderTasks);
    }

    GroupingProgressLogEventGenerator(OutputEventListener listener, TimeProvider timeProvider, ScheduledExecutorService executor, LogHeaderFormatter headerFormatter, boolean alwaysRenderTasks) {
        this.listener = listener;
        this.timeProvider = timeProvider;
        this.executor = executor;
        this.headerFormatter = headerFormatter;
        this.alwaysRenderTasks = alwaysRenderTasks;
    }

    public void onOutput(OutputEvent event) {
        if (event instanceof ProgressStartEvent) {
            onStart((ProgressStartEvent) event);
        } else if (event instanceof RenderableOutputEvent) {
            handleOutput((RenderableOutputEvent) event);
        } else if (event instanceof ProgressCompleteEvent) {
            onComplete((ProgressCompleteEvent) event);
        } else if (event instanceof EndOutputEvent) {
            if (future != null && !future.isCancelled()) {
                future.cancel(false);
            }
            executor.shutdown();
            onEnd((EndOutputEvent) event);
        } else if (!(event instanceof ProgressEvent)) {
            listener.onOutput(event);
        }
    }

    private void onStart(ProgressStartEvent startEvent) {
        Object buildOpId = startEvent.getBuildOperationId();
        if (buildOpId != null) {
            buildOpIdHierarchy.put(buildOpId, startEvent.getParentBuildOperationId());
            progressToBuildOpIdMap.put(startEvent.getProgressOperationId(), buildOpId);

            // Create a new group for tasks or configure project
            if (isGroupedOperation(startEvent.getBuildOperationCategory())) {
                if (future == null || future.isCancelled()) {
                    future = executor.scheduleAtFixedRate(new Runnable() {
                        @Override
                        public void run() {
                            for (OperationGroup group : operationsInProgress.values()) {
                                group.maybeFlushOutput(timeProvider.getCurrentTime());
                            }
                        }
                    }, LONG_RUNNING_TASK_OUTPUT_FLUSH_TIMEOUT, 500, TimeUnit.MILLISECONDS);
                }
                operationsInProgress.put(buildOpId, new OperationGroup(startEvent.getCategory(), startEvent.getLoggingHeader(), startEvent.getDescription(), startEvent.getShortDescription(), startEvent.getTimestamp(), startEvent.getBuildOperationId(), startEvent.getBuildOperationCategory()));
            }
        }
    }

    private boolean isGroupedOperation(BuildOperationCategory buildOperationCategory) {
        return buildOperationCategory == BuildOperationCategory.TASK || buildOperationCategory == BuildOperationCategory.CONFIGURE_PROJECT;
    }

    private void handleOutput(RenderableOutputEvent event) {
        Object operationId = getOperationId(event.getBuildOperationId());
        if (operationId != null) {
            operationsInProgress.get(operationId).bufferOutput(event);
        } else {
            onUngroupedOutput(event);
        }
    }

    private void onComplete(ProgressCompleteEvent completeEvent) {
        Object buildOpId = progressToBuildOpIdMap.remove(completeEvent.getProgressOperationId());
        buildOpIdHierarchy.remove(buildOpId);
        OperationGroup group = operationsInProgress.remove(buildOpId);
        if (group != null) {
            group.setStatus(completeEvent.getStatus());
            group.flushOutput();
        }
    }

    private void onEnd(EndOutputEvent event) {
        for (OperationGroup group : operationsInProgress.values()) {
            group.flushOutput();
        }
        listener.onOutput(event);
        buildOpIdHierarchy.clear();
        operationsInProgress.clear();
        progressToBuildOpIdMap.clear();
    }

    private void onUngroupedOutput(RenderableOutputEvent event) {
        // Visually separate grouped output from ungrouped
        if (lastRenderedBuildOpId != null) {
            listener.onOutput(new LogEvent(event.getTimestamp(), event.getCategory(), null, "", null));
        }
        listener.onOutput(event);
        lastRenderedBuildOpId = null;
    }

    // Return the id of the operation/group, checking up the build operation hierarchy
    private Object getOperationId(@Nullable final Object buildOpId) {
        Object current = buildOpId;
        while (current != null) {
            if (operationsInProgress.containsKey(current)) {
                return current;
            }
            current = buildOpIdHierarchy.get(current);
        }
        return null;
    }

    private class OperationGroup {
        private final String category;
        private final String loggingHeader;
        private long lastUpdateTime;
        private final String description;
        private final String shortDescription;
        private final Object buildOpIdentifier;
        private final BuildOperationCategory buildOperationCategory;

        private String status;

        private List<RenderableOutputEvent> bufferedLogs = new ArrayList<RenderableOutputEvent>();

        private OperationGroup(String category, @Nullable String loggingHeader, String description, @Nullable String shortDescription, long startTime, Object buildOpIdentifier, BuildOperationCategory buildOperationCategory) {
            this.category = category;
            this.loggingHeader = loggingHeader;
            this.lastUpdateTime = startTime;
            this.description = description;
            this.shortDescription = shortDescription;
            this.lastUpdateTime = startTime;
            this.buildOpIdentifier = buildOpIdentifier;
            this.buildOperationCategory = buildOperationCategory;
        }

        StyledTextOutputEvent header() {
            return new StyledTextOutputEvent(lastUpdateTime, category, null, buildOpIdentifier, headerFormatter.format(loggingHeader, description, shortDescription, status));
        }

        synchronized void bufferOutput(RenderableOutputEvent output) {
            // Forward output immediately when the focus is on this operation group
            if (Objects.equal(buildOpIdentifier, lastRenderedBuildOpId)) {
                listener.onOutput(output);
                lastUpdateTime = timeProvider.getCurrentTime();
            } else {
                bufferedLogs.add(output);
            }
        }

        synchronized void flushOutput() {
            if (shouldForward()) {
                if (!buildOpIdentifier.equals(lastRenderedBuildOpId)) {
                    listener.onOutput(header());
                }

                for (RenderableOutputEvent renderableEvent : bufferedLogs) {
                    listener.onOutput(renderableEvent);
                }

                bufferedLogs.clear();
                lastUpdateTime = timeProvider.getCurrentTime();
                lastRenderedBuildOpId = buildOpIdentifier;
            }
        }

        synchronized void maybeFlushOutput(long now) {
            if ((lastUpdateTime + LONG_RUNNING_TASK_OUTPUT_FLUSH_TIMEOUT) < now) {
                flushOutput();
            }
        }

        private void setStatus(String status) {
            this.status = status;
        }

        private boolean shouldForward() {
            return !bufferedLogs.isEmpty() || (alwaysRenderTasks && buildOperationCategory == BuildOperationCategory.TASK);
        }
    }
}
