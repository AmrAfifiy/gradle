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

package org.gradle.integtests.tooling.r68

import org.gradle.integtests.tooling.fixture.TargetGradleVersion
import org.gradle.integtests.tooling.fixture.ToolingApiSpecification
import org.gradle.integtests.tooling.fixture.ToolingApiVersion
import org.gradle.tooling.ProjectConnection
import org.gradle.tooling.events.BinaryPluginIdentifier
import org.gradle.tooling.events.OperationType
import org.gradle.tooling.events.ProgressListener
import org.gradle.tooling.events.ScriptPluginIdentifier
import org.gradle.tooling.events.StartEvent
import org.gradle.tooling.events.configuration.PluginApplicationStartEvent
import org.gradle.tooling.events.configuration.ProjectConfigurationStartEvent

@ToolingApiVersion('>=6.8')
@TargetGradleVersion('>=6.8')
class PluginApplicationCrossVersionSpec extends ToolingApiSpecification {

    def "plugin application events sent to configuration steps progress listener"() {
        when:
        buildAndCollectConfigurationStepsEvents(OperationType.CONFIGURATION_STEPS)
        def pluginEvents = getPluginApplicationEvents()
        PluginApplicationStartEvent javaPluginApplicationStartedEvent =
            pluginEvents.find { StartEvent event -> event.descriptor.displayName == "Apply plugin org.gradle.java to root project 'test'" }
        PluginApplicationStartEvent buildScriptApplicationStartedEvent =
            pluginEvents.find { StartEvent event -> event.descriptor.displayName == "Apply build file 'build.gradle' to root project 'test'"}

        then:
        javaPluginApplicationStartedEvent.descriptor.plugin instanceof BinaryPluginIdentifier
        (javaPluginApplicationStartedEvent.descriptor.plugin as BinaryPluginIdentifier).className == "org.gradle.api.plugins.JavaPlugin"

        buildScriptApplicationStartedEvent.descriptor.plugin instanceof ScriptPluginIdentifier
        (buildScriptApplicationStartedEvent.descriptor.plugin as ScriptPluginIdentifier).displayName == "build.gradle"
    }

    def "plugin application events have correct parent events"() {
        when:
        buildAndCollectConfigurationStepsEvents(OperationType.CONFIGURATION_STEPS, OperationType.PROJECT_CONFIGURATION)
        def pluginEvents = getPluginApplicationEvents()

        StartEvent javaPluginApplicationStartedEvent =
            pluginEvents.find { StartEvent event -> event.descriptor.displayName == "Apply plugin org.gradle.java to root project 'test'" }
        StartEvent buildScriptApplicationStartedEvent =
            pluginEvents.find { StartEvent event -> event.descriptor.displayName == "Apply build file 'build.gradle' to root project 'test'"}
        StartEvent configurationStartedEvent = progressEvents.findAll { ProjectConfigurationStartEvent.isInstance(it) }
            .find { StartEvent event -> event.descriptor.displayName == "Configure project :" }

        then:
        buildScriptApplicationStartedEvent.descriptor == javaPluginApplicationStartedEvent.descriptor.parent
        configurationStartedEvent.descriptor == buildScriptApplicationStartedEvent.descriptor.parent
    }

    def progressEvents = []

    def setup() {
        settingsFile << """
            rootProject.name = 'test'
        """

        buildFile << """
            apply plugin: 'java'
            ${mavenCentralRepository()}
        """
    }

    def buildAndCollectConfigurationStepsEvents(OperationType... operationTypes) {
        withConnection { ProjectConnection connection ->
            connection.newBuild().forTasks('assemble').addProgressListener({ event -> progressEvents << event } as ProgressListener, operationTypes).run()
        }
    }

    def getPluginApplicationEvents() {
        progressEvents.findAll { PluginApplicationStartEvent.isInstance(it) }
    }
}
