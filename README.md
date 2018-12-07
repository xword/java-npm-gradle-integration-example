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

Create `java-npm-integration` Gradle project with the following configuration. It can be done e.g. via IntelliJ's new project wizard.

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

The directory structure is expected to be as the following:

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

Make the build depend on the Zip task rather than the directly npm task - replace line
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