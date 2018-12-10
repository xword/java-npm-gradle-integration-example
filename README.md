# Integrating Java and NPM builds using Gradle

This article describes how to automate building Java and JavaScript NPM-based applications with a single Gradle build.

As examples we use Spring Boot for Java and React for JavaScript/NPM, though there are no obstacles to replacing them with any similar technologies like DropWizard or Angular, using TypeScript instead of JavaScript, etc.

Our main focus is Gradle build configuration, both applications' details are of minor importance.

## Goal

We want to serve the NPM frontend application as static resource from the Java backend application. Full production package, i.e. fat JAR containing all the resources, should be automatically created via Gradle.

The NPM project should be build using Gradle, without any direct interaction with `npm` or `node` CLIs. Going further, it should not be necessary to have them installed on the system at all- especially important when building on a CI server.

## The Plan

The Java project is built with Gradle in a regular way, no fancy things here, almost.

The NPM build is done using [gradle-node-plugin](https://github.com/srs/gradle-node-plugin), which integrates NodeJS-based projects with Gradle without requiring to have NodeJS installed on the system.

Output of the NPM build is packaged into JAR file and added as a regular dependency to the Java project.

## Initial setup

Create root Gradle project, lets call it `java-npm-integration`, and `java-app` and `npm-app` as it's subprojects.


### Create root project

Create `java-npm-integration` Gradle project with the following configuration.

`java-npm-integration/build.gradle`
```groovy
defaultTasks 'build'

wrapper {
    description "Regenerates the Gradle Wrapper files"
    gradleVersion = '5.0'
    distributionUrl = "http://services.gradle.org/distributions/gradle-${gradleVersion}-all.zip"
}
```

`java-npm-integration/settings.gradle`
```groovy
rootProject.name = 'java-npm-integration'
```

The directory structure is expected to be as below:

```
java-npm-integration/
├── build.gradle
├── gradle
│   └── wrapper
│       ├── gradle-wrapper.jar
│       └── gradle-wrapper.properties
├── gradlew
├── gradlew.bat
└── settings.gradle
```

### Create `java-app` project

Generate Spring Boot application using [Spring Initializr](https://start.spring.io/), with `Web` dependency and gradle as build type. Place the generated project under `java-npm-integration` directory.

### Create `npm-app` project

Generate `npm-app` React application using [create-react-app](https://github.com/facebook/create-react-app) under `java-npm-integration` directory.

## Adapt `java-app` to be Gradle subproject of `java-npm-integration`

Remove `gradle` directory, `gradlew`, `gradlew.bat` and `settings.gradle` files from `java-app` as the are provided by the root project.

Update the root project to include `java-app` by adding the following line 
```groovy
include 'java-app'
```
to `java-npm-integration/settings.gradle`.

Now building the root project, i.e. running `./gradlew` inside `java-npm-integration` directory should build the `java-app` as well.

## Make `npm-app` be built by Gradle

This the essential part consisting of converting `npm-app` to Gradle subproject and executing npm build via Gradle script.

Create `npm-app/build.gradle` file with the following contents, already including gradle-node-plugin dependency.
```groovy
buildscript {
    repositories {
        mavenCentral()
        maven {
            url "https://plugins.gradle.org/m2/"
        }
    }

    dependencies {
        classpath 'com.moowork.gradle:gradle-node-plugin:1.2.0'
    }
}

apply plugin: 'base'
apply plugin: 'com.moowork.node' // gradle-node-plugin
```

Below add configuration for gradle-node-plugin declaring the versions of npm/NodeJS to be used. The `download` flag is crucial here as it decides about downloading npm/NodeJS by the plugin or using the ones installed in the system.
```groovy
node {
    /* gradle-node-plugin configuration
       https://github.com/srs/gradle-node-plugin/blob/master/docs/node.md

       Task name pattern:
       ./gradlew npm_<command> Executes an NPM command.
    */

    // Version of node to use.
    version = '10.14.1'

    // Version of npm to use.
    npmVersion = '6.4.1'

    // If true, it will download node using above parameters.
    // If false, it will try to use globally installed node.
    download = true
}
```

Now it's time to configure the build task. Normally te build would be done via `npm run build` command. gradle-node-plugin allows executing npm commands using the following underscore notation: `/gradlew npm_<command>`. Behind the scenes it dynamically generates Gradle task. So for our purpose the gradle task is `npm_run_build`. Let's customize it's behavior - we want to sure it is executed only when the appropriate files change.

```groovy
npm_run_build {
    inputs.files fileTree("public")
    inputs.files fileTree("src")

    inputs.file 'package.json'
    inputs.file 'package-lock.json'

    outputs.dir 'build'
}
```
One would say we are missing `node_modules` as inputs here, though this directory appeared not reliable for dependency change detection. The task was rerun without changes, probably enormous number of node_modules files does not help here either. Instead we monitor only `package.json` and `package-lock.json` as the reflect state of dependencies enough.

Finally make the Gradle build depend on executing npm build:
```groovy
assemble.dependsOn npm_run_build
```

Now include `npm-app` in the root project by adding the following line to `java-npm-integration/settings.gradle`:
```groovy
include 'npm-app'
```

At this moment you should be able to build the root project and see the npm build results under `npm-app/build` directory.

## Pack npm build result into JAR and expose to the Java project

Now we need to somehow put the npm build result into the Java package. We would like to do it without awkward copying external files into Java project resources during the build. Much more elegant and reliable way is to add them as a regular  dependency, just like any other library.

Let's update `npm-app/build.gradle` to configure this.

At first define a custom configuration to be used for passing the dependency:
```groovy
configurations {
    runtime
}

configurations.default.extendsFrom(configurations.runtime)
```

Add task packing output of the build into JAR file:
```groovy
task archive(type: Zip) {
    dependsOn npm_run_build
    baseName 'npm-app'
    extension 'jar'
    destinationDir file("${projectDir}/build_archive")
    from('build') {
        // optional path under which output will be visible in Java classpath, e.g. static resources path
        into 'static' 
    }
}
```

And the crucial part - expose the artifact created by the Zip task:
```groovy
artifacts {
    runtime(archive.archivePath) {
        builtBy archive
        type "jar"
    }
}
```

Now make the build depend on the Zip task rather than the directly npm task by replacing line
```groovy
assemble.dependsOn npm_run_build
``` 
with
```groovy
assemble.dependsOn archive
```

Don't forget to configure proper cleaning as now the output doesn't go to the standard Gradle build directory:
```groovy
clean {
    delete archive.destinationDir
}
```

Finally, include `npm-app` project as a dependency of `java-app` by adding
```groovy
runtime project(':npm-app')
```
to the `dependencies { }` block of `java-app/build.gradle`.

Now executing the root project build, i.e. `./gradlew ` in `java-npm-integration`, should result in creating `java-app` JAR containing,
apart of the java project's classes and resources, also the `npm-app` bundle packaged into JAR. 

In our case the mentioned `npm-app.jar` resides in `java-app/build/libs/java-app-0.0.1-SNAPSHOT.jar`:
```
$ zipinfo -1 java-app/build/libs/java-app-0.0.1-SNAPSHOT.jar
...
BOOT-INF/classes/eu/xword/labs/gc/JavaAppApplication.class
BOOT-INF/classes/application.properties
BOOT-INF/lib/
BOOT-INF/lib/spring-boot-starter-web-2.1.1.RELEASE.jar
BOOT-INF/lib/npm-app.jar
BOOT-INF/lib/spring-boot-starter-json-2.1.1.RELEASE.jar
BOOT-INF/lib/spring-boot-starter-2.1.1.RELEASE.jar
BOOT-INF/lib/spring-boot-starter-tomcat-2.1.1.RELEASE.jar
...
```

## What about tests?

In order to run npm tests during the Gradle build we need to create a task that would execute `npm run test` command. 

Here it's important to make sure the process started by such task exits with a proper status code, i.e. `0` for success 
and `non-0` for failure - we don't want our Gradle build pass smoothly ignoring JavaScript tests blowing up.
In our example it's enough to set `CI` environment variable - the `Jest` testing platform (default for create-react-app) 
is going to behave correctly.

```groovy
String testsExecutedMarkerName = "${projectDir}/.tests.executed"

task test(type: NpmTask) {
    dependsOn assemble

    // force Jest test runner to execute tests once and finish the process instead of starting watch mode
    environment CI: 'true'

    args = ['run', 'test']
    
    inputs.files fileTree('src')
    inputs.file 'package.json'
    inputs.file 'package-lock.json'

    // allows easy triggering re-tests
    doLast {
        new File(testsExecutedMarkerName).text = 'delete this file to force re-execution JavaScript tests'
    }
    outputs.file testsExecutedMarkerName
}
```
We also add a file marker for making re-execution of tests easier.

Finally make the project depend on tests execution
```groovy
check.dependsOn test
```

And update clean task:
```groovy
clean {
    delete archive.destinationDir
    delete testsExecutedMarkerName
}
```

That's it. Now your build includes both Java and JavaScript tests execution. 
In order to execute the latter individually just run `./gradlew npm-app:test`.

## Summary

We integrated building Java and JavaScript/NPM projects into a single Gradle project. 
The Java project is build in a standard manner, whereas the JavaScript one is build by `npm` tool wrapped with Gradle script
using `gradle-node-plugin`. The plugin can provide `npm` and `node` so they do not need to be installed on the system.

Result of the build is a standard Java package (fat JAR) additionally including JavaScript package as classpath resource 
to be served as a static asset.

Such setup can be useful for simple frontend-backend stacks when there is no need to serve frontend application from a separate server.

Full implementation of this example [can be found on GitHub](https://github.com/xword/labs/tree/master/grzegorz-cwiak/java-npm-integration).  