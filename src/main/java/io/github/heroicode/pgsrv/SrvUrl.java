package io.github.heroicode.pgsrv;

import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

/**
 * A parsed {@code jdbc:postgresql+srv://name[/database][?parameters]} URL.
 *
 * <p>The parameters {@code srvServiceName} and {@code srvDnsServer} belong to this wrapper. They
 * can be given in the URL or in the connection properties (the URL wins), and are not passed on
 * to the PostgreSQL driver.
 */
final class SrvUrl {
  static final String PREFIX = "jdbc:postgresql+srv://";
  static final String SERVICE_NAME_PROPERTY = "srvServiceName";
  static final String DNS_SERVER_PROPERTY = "srvDnsServer";
  static final String DEFAULT_SERVICE_NAME = "postgresql";

  private static final String INVALID_URL_STATE = "08001";

  private final String srvName;
  private final String dnsServer;
  private final String database;
  private final String query;
  private final Properties properties;

  private SrvUrl(
      String srvName, String dnsServer, String database, String query, Properties properties) {
    this.srvName = srvName;
    this.dnsServer = dnsServer;
    this.database = database;
    this.query = query;
    this.properties = properties;
  }

  static boolean accepts(String url) {
    return url != null && url.startsWith(PREFIX);
  }

  static SrvUrl parse(String url, Properties info) throws SQLException {
    String rest = url.substring(PREFIX.length());

    String rawQuery = "";
    int queryStart = rest.indexOf('?');
    if (queryStart >= 0) {
      rawQuery = rest.substring(queryStart + 1);
      rest = rest.substring(0, queryStart);
    }

    String database = "";
    int pathStart = rest.indexOf('/');
    if (pathStart >= 0) {
      database = rest.substring(pathStart + 1);
      rest = rest.substring(0, pathStart);
    }

    String host = rest;
    if (host.isEmpty()) {
      throw invalid(url, "the DNS name is missing");
    }
    if (host.indexOf(',') >= 0) {
      throw invalid(url, "only one DNS name is permitted");
    }
    if (host.indexOf(':') >= 0) {
      throw invalid(url, "a port is not permitted, because the SRV records supply the ports");
    }
    if (host.endsWith(".")) {
      host = host.substring(0, host.length() - 1);
    }

    // Copy by name so that defaults nested in the caller's Properties are kept.
    Properties properties = new Properties();
    if (info != null) {
      for (String name : info.stringPropertyNames()) {
        properties.setProperty(name, info.getProperty(name));
      }
    }
    String serviceName = (String) properties.remove(SERVICE_NAME_PROPERTY);
    String dnsServer = (String) properties.remove(DNS_SERVER_PROPERTY);

    List<String> passedOn = new ArrayList<String>();
    for (String parameter : rawQuery.split("&")) {
      if (parameter.isEmpty()) {
        continue;
      }
      int equals = parameter.indexOf('=');
      String name = equals < 0 ? parameter : parameter.substring(0, equals);
      String value = equals < 0 ? "" : decode(parameter.substring(equals + 1));
      if (SERVICE_NAME_PROPERTY.equals(name)) {
        serviceName = value;
      } else if (DNS_SERVER_PROPERTY.equals(name)) {
        dnsServer = value;
      } else {
        passedOn.add(parameter);
      }
    }

    if (serviceName == null || serviceName.isEmpty()) {
      serviceName = DEFAULT_SERVICE_NAME;
    }
    if (dnsServer != null && dnsServer.isEmpty()) {
      dnsServer = null;
    }

    // A name that already starts with a "_service" label is used as it is.
    String srvName = host.startsWith("_") ? host : "_" + serviceName + "._tcp." + host;
    return new SrvUrl(srvName, dnsServer, database, String.join("&", passedOn), properties);
  }

  /** Builds the multi-host URL for the PostgreSQL driver. The driver tries the hosts in order. */
  String toPostgresUrl(List<SrvRecord> orderedRecords) {
    StringBuilder url = new StringBuilder("jdbc:postgresql://");
    for (int i = 0; i < orderedRecords.size(); i++) {
      SrvRecord record = orderedRecords.get(i);
      if (i > 0) {
        url.append(',');
      }
      url.append(record.getTarget()).append(':').append(record.getPort());
    }
    url.append('/').append(database);
    if (!query.isEmpty()) {
      url.append('?').append(query);
    }
    return url.toString();
  }

  String getSrvName() {
    return srvName;
  }

  String getDnsServer() {
    return dnsServer;
  }

  /** The caller's connection properties, without the properties that belong to this wrapper. */
  Properties getProperties() {
    return properties;
  }

  private static String decode(String value) {
    try {
      return URLDecoder.decode(value, "UTF-8");
    } catch (UnsupportedEncodingException e) {
      throw new IllegalStateException(e);
    }
  }

  private static SQLException invalid(String url, String reason) {
    return new SQLException("Invalid URL " + url + ": " + reason, INVALID_URL_STATE);
  }
}
