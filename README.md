# JvmOptionsParser.java

Inspired by [InstallCert](https://github.com/escline/InstallCert)
and [/etc/elasticsearch/jvm.options](https://github.com/elastic/elasticsearch/blob/main/distribution/src/config/jvm.options)

## Usage

Need to compile, first:

```
javac --source 1.8 --target 1.8 -d . src/main/java/JvmOptionsParser.java
```

```bash
java JvmOptionsParser <args>
```

> **Note** since Java 11, you can run it directly without compiling it first:

```
java src/main/java/JvmOptionsParser.java <args>
```

## Jar

```bash
ant

java -jar dist/JvmOptionsParser.jar <args>
```

download precompiled jar

```bash
curl -fsSL -O https://github.com/dyrnq/JvmOptionsParser/releases/download/v0.0.1/JvmOptionsParser.jar

java -jar JvmOptionsParser.jar <args>
```

## Args and ENV

need args pass a conf folder or file

- folder eg. `java -jar JvmOptionsParser.jar /etc/es`, then `/etc/es/jvm.options + /etc/es/jvm.options.d/*.options`.
- file eg. `java -jar JvmOptionsParser.jar /etc/es/my.options`, `then /etc/es/my.options + /etc/es/jvm.options.d/*.options`.
- env ES_JAVA_OPTS JAVA_OPTS JAVA_OPTIONS JVM_OPTS JVM_OPTIONS.

## Ref

- [JVM options syntax](https://www.elastic.co/guide/en/elasticsearch/reference/current/advanced-configuration.html#jvm-options-syntax)
- [src/config/jvm.options](https://github.com/elastic/elasticsearch/blob/main/distribution/src/config/jvm.options)
- <https://developer.axonivy.com/doc/8.0/engine-guide/configuration/files/jvm-options.html>