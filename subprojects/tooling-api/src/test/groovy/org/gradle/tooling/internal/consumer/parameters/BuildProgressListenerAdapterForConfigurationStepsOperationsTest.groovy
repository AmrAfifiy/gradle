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

package org.gradle.tooling.internal.consumer.parameters

import org.gradle.tooling.events.BinaryPluginIdentifier
import org.gradle.tooling.events.configuration.PluginApplicationStartEvent
import org.gradle.tooling.internal.protocol.events.InternalBinaryPluginIdentifier
import spock.lang.Specification
import org.gradle.tooling.events.FinishEvent
import org.gradle.tooling.events.OperationType
import org.gradle.tooling.events.ProgressListener
import org.gradle.tooling.internal.protocol.InternalBuildProgressListener
import org.gradle.tooling.internal.protocol.events.InternalOperationFinishedProgressEvent
import org.gradle.tooling.internal.protocol.events.InternalOperationStartedProgressEvent
import org.gradle.tooling.internal.protocol.events.InternalPluginApplicationDescriptor
import org.gradle.tooling.internal.protocol.events.InternalSuccessResult

class BuildProgressListenerAdapterForConfigurationStepsOperationsTest extends Specification {

    def "adapter is only subscribing to configuration steps progress events if at least one configuration steps progress listener is attached"() {
        when:
        def adapter = createAdapter()

        then:
        adapter.subscribedOperations == []

        when:
        def listener = Mock(ProgressListener)
        adapter = createAdapter(listener)

        then:
        adapter.subscribedOperations == [InternalBuildProgressListener.CONFIGURATION_STEPS_EXECUTION]
    }

    def "convert plugin application event to PluginApplicationStartEvent"() {
        given:
        def listener = Mock(ProgressListener)
        def adapter = createAdapter(listener)

        when:
        def plugin = Mock(InternalBinaryPluginIdentifier)
        _ * plugin.getDisplayName() >> 'test plugin'
        _ * plugin.getPluginId() >> 'test.plugin'
        _ * plugin.getClassName() >> 'org.test.PluginClass'

        def pluginApplicationDescriptor = Mock(InternalPluginApplicationDescriptor)
        _ * pluginApplicationDescriptor.getId() >> 1
        _ * pluginApplicationDescriptor.getName() >> 'Applying plugin'
        _ * pluginApplicationDescriptor.getDisplayName() >> 'Applying plugin'
        _ * pluginApplicationDescriptor.getPlugin() >> plugin

        def startEvent = Mock(InternalOperationStartedProgressEvent)
        _ * startEvent.getEventTime() >> 999
        _ * startEvent.getDisplayName() >> 'Applying plugin started'
        _ * startEvent.getDescriptor() >> pluginApplicationDescriptor

        adapter.onEvent(startEvent)

        then:
        1 * listener.statusChanged(_ as PluginApplicationStartEvent) >> { PluginApplicationStartEvent event ->
            assert event.eventTime == 999
            assert event.displayName == 'Applying plugin started'
            assert event.descriptor.displayName == 'Applying plugin'
            assert event.descriptor.plugin instanceof BinaryPluginIdentifier
            assert (event.descriptor.plugin as BinaryPluginIdentifier).displayName == 'test plugin'
            assert (event.descriptor.plugin as BinaryPluginIdentifier).pluginId == 'test.plugin'
            assert (event.descriptor.plugin as BinaryPluginIdentifier).className == 'org.test.PluginClass'
        }
    }

    def "convert plugin application event to PluginApplicationFinishEvent"() {
        given:
        def listener = Mock(ProgressListener)
        def adapter = createAdapter(listener)

        when:
        def plugin = Mock(InternalBinaryPluginIdentifier)
        _ * plugin.getDisplayName() >> 'test plugin'
        _ * plugin.getPluginId() >> 'test.plugin'
        _ * plugin.getClassName() >> 'org.test.PluginClass'

        def pluginApplicationDescriptor = Mock(InternalPluginApplicationDescriptor)
        _ * pluginApplicationDescriptor.getId() >> 1
        _ * pluginApplicationDescriptor.getName() >> 'Applying plugin'
        _ * pluginApplicationDescriptor.getDisplayName() >> 'Applying plugin'
        _ * pluginApplicationDescriptor.getPlugin() >> plugin

        def result = Mock(InternalSuccessResult)
        _ * result.getStartTime() >> 1
        _ * result.getEndTime() >> 2

        def startEvent = Mock(InternalOperationStartedProgressEvent)
        _ * startEvent.getEventTime() >> 999
        _ * startEvent.getDisplayName() >> 'Applying plugin started'
        _ * startEvent.getDescriptor() >> pluginApplicationDescriptor

        def succeededEvent = Mock(InternalOperationFinishedProgressEvent)
        _ * succeededEvent.getEventTime() >> 999
        _ * succeededEvent.getDisplayName() >> 'Applying plugin succeeded'
        _ * succeededEvent.getDescriptor() >> pluginApplicationDescriptor
        _ * succeededEvent.getResult() >> result

        adapter.onEvent(startEvent)
        adapter.onEvent(succeededEvent)

        then:
        1 * listener.statusChanged(_ as FinishEvent) >> { FinishEvent event ->
            assert event.eventTime == 999
            assert event.displayName == 'Applying plugin succeeded'
            assert event.descriptor.displayName == 'Applying plugin'
            assert event.descriptor.plugin instanceof BinaryPluginIdentifier
            assert (event.descriptor.plugin as BinaryPluginIdentifier).displayName == 'test plugin'
            assert (event.descriptor.plugin as BinaryPluginIdentifier).pluginId == 'test.plugin'
            assert (event.descriptor.plugin as BinaryPluginIdentifier).className == 'org.test.PluginClass'
            assert event.result.startTime == 1
            assert event.result.endTime == 2
        }
    }

    private static BuildProgressListenerAdapter createAdapter() {
        new BuildProgressListenerAdapter([:])
    }

    private static BuildProgressListenerAdapter createAdapter(ProgressListener testListener) {
        new BuildProgressListenerAdapter([(OperationType.CONFIGURATION_STEPS): [testListener]])
    }
}
