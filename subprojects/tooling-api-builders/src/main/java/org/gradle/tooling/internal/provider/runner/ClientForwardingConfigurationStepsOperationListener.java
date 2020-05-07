/*
 * Copyright 2020 the original author or authors.
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

package org.gradle.tooling.internal.provider.runner;

import com.google.common.collect.Sets;
import org.gradle.api.internal.plugins.ApplyPluginBuildOperationType;
import org.gradle.configuration.ApplyScriptPluginBuildOperationType;
import org.gradle.internal.build.event.BuildEventSubscriptions;
import org.gradle.internal.build.event.types.DefaultOperationFinishedProgressEvent;
import org.gradle.internal.build.event.types.DefaultOperationStartedProgressEvent;
import org.gradle.internal.build.event.types.DefaultPluginApplicationDescriptor;
import org.gradle.internal.operations.BuildOperationDescriptor;
import org.gradle.internal.operations.BuildOperationListener;
import org.gradle.internal.operations.OperationFinishEvent;
import org.gradle.internal.operations.OperationIdentifier;
import org.gradle.internal.operations.OperationProgressEvent;
import org.gradle.internal.operations.OperationStartEvent;
import org.gradle.tooling.events.OperationType;
import org.gradle.tooling.internal.protocol.events.InternalOperationFinishedProgressEvent;
import org.gradle.tooling.internal.protocol.events.InternalOperationStartedProgressEvent;
import org.gradle.tooling.internal.protocol.events.InternalPluginIdentifier;

import java.util.Set;

import static org.gradle.tooling.internal.provider.runner.ClientForwardingBuildOperationListener.toOperationResult;

/**
 * Configuration steps listener that forwards all receiving events to the client via the provided {@code ProgressEventConsumer} instance.
 *
 * @since 6.8
 */
public class ClientForwardingConfigurationStepsOperationListener implements BuildOperationListener {

    private final ProgressEventConsumer eventConsumer;
    private final BuildOperationListener delegate;
    private final PluginApplicationTracker pluginApplicationTracker;

    // BuildOperationListener dispatch is not serialized
    private final Set<Object> skipEvents = Sets.newConcurrentHashSet();
    private final boolean enabled;

    ClientForwardingConfigurationStepsOperationListener(ProgressEventConsumer eventConsumer, BuildEventSubscriptions subscriptions, BuildOperationListener delegate,
                                                        PluginApplicationTracker pluginApplicationTracker) {
        this.eventConsumer = eventConsumer;
        this.delegate = delegate;
        this.pluginApplicationTracker = pluginApplicationTracker;
        this.enabled = subscriptions.isRequested(OperationType.CONFIGURATION_STEPS);
    }

    @Override
    public void started(BuildOperationDescriptor buildOperation, OperationStartEvent startEvent) {
        OperationIdentifier parentId = buildOperation.getParentId();
        if (parentId != null && skipEvents.contains(parentId)) {
            skipEvents.add(buildOperation.getId());
            return;
        }

        if (buildOperation.getDetails() instanceof ApplyPluginBuildOperationType.Details ||
            buildOperation.getDetails() instanceof ApplyScriptPluginBuildOperationType.Details) {
            if (enabled) {
                PluginApplicationTracker.PluginApplication pluginApplication = pluginApplicationTracker.getRunningPluginApplication(buildOperation.getId());
                if (pluginApplication != null) {
                    InternalOperationStartedProgressEvent startedProgressEvent =
                        new DefaultOperationStartedProgressEvent(startEvent.getStartTime(), toPluginConfigurationDescriptor(buildOperation, pluginApplication.getPlugin()));
                    eventConsumer.started(startedProgressEvent);
                } else {
                    delegate.started(buildOperation, startEvent);
                }
            } else {
                // Discard this operation and all children
                skipEvents.add(buildOperation.getId());
            }
        } else {
            delegate.started(buildOperation, startEvent);
        }
    }

    @Override
    public void progress(OperationIdentifier operationIdentifier, OperationProgressEvent progressEvent) {

    }

    @Override
    public void finished(BuildOperationDescriptor buildOperation, OperationFinishEvent finishEvent) {
        if (skipEvents.remove(buildOperation.getId())) {
            return;
        }

        if (buildOperation.getDetails() instanceof ApplyPluginBuildOperationType.Details ||
            buildOperation.getDetails() instanceof ApplyScriptPluginBuildOperationType.Details) {
            PluginApplicationTracker.PluginApplication pluginApplication = pluginApplicationTracker.getRunningPluginApplication(buildOperation.getId());
            if (pluginApplication != null) {
                InternalOperationFinishedProgressEvent finishedProgressEvent =
                    new DefaultOperationFinishedProgressEvent(finishEvent.getEndTime(), toPluginConfigurationDescriptor(buildOperation, pluginApplication.getPlugin()), toOperationResult(finishEvent));
                eventConsumer.finished(finishedProgressEvent);
            } else {
                delegate.finished(buildOperation, finishEvent);
            }
        } else {
            delegate.finished(buildOperation, finishEvent);
        }
    }

    private DefaultPluginApplicationDescriptor toPluginConfigurationDescriptor(BuildOperationDescriptor buildOperation, InternalPluginIdentifier pluginIdentifier) {
        Object id = buildOperation.getId();
        String name = buildOperation.getName();
        String displayName = buildOperation.getDisplayName();
        OperationIdentifier parentId = eventConsumer.findStartedParentId(buildOperation);
        return new DefaultPluginApplicationDescriptor(id, name, displayName, parentId, pluginIdentifier);
    }
}
