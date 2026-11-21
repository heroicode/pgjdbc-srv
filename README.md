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
  <version>0.2.0</version>
</dependency>
```

Gradle (Kotlin DSL):

```kotlin
implementation("io.github.heroicode:pgjdbc-srv:0.2.0")
```

Gradle (Groovy DSL):

```groovy
implementation 'io.github.heroicode:pgjdbc-srv:0.2.0'
```

Do not use version 0.1.0. It has security defects that 0.2.0 corrects: a forged DNS answer
could change the connection parameters, and SRV targets were not limited to a domain.

## Usage

Change the URL scheme:

```
jdbc:postgresql+srv://db.example.com/orders?sslmode=verify-full
```

For each new connection the driver:

1. Looks up the SRV records of `_postgresql._tcp.db.example.com`.
2. Puts them in [RFC 2782](https://datatracker.ietf.org/doc/html/rfc2782) order: lowest
   priority value first. Records with the same priority are in random order, and a record with
   a larger weight is more likely to come first. If all the weights are 0, each record has an
   equal chance.
3. Connects through pgjdbc with the multi-host URL, for example
   `jdbc:postgresql://pg1.example.com:5432,pg2.example.com:5432/orders`. The parameters of
   your URL are passed on as connection properties, not in that URL, so that a password in a
   parameter cannot appear in an error message that shows the URL.

pgjdbc tries the hosts in that order, so its multi-host parameters (`targetServerType`,
`loadBalanceHosts`, `connectTimeout`, ...) work as usual. All URL parameters and connection
properties are passed on to pgjdbc, except those below. If a parameter is in the URL and in the
properties, the URL is used.

The driver registers itself (`META-INF/services/java.sql.Driver`). If your pool wants a class
name, it is `io.github.heroicode.pgsrv.SrvDriver`.

Example zone:

```
_postgresql._tcp.db.example.com. 60 IN SRV 10 0 5432 pg1.example.com.
_postgresql._tcp.db.example.com. 60 IN SRV 20 0 5432 pg2.example.com.
```

## Parameters

Give these in the URL or in the connection properties. The URL wins, except for
`srvTargetDomain` (see Security).

| Parameter        | Default      | Meaning                                                              |
|------------------|--------------|----------------------------------------------------------------------|
| `srvServiceName` | `postgresql` | Service label. The lookup is for `_<srvServiceName>._tcp.<name>`.    |
| `srvDnsServer`   | OS resolver  | DNS server to query, as `host` or `host:port`.                       |
| `srvTargetDomain`| see Security | Domain that all SRV targets must be in.                              |

The names are case-sensitive. A parameter that starts with `srv` and is not in this table, such
as `srvtargetdomain`, is an error. This prevents a misspelled name that is ignored without a
message.

If the name in the URL already starts with `_` (for example
`jdbc:postgresql+srv://_pg._tcp.example.com/orders`), it is looked up as it is. Such a name
already contains the service, so it is an error to give `srvServiceName` with it.

## Security

DNS chooses the hosts that the driver connects to, and DNS answers can be forged. pgjdbc checks
the server certificate against the SRV target, not against the name in your URL. This means
that `sslmode=verify-full` is only as strong as the path to your DNS server.

To limit this, the driver refuses to connect if an SRV target is outside the expected domain:

- By default, the expected domain is the name in the URL without its first label. For
  `jdbc:postgresql+srv://db.example.com/orders`, all targets must be `example.com` or a name
  below it.
- If the name has fewer than three labels (`example.com`), the targets must be below that name.
- To use targets in a different domain, give that domain in `srvTargetDomain`, for example
  `?srvTargetDomain=db.cloud-provider.example`.
- `srvAllowedHostsSuffix`, the name that MongoDB drivers use, is accepted as another name for
  `srvTargetDomain`.
- A value of `srvTargetDomain` in the URL cannot replace or remove a value in the connection
  properties. If you give the parameter in more than one place, or with both names, the values
  must be the same. An empty value is an error.

A forged answer can still point at another host in the same domain. Thus:

- Use `sslmode=verify-full`. Without it, a forged answer can send your credentials to any host
  in the domain.
- Do not let the expected domain be one that other parties can get names and certificates in.
  The driver does not know which domains these are. Examples:
  - A registry suffix such as `co.uk` or `com.au`. The default for `db.co.uk` is `co.uk`.
  - A domain of a hosting or cloud platform, such as `herokuapp.com`, `github.io` or
    `azurewebsites.net`. The default for `my-db.herokuapp.com` is `herokuapp.com`.

  In such a case, set `srvTargetDomain` to a domain that only you control.
- Where you can, use a resolver that validates DNSSEC, and sign the zone.

The driver also rejects SRV targets that are not plain host names, so a DNS answer cannot add
or change connection parameters.

Do not build the URL or the connection properties from input that you do not trust.
`srvDnsServer` selects the DNS server, and `srvTargetDomain` selects the permitted domain, so a
person who controls them controls where the driver connects.

## Limits

- The URL takes one DNS name and no port. The SRV records supply the hosts and ports.
- The lookup runs once for each new physical connection and is not cached by this driver. An
  open connection does not move when the records change.
- A DNS failure, or a name with no SRV records, gives an `SQLException` with SQLSTATE `08001`.
  There is no fallback to an A/AAAA lookup of the name.
- The DNS lookup has its own time limit. The `connectTimeout` and `loginTimeout` parameters of
  pgjdbc do not change it. A DNS server that does not answer delays a new connection by
  approximately 6 seconds for each such server, before the lookup fails or the next DNS server
  is tried.

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
  random order, with a chance in proportion to the weight.

If this driver and a pgjdbc with SRV support are on the classpath together, connections still
work. `DriverManager` asks the drivers in the order in which they were registered, which
usually follows the classpath order, and uses the first one that connects. To make sure that
this driver handles the URL, give its class name (`io.github.heroicode.pgsrv.SrvDriver`) to
your connection pool.

## License

[MIT](LICENSE)
