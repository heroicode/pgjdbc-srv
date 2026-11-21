package io.github.heroicode.pgsrv;

import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.sql.SQLException;
import java.util.List;
import java.util.Locale;
import java.util.Properties;

/**
 * A parsed {@code jdbc:postgresql+srv://name[/database][?parameters]} URL.
 *
 * <p>The parameters {@code srvServiceName}, {@code srvDnsServer} and {@code srvTargetDomain}
 * belong to this wrapper. They can be given in the URL or in the connection properties, and are
 * not passed on to the PostgreSQL driver. The URL wins, except for {@code srvTargetDomain}: its
 * values must all be the same. {@code srvAllowedHostsSuffix}, the name
 * that MongoDB drivers use, is accepted as another name for {@code srvTargetDomain}.
 */
final class SrvUrl {
  static final String PREFIX = "jdbc:postgresql+srv://";
  static final String SERVICE_NAME_PROPERTY = "srvServiceName";
  static final String DNS_SERVER_PROPERTY = "srvDnsServer";
  static final String TARGET_DOMAIN_PROPERTY = "srvTargetDomain";
  static final String TARGET_DOMAIN_ALIAS = "srvAllowedHostsSuffix";
  static final String DEFAULT_SERVICE_NAME = "postgresql";

  private static final String INVALID_URL_STATE = "08001";

  private final String srvName;
  private final String dnsServer;
  private final String targetDomain;
  private final String database;
  private final Properties properties;

  private SrvUrl(
      String srvName,
      String dnsServer,
      String targetDomain,
      String database,
      Properties properties) {
    this.srvName = srvName;
    this.dnsServer = dnsServer;
    this.targetDomain = targetDomain;
    this.database = database;
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
      throw invalid("the DNS name is missing");
    }
    if (host.indexOf(',') >= 0) {
      throw invalid("only one DNS name is permitted");
    }
    if (host.indexOf(':') >= 0) {
      throw invalid("a port is not permitted, because the SRV records supply the ports");
    }
    if (host.endsWith(".")) {
      host = host.substring(0, host.length() - 1);
    }
    if (!SrvRecord.isHostName(host)) {
      throw invalid("the DNS name is not a valid host name");
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
    String targetDomain = addTargetDomain(null, (String) properties.remove(TARGET_DOMAIN_PROPERTY));
    targetDomain = addTargetDomain(targetDomain, (String) properties.remove(TARGET_DOMAIN_ALIAS));

    // All other URL parameters are passed on as properties, not in the URL. The PostgreSQL
    // driver can put the URL that it gets in its error messages, and a parameter can be a
    // password. A URL parameter replaces a property of the same name, as in the PostgreSQL driver.
    for (String parameter : rawQuery.split("&")) {
      if (parameter.isEmpty()) {
        continue;
      }
      int equals = parameter.indexOf('=');
      String name = equals < 0 ? parameter : parameter.substring(0, equals);
      String value;
      try {
        value = equals < 0 ? "" : decode(parameter.substring(equals + 1));
      } catch (IllegalArgumentException e) {
        throw invalid("the value of a URL parameter has a percent-escape that is not valid");
      }
      if (SERVICE_NAME_PROPERTY.equals(name)) {
        serviceName = value;
      } else if (DNS_SERVER_PROPERTY.equals(name)) {
        dnsServer = value;
      } else if (TARGET_DOMAIN_PROPERTY.equals(name) || TARGET_DOMAIN_ALIAS.equals(name)) {
        targetDomain = addTargetDomain(targetDomain, value);
      } else {
        properties.setProperty(name, value);
      }
    }

    boolean serviceNameGiven = serviceName != null && !serviceName.isEmpty();
    if (!serviceNameGiven) {
      serviceName = DEFAULT_SERVICE_NAME;
    }
    if (dnsServer != null && dnsServer.isEmpty()) {
      dnsServer = null;
    }

    // A name that already starts with a "_service" label is used as it is.
    String srvName;
    if (host.startsWith("_")) {
      if (serviceNameGiven) {
        throw invalid(SERVICE_NAME_PROPERTY
                + " cannot be used with a DNS name that starts with \"_\", because that name"
                + " already contains the service");
      }
      srvName = host;
    } else {
      if (serviceName.indexOf('.') >= 0 || !SrvRecord.isHostName(serviceName)) {
        throw invalid(SERVICE_NAME_PROPERTY + " is not a valid DNS label");
      }
      srvName = "_" + serviceName + "._tcp." + host;
    }

    if (targetDomain == null) {
      targetDomain = defaultTargetDomain(host).toLowerCase(Locale.ROOT);
    }

    return new SrvUrl(srvName, dnsServer, targetDomain, database, properties);
  }

  /**
   * The domain that SRV targets must be in when the URL does not give one: the DNS name without
   * its first label, or the DNS name itself if it has fewer than three labels. Labels of an SRV
   * name ("_service", "_tcp") are not counted.
   */
  private static String defaultTargetDomain(String host) {
    String name = host;
    while (name.startsWith("_") && name.indexOf('.') >= 0) {
      name = name.substring(name.indexOf('.') + 1);
    }
    int firstDot = name.indexOf('.');
    boolean hasThreeLabels = firstDot >= 0 && name.indexOf('.', firstDot + 1) >= 0;
    return hasThreeLabels ? name.substring(firstDot + 1) : name;
  }

  /**
   * Adds one given value of the target domain. The target domain is a security limit, so a value
   * in one place cannot replace or remove a value in another place: all values must be the same.
   *
   * @param current the value from the places that were read before, or null
   * @param value the value from this place, or null if it is not given there
   * @return the target domain in lower case without dots at the ends, or null if there is none
   */
  private static String addTargetDomain(String current, String value) throws SQLException {
    if (value == null) {
      return current;
    }
    String domain = stripDots(value).toLowerCase(Locale.ROOT);
    if (!SrvRecord.isHostName(domain)) {
      throw invalid(TARGET_DOMAIN_PROPERTY + " is empty or is not a valid domain name");
    }
    if (current != null && !current.equals(domain)) {
      throw invalid(
          TARGET_DOMAIN_PROPERTY
              + " is given more than one time with different values. The URL, the connection"
              + " properties and "
              + TARGET_DOMAIN_ALIAS
              + " must agree");
    }
    return domain;
  }

  private static String stripDots(String name) {
    int start = name.startsWith(".") ? 1 : 0;
    int end = name.endsWith(".") && name.length() > start ? name.length() - 1 : name.length();
    return name.substring(start, end);
  }

  /** True if the SRV target is the target domain or a name below it. */
  boolean allowsTarget(String target) {
    String name = target.toLowerCase(Locale.ROOT);
    return name.equals(targetDomain) || name.endsWith("." + targetDomain);
  }

  String getTargetDomain() {
    return targetDomain;
  }

  /**
   * Builds the multi-host URL for the PostgreSQL driver. The driver tries the hosts in order. The
   * URL has no parameters: they are in {@link #getProperties()}.
   */
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
    return url.toString();
  }

  String getSrvName() {
    return srvName;
  }

  String getDnsServer() {
    return dnsServer;
  }

  /**
   * The caller's connection properties and URL parameters, without those that belong to this
   * wrapper.
   */
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

  private static SQLException invalid(String reason) {
    // The URL is not put in the message. It can contain a password in more than one place, and
    // a URL that is not valid cannot be divided into safe and secret parts with certainty.
    return new SQLException("Invalid " + PREFIX + " URL: " + reason, INVALID_URL_STATE);
  }
}
