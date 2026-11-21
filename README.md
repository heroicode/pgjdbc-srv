# pgjdbc-srv

[![CI](https://github.com/heroicode/pgjdbc-srv/actions/workflows/ci.yml/badge.svg?branch=main)](https://github.com/heroicode/pgjdbc-srv/actions/workflows/ci.yml)
[![Maven Central](https://img.shields.io/maven-central/v/io.github.heroicode/pgjdbc-srv)](https://central.sonatype.com/artifact/io.github.heroicode/pgjdbc-srv)
[![javadoc](https://javadoc.io/badge2/io.github.heroicode/pgjdbc-srv/javadoc.svg)](https://javadoc.io/doc/io.github.heroicode/pgjdbc-srv)
[![Java 8+](https://img.shields.io/badge/Java-8%2B-blue)](pom.xml)
[![License: MIT](https://img.shields.io/github/license/heroicode/pgjdbc-srv)](LICENSE)

DNS SRV record support for the PostgreSQL JDBC driver (pgjdbc).

This is a small JDBC driver that sits in front of pgjdbc. It has no dependencies of its own,
works with the pgjdbc version you already use, and runs on Java 8 and later.

## Install

Add this library next to the PostgreSQL JDBC driver. It does not bring pgjdbc with it, so keep
your own `org.postgresql:postgresql` dependency.

Maven:

```xml
<dependency>
  <groupId>io.github.heroicode</groupId>
  <artifactId>pgjdbc-srv</artifactId>
  <version>0.1.0</version>
</dependency>
```

Gradle (Kotlin DSL):

```kotlin
implementation("io.github.heroicode:pgjdbc-srv:0.1.0")
```

Gradle (Groovy DSL):

```groovy
implementation 'io.github.heroicode:pgjdbc-srv:0.1.0'
```

## Usage

Change the URL scheme:

```
jdbc:postgresql+srv://db.example.com/orders?sslmode=require
```

For each new connection the driver:

1. Looks up the SRV records of `_postgresql._tcp.db.example.com`.
2. Puts them in RFC 2782 order: lowest priority value first, weighted random order among
   records with the same priority.
3. Connects through pgjdbc with the equivalent multi-host URL, for example
   `jdbc:postgresql://pg1.example.com:5432,pg2.example.com:5432/orders?sslmode=require`.

pgjdbc tries the hosts in that order, so its multi-host parameters (`targetServerType`,
`loadBalanceHosts`, `connectTimeout`, ...) work as usual. All URL parameters and connection
properties are passed on to pgjdbc unchanged, except the two below.

The driver registers itself (`META-INF/services/java.sql.Driver`). If your pool wants a class
name, it is `io.github.heroicode.pgsrv.SrvDriver`.

Example zone:

```
_postgresql._tcp.db.example.com. 60 IN SRV 10 0 5432 pg1.example.com.
_postgresql._tcp.db.example.com. 60 IN SRV 20 0 5432 pg2.example.com.
```

## Parameters

Give these in the URL or in the connection properties. The URL wins.

| Parameter        | Default      | Meaning                                                              |
|------------------|--------------|----------------------------------------------------------------------|
| `srvServiceName` | `postgresql` | Service label. The lookup is for `_<srvServiceName>._tcp.<name>`.    |
| `srvDnsServer`   | OS resolver  | DNS server to query, as `host` or `host:port`.                       |

If the name in the URL already starts with `_` (for example
`jdbc:postgresql+srv://_pg._tcp.example.com/orders`), it is looked up as it is.

## Limits

- The URL takes one DNS name and no port. The SRV records supply the hosts and ports.
- The lookup runs once for each new physical connection and is not cached by this driver. An
  open connection does not move when the records change.
- A DNS failure, or a name with no SRV records, gives an `SQLException` with SQLSTATE `08001`.
  There is no fallback to an A/AAAA lookup of the name.

## Build

```
./mvnw package
```

## Relation to pgjdbc

pgjdbc does not support SRV records at this time. An open pull request,
[pgjdbc/pgjdbc#4036](https://github.com/pgjdbc/pgjdbc/pull/4036), adds the same
`jdbc:postgresql+srv://` URL scheme to pgjdbc itself. This driver uses that scheme on purpose:
when a pgjdbc release includes SRV support, remove this dependency and keep your URLs.

Before you remove it, check these differences from the pull request as it is now:

- pgjdbc has no `srvServiceName` or `srvDnsServer` parameter. It ignores them.
- pgjdbc does not accept a name that starts with `_` as a full SRV name. It always adds
  `_postgresql._tcp.` in front of the name.
- pgjdbc sorts records of equal priority by weight, highest first. This driver puts them in
  weighted random order.

If this driver and a pgjdbc with SRV support are on the classpath together, connections still
work. `DriverManager` asks the drivers in the order in which they were registered, which
usually follows the classpath order, and uses the first one that connects. To make sure that
this driver handles the URL, give its class name (`io.github.heroicode.pgsrv.SrvDriver`) to
your connection pool.

## License

[MIT](LICENSE)
