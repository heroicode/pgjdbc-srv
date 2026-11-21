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
 * RFC 2782 order, and connects through the PostgreSQL driver with the multi-host URL {@code
 * jdbc:postgresql://target1:port1,target2:port2/database}. The parameters of the SRV URL are
 * passed on as connection properties.
 *
 * <pre>{@code
 * Connection connection = DriverManager.getConnection(
 *     "jdbc:postgresql+srv://db.example.com/orders?sslmode=verify-full", "user", "password");
 * }</pre>
 *
 * <h2>Parameters</h2>
 *
 * <p>These parameters belong to this driver. Give them in the URL or in the connection
 * properties. If a parameter is in both, the URL is used. All other parameters and properties are
 * passed on to the PostgreSQL driver.
 *
 * <dl>
 *   <dt>{@code srvServiceName}
 *   <dd>Service label of the SRV name. The lookup is for {@code _<srvServiceName>._tcp.<name>}.
 *       Default: {@code postgresql}. If {@code name} in the URL starts with an underscore, it is
 *       looked up as it is and this parameter must not be given.
 *   <dt>{@code srvDnsServer}
 *   <dd>DNS server to query, as {@code host} or {@code host:port}. Default: the DNS servers of
 *       the operating system.
 *   <dt>{@code srvTargetDomain}
 *   <dd>Domain that all SRV targets must be in. Default: {@code name} without its first label, or
 *       {@code name} itself if it has fewer than three labels. For {@code db.example.com} the
 *       default is {@code example.com}. {@code srvAllowedHostsSuffix} is another name for this
 *       parameter.
 * </dl>
 *
 * <h2>Security</h2>
 *
 * <p>DNS chooses the hosts, and the PostgreSQL driver checks the server certificate against the
 * SRV target, not against {@code name}. For that reason the connection attempt fails with
 * SQLSTATE {@code 08001} if a target is outside the target domain or is not a plain host name.
 * Use {@code sslmode=verify-full}, and set {@code srvTargetDomain} if other parties can get host
 * names in the default domain.
 *
 * <h2>Failures</h2>
 *
 * <p>A failed DNS lookup, or a name that has no SRV records, gives an {@link SQLException} with
 * SQLSTATE {@code 08001}. The lookup is done again for each new connection and is not cached.
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

  /** Creates a driver that uses the DNS client of the JDK and the PostgreSQL JDBC driver. */
  public SrvDriver() {
    this(new DnsSrvResolver(), null, new Random());
  }

  /**
   * Creates a driver with a different source of SRV records or a different driver to connect
   * through.
   *
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
    Connection connection = delegate().connect(resolveUrl(srvUrl), srvUrl.getProperties());
    if (connection == null) {
      // A JDBC driver returns null for a URL that it does not accept. To return null from here
      // would say that this driver does not accept the SRV URL, which is not correct.
      throw new SQLException(
          "The PostgreSQL JDBC driver did not accept the URL made from the SRV records of "
              + srvUrl.getSrvName()
              + ". Examine the database name and the parameters in the URL.",
          "08001");
    }
    return connection;
  }

  /**
   * Does the DNS lookup for an SRV URL and returns the result, without making a connection. Use
   * it to see which hosts a URL gives at this moment.
   *
   * @param url a {@code jdbc:postgresql+srv://} URL
   * @param info connection properties, or null
   * @return the multi-host {@code jdbc:postgresql://} URL that {@link #connect} passes to the
   *     PostgreSQL driver. It has no parameters, because they are passed on as properties
   * @throws SQLException if the URL is not valid, the lookup fails, or a target is not permitted
   */
  public String resolve(String url, Properties info) throws SQLException {
    if (!acceptsURL(url)) {
      throw new SQLException("The URL is not a " + SrvUrl.PREFIX + " URL", "08001");
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
    // The PostgreSQL driver checks the server certificate against the SRV target, and DNS chose
    // that target. A target outside the expected domain must not be used.
    for (SrvRecord record : usable) {
      if (!srvUrl.allowsTarget(record.getTarget())) {
        throw new SQLException(
            "SRV target "
                + record.getTarget()
                + " of "
                + srvUrl.getSrvName()
                + " is not in the domain "
                + srvUrl.getTargetDomain()
                + ". If this target is correct, set the "
                + SrvUrl.TARGET_DOMAIN_PROPERTY
                + " parameter to its domain.",
            "08001");
      }
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
    DriverPropertyInfo targetDomain = new DriverPropertyInfo(SrvUrl.TARGET_DOMAIN_PROPERTY, null);
    targetDomain.description =
        "Domain that all SRV targets must be in. Default: the name in the URL without its first"
            + " label, or the name itself if it has fewer than three labels. "
            + SrvUrl.TARGET_DOMAIN_ALIAS
            + " is another name for this parameter.";
    return new DriverPropertyInfo[] {serviceName, dnsServer, targetDomain};
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
    if (driver != null) {
      return driver;
    }
    ClassLoader own = SrvDriver.class.getClassLoader();
    driver = loadPostgresDriver(own, Thread.currentThread().getContextClassLoader());
    // A driver from the context class loader belongs to the caller, for example to one web
    // application. Only a driver from the class loader of this library is kept.
    if (driver.getClass().getClassLoader() == own) {
      delegate = driver;
    }
    return driver;
  }

  /**
   * Loads the PostgreSQL driver through the class loader of this library. If it is not visible
   * there, as in a container that puts the two jars in different class loaders, the context class
   * loader of the thread is used.
   */
  static Driver loadPostgresDriver(ClassLoader own, ClassLoader context) throws SQLException {
    Throwable failure;
    try {
      return newPostgresDriver(own);
    } catch (ReflectiveOperationException | LinkageError e) {
      failure = e;
    }
    if (context != null && context != own) {
      try {
        return newPostgresDriver(context);
      } catch (ReflectiveOperationException | LinkageError e) {
        failure.addSuppressed(e);
      }
    }
    throw new SQLException(
        "The PostgreSQL JDBC driver ("
            + POSTGRES_DRIVER_CLASS
            + ") was not found by the class loader of pgjdbc-srv or by the context class loader",
        "08001",
        failure);
  }

  private static Driver newPostgresDriver(ClassLoader loader)
      throws ReflectiveOperationException {
    return (Driver)
        Class.forName(POSTGRES_DRIVER_CLASS, true, loader).getDeclaredConstructor().newInstance();
  }
}
