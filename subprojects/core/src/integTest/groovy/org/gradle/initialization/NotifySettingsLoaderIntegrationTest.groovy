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

package org.gradle.initialization

import org.gradle.integtests.fixtures.AbstractIntegrationSpec
import org.gradle.integtests.fixtures.BuildOperationsFixture
import org.gradle.internal.operations.trace.BuildOperationRecord

class NotifySettingsLoaderIntegrationTest extends AbstractIntegrationSpec {

    final buildOperations = new BuildOperationsFixture(executer, temporaryFolder)

    def "multiproject settings with customizations are are exposed correctly"() {
        settingsFile << """
        include "b"
        include "a"
        include "a:c"
        include "a:c:d"

        findProject(':a:c:d').projectDir = file("d")
        findProject(':a:c:d').buildFileName = "d.gradle"

        rootProject.name = "root"
        rootProject.buildFileName = 'root.gradle'
        """

        when:
        succeeds('help')

        then:
        operation().result.buildPath == ":"
        operation().result.rootProject.name == "root"
        operation().result.rootProject.path == ":"
        operation().result.rootProject.identityPath == ":"
        operation().result.rootProject.projectDir == buildFile.parentFile.absolutePath
        operation().result.rootProject.buildFile == file("root.gradle").absolutePath

        project(":b").name == "b"
        project(":b").path == ":b"
        project(":b").identityPath == ":b"
        project(":b").projectDir == new File(testDirectory, "b").absolutePath
        project(":b").buildFile == new File(testDirectory, "b/build.gradle").absolutePath

        project(":a").name == "a"
        project(":a").path == ":a"
        project(":a").projectDir == new File(testDirectory, "a").absolutePath
        project(":a").buildFile == new File(testDirectory, "a/build.gradle").absolutePath

        project(":a:c").name == "c"
        project(":a:c").path == ":a:c"
        project(":a:c").projectDir == new File(testDirectory, "a/c").absolutePath
        project(":a:c").buildFile == new File(testDirectory, "a/c/build.gradle").absolutePath

        project(":a:c:d").name == "d"
        project(":a:c:d").path == ":a:c:d"
        project(":a:c:d").identityPath == ":a:c:d"
        project(":a:c:d").projectDir == new File(testDirectory, "d").absolutePath
        project(":a:c:d").buildFile == new File(testDirectory, "d/d.gradle").absolutePath
    }

    def "settings with master folder are exposed correctly"() {

        def customSettingsFile = file("master/settings.gradle")
        customSettingsFile << """
        rootProject.name = "root"
        rootProject.buildFileName = 'root.gradle'

        includeFlat "a"
        """

        def projectDirectory = testDirectory.createDir("a")

        when:
        executer.withSearchUpwards()
        projectDir(projectDirectory)
        succeeds('help')

        then:
        operation().result.buildPath == ":"
        operation().result.rootProject.name == "root"
        operation().result.rootProject.path == ":"
        operation().result.rootProject.projectDir == customSettingsFile.parentFile.absolutePath
        operation().result.rootProject.buildFile == customSettingsFile.parentFile.file("root.gradle").absolutePath

        project(":a").name == "a"
        project(":a").path == ":a"
        project(":a").identityPath == ":a"
        project(":a").projectDir == new File(testDirectory, "a").absolutePath
        project(":a").buildFile == new File(testDirectory, "a/build.gradle").absolutePath
    }

    def "settings set via cmdline flag are exposed correctly"() {
        def customSettingsDir = file("custom")
        customSettingsDir.mkdirs()
        def customSettingsFile = new File(customSettingsDir, "settings.gradle")
        customSettingsFile << """
        rootProject.name = "root"
        rootProject.buildFileName = 'root.gradle'

        include "a"
        """

        when:
        executer.withArguments("--settings-file", customSettingsFile.absolutePath)
        succeeds('help')

        then:
        operation().result.buildPath == ":"
        operation().result.rootProject.name == "root"
        operation().result.rootProject.path == ":"
        operation().result.rootProject.projectDir == customSettingsDir.absolutePath
        operation().result.rootProject.buildFile == customSettingsDir.file("root.gradle").absolutePath

        project(":a").name == "a"
        project(":a").path == ":a"
        project(":a").identityPath == ":a"
        project(":a").projectDir == new File(customSettingsDir, "a").absolutePath
        project(":a").buildFile == new File(customSettingsDir, "a/build.gradle").absolutePath
    }

    def "composite participants expose their project structure"() {
        settingsFile << """
        include "a"
        includeBuild "nested"

        rootProject.name = "root"
        rootProject.buildFileName = 'root.gradle'

        """

        file("nested/settings.gradle") << """
        rootProject.name = "nested"
        include "b"
        """

        file("nested/build.gradle") << """
        group = "org.acme"
        version = "1.0"
        """

        when:
        succeeds('help')

        def nestedBuildOperation = operations()[0]
        def rootBuildOperation = operations()[1]
        then:
        rootBuildOperation.result.buildPath == ":"
        rootBuildOperation.result.rootProject.name == "root"
        rootBuildOperation.result.rootProject.path == ":"
        rootBuildOperation.result.rootProject.projectDir == settingsFile.parentFile.absolutePath
        rootBuildOperation.result.rootProject.buildFile == settingsFile.parentFile.file("root.gradle").absolutePath

        project(":a", rootBuildOperation).name == "a"
        project(":a", rootBuildOperation).path == ":a"
        project(":a", rootBuildOperation).identityPath == ":a"
        project(":a", rootBuildOperation).projectDir == new File(settingsFile.parentFile, "a").absolutePath
        project(":a", rootBuildOperation).buildFile == new File(settingsFile.parentFile, "a/build.gradle").absolutePath

        nestedBuildOperation.result.buildPath == ":nested"
        nestedBuildOperation.result.rootProject.name == "nested"
        nestedBuildOperation.result.rootProject.path == ":"
        nestedBuildOperation.result.rootProject.identityPath == ":nested"
        nestedBuildOperation.result.rootProject.projectDir == settingsFile.parentFile.file("nested").absolutePath
        nestedBuildOperation.result.rootProject.buildFile == settingsFile.parentFile.file("nested/build.gradle").absolutePath

        project(":b", nestedBuildOperation).name == "b"
        project(":b", nestedBuildOperation).path == ":b"
        project(":b", nestedBuildOperation).identityPath == ":nested:b"
        project(":b", nestedBuildOperation).projectDir == new File(settingsFile.parentFile, "nested/b").absolutePath
        project(":b", nestedBuildOperation).buildFile == new File(settingsFile.parentFile, "nested/b/build.gradle").absolutePath
    }

    def project(String path, BuildOperationRecord operation = operation(), Map parent = null) {
        if (parent == null) {
            if (path.lastIndexOf(':') == 0) {
                return operation.result.rootProject.children.find { it.path == path }
            } else {
                return project(path, operation, project(path.substring(0, path.lastIndexOf(':'))))
            }
        }
        return parent.children.find { it.path == path }
    }

    private BuildOperationRecord operation(){
        def operationRecords = operations()
        assert operationRecords.size() == 1
        operationRecords.iterator().next()
    }
    private List<BuildOperationRecord> operations() {
        buildOperations.all(LoadingBuildBuildOperationType)
    }
}
