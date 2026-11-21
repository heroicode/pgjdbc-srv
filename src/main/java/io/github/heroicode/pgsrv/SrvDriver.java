package io.github.heroicode.pgsrv;

import java.sql.Connection;
import java.sql.Driver;
import java.sql.DriverManager;
import java.sql.DriverPropertyInfo;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.Random;
import java.util.logging.Logger;

/**
 * JDBC driver that adds DNS SRV record support to the PostgreSQL JDBC driver.
 *
 * <p>It accepts URLs of the form {@code jdbc:postgresql+srv://name[/database][?parameters]}. For
 * each new connection it looks up the SRV records of {@code _postgresql._tcp.name}, puts them in
 * RFC 2782 order, and connects through the PostgreSQL driver with the equivalent multi-host URL
 * {@code jdbc:postgresql://target1:port1,target2:port2/database?parameters}.
 */
public final class SrvDriver implements Driver {
  private static final String POSTGRES_DRIVER_CLASS = "org.postgresql.Driver";

  static {
    try {
      DriverManager.registerDriver(new SrvDriver());
    } catch (SQLException e) {
      throw new ExceptionInInitializerError(e);
    }
  }

  private final SrvResolver resolver;
  private final Random random;
  private volatile Driver delegate;

  public SrvDriver() {
    this(new DnsSrvResolver(), null, new Random());
  }

  /**
   * @param resolver the source of the SRV records
   * @param delegate the driver that makes the connection, or null to load the PostgreSQL driver
   * @param random the source of randomness for the weighted order of equal-priority records
   */
  public SrvDriver(SrvResolver resolver, Driver delegate, Random random) {
    this.resolver = resolver;
    this.delegate = delegate;
    this.random = random;
  }

  @Override
  public Connection connect(String url, Properties info) throws SQLException {
    if (!acceptsURL(url)) {
      return null;
    }
    SrvUrl srvUrl = SrvUrl.parse(url, info);
    return delegate().connect(resolveUrl(srvUrl), srvUrl.getProperties());
  }

  /** Returns the multi-host PostgreSQL URL that the given SRV URL resolves to at this moment. */
  public String resolve(String url, Properties info) throws SQLException {
    if (!acceptsURL(url)) {
      throw new SQLException("Not a " + SrvUrl.PREFIX + " URL: " + url, "08001");
    }
    return resolveUrl(SrvUrl.parse(url, info));
  }

  private String resolveUrl(SrvUrl srvUrl) throws SQLException {
    List<SrvRecord> usable = new ArrayList<SrvRecord>();
    for (SrvRecord record : resolver.resolve(srvUrl.getSrvName(), srvUrl.getDnsServer())) {
      if (!record.isUnavailableMarker()) {
        usable.add(record);
      }
    }
    if (usable.isEmpty()) {
      throw new SQLException(
          "The SRV records of " + srvUrl.getSrvName() + " say that the service is not available",
          "08001");
    }
    return srvUrl.toPostgresUrl(SrvRecord.order(usable, random));
  }

  @Override
  public boolean acceptsURL(String url) {
    return SrvUrl.accepts(url);
  }

  @Override
  public DriverPropertyInfo[] getPropertyInfo(String url, Properties info) {
    DriverPropertyInfo serviceName =
        new DriverPropertyInfo(SrvUrl.SERVICE_NAME_PROPERTY, SrvUrl.DEFAULT_SERVICE_NAME);
    serviceName.description =
        "Service label of the SRV name. The lookup is for _<srvServiceName>._tcp.<name>.";
    DriverPropertyInfo dnsServer = new DriverPropertyInfo(SrvUrl.DNS_SERVER_PROPERTY, null);
    dnsServer.description =
        "DNS server to query, as host or host:port. Default: the servers of the operating system.";
    return new DriverPropertyInfo[] {serviceName, dnsServer};
  }

  @Override
  public int getMajorVersion() {
    return 0;
  }

  @Override
  public int getMinorVersion() {
    return 1;
  }

  @Override
  public boolean jdbcCompliant() {
    return false;
  }

  @Override
  public Logger getParentLogger() {
    return Logger.getLogger("io.github.heroicode.pgsrv");
  }

  private Driver delegate() throws SQLException {
    Driver driver = delegate;
    if (driver == null) {
      try {
        driver =
            (Driver)
                Class.forName(POSTGRES_DRIVER_CLASS, true, SrvDriver.class.getClassLoader())
                    .getDeclaredConstructor()
                    .newInstance();
      } catch (ReflectiveOperationException | LinkageError e) {
        throw new SQLException(
            "The PostgreSQL JDBC driver (" + POSTGRES_DRIVER_CLASS + ") is not on the classpath",
            "08001",
            e);
      }
      delegate = driver;
    }
    return driver;
  }
}
