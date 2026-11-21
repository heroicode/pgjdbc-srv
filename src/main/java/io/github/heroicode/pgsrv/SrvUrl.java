package io.github.heroicode.pgsrv;

import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Properties;

/**
 * A parsed {@code jdbc:postgresql+srv://name[/database][?parameters]} URL.
 *
 * <p>The parameters {@code srvServiceName}, {@code srvDnsServer} and {@code srvTargetDomain}
 * belong to this wrapper. They can be given in the URL or in the connection properties (the URL
 * wins), and are not passed on to the PostgreSQL driver. {@code srvAllowedHostsSuffix}, the name
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
  private final String query;
  private final Properties properties;

  private SrvUrl(
      String srvName,
      String dnsServer,
      String targetDomain,
      String database,
      String query,
      Properties properties) {
    this.srvName = srvName;
    this.dnsServer = dnsServer;
    this.targetDomain = targetDomain;
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
    if (!SrvRecord.isHostName(host)) {
      throw invalid(url, "the DNS name is not a valid host name");
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
    String targetDomain =
        sameTargetDomain(
            url,
            (String) properties.remove(TARGET_DOMAIN_PROPERTY),
            (String) properties.remove(TARGET_DOMAIN_ALIAS));
    String urlTargetDomain = null;
    String urlTargetDomainAlias = null;

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
      } else if (TARGET_DOMAIN_PROPERTY.equals(name)) {
        urlTargetDomain = value;
      } else if (TARGET_DOMAIN_ALIAS.equals(name)) {
        urlTargetDomainAlias = value;
      } else {
        passedOn.add(parameter);
      }
    }

    if (urlTargetDomain != null || urlTargetDomainAlias != null) {
      targetDomain = sameTargetDomain(url, urlTargetDomain, urlTargetDomainAlias);
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
        throw invalid(
            url,
            SERVICE_NAME_PROPERTY
                + " cannot be used with a DNS name that starts with \"_\", because that name"
                + " already contains the service");
      }
      srvName = host;
    } else {
      if (serviceName.indexOf('.') >= 0 || !SrvRecord.isHostName(serviceName)) {
        throw invalid(url, SERVICE_NAME_PROPERTY + " is not a valid DNS label");
      }
      srvName = "_" + serviceName + "._tcp." + host;
    }

    if (targetDomain == null || targetDomain.isEmpty()) {
      targetDomain = defaultTargetDomain(host);
    } else {
      targetDomain = stripDots(targetDomain);
      if (!SrvRecord.isHostName(targetDomain)) {
        throw invalid(url, TARGET_DOMAIN_PROPERTY + " is not a valid domain name");
      }
    }
    targetDomain = targetDomain.toLowerCase(Locale.ROOT);

    return new SrvUrl(
        srvName, dnsServer, targetDomain, database, String.join("&", passedOn), properties);
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

  /** Returns the value that was given. It is an error to give the two names different values. */
  private static String sameTargetDomain(String url, String value, String aliasValue)
      throws SQLException {
    if (value == null) {
      return aliasValue;
    }
    if (aliasValue != null
        && !stripDots(value).equalsIgnoreCase(stripDots(aliasValue))) {
      throw invalid(
          url,
          TARGET_DOMAIN_PROPERTY + " and " + TARGET_DOMAIN_ALIAS + " have different values");
    }
    return value;
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

  /**
   * Returns the URL in a form that is safe to put in a message or a log. URL parameters and user
   * information can contain a password, so they are removed.
   */
  static String redact(String url) {
    int queryStart = url.indexOf('?');
    String safe = queryStart < 0 ? url : url.substring(0, queryStart);
    int authorityStart = safe.indexOf("//");
    if (authorityStart >= 0) {
      authorityStart += 2;
      int pathStart = safe.indexOf('/', authorityStart);
      int authorityEnd = pathStart < 0 ? safe.length() : pathStart;
      int userInfoEnd = safe.lastIndexOf('@', authorityEnd - 1);
      if (userInfoEnd >= authorityStart) {
        safe = safe.substring(0, authorityStart) + safe.substring(userInfoEnd + 1);
      }
    }
    return safe;
  }

  private static SQLException invalid(String url, String reason) {
    return new SQLException("Invalid URL " + redact(url) + ": " + reason, INVALID_URL_STATE);
  }
}
