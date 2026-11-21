package io.github.heroicode.pgsrv;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.Driver;
import java.sql.DriverManager;
import java.sql.DriverPropertyInfo;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Random;
import java.util.logging.Logger;

import org.junit.jupiter.api.Test;

class SrvDriverTest {
  /** Resolver that returns fixed records and remembers what it was asked. */
  private static final class FixedResolver implements SrvResolver {
    final List<SrvRecord> records;
    String askedName;
    String askedServer;

    FixedResolver(SrvRecord... records) {
      this.records = Arrays.asList(records);
    }

    @Override
    public List<SrvRecord> resolve(String srvName, String dnsServer) {
      askedName = srvName;
      askedServer = dnsServer;
      return records;
    }
  }

  /** Delegate driver that remembers the URL and properties it was given. */
  private static final class RecordingDriver implements Driver {
    Connection connection =
        (Connection)
            Proxy.newProxyInstance(
                SrvDriverTest.class.getClassLoader(),
                new Class<?>[] {Connection.class},
                (proxy, method, args) -> null);
    String url;
    Properties info;

    @Override
    public Connection connect(String url, Properties info) {
      this.url = url;
      this.info = info;
      return connection;
    }

    @Override
    public boolean acceptsURL(String url) {
      return true;
    }

    @Override
    public DriverPropertyInfo[] getPropertyInfo(String url, Properties info) {
      return new DriverPropertyInfo[0];
    }

    @Override
    public int getMajorVersion() {
      return 0;
    }

    @Override
    public int getMinorVersion() {
      return 0;
    }

    @Override
    public boolean jdbcCompliant() {
      return false;
    }

    @Override
    public Logger getParentLogger() {
      return null;
    }
  }

  @Test
  void connectsThroughTheDelegateWithAMultiHostUrlInPriorityOrder() throws SQLException {
    FixedResolver resolver =
        new FixedResolver(
            new SrvRecord(20, 0, 5433, "standby.example.com"),
            new SrvRecord(10, 0, 5432, "primary.example.com"));
    RecordingDriver delegate = new RecordingDriver();
    SrvDriver driver = new SrvDriver(resolver, delegate, new Random(1));

    Properties info = new Properties();
    info.setProperty("user", "app");
    Connection connection =
        driver.connect(
            "jdbc:postgresql+srv://db.example.com/orders?sslmode=require&targetServerType=primary",
            info);

    assertSame(delegate.connection, connection);
    assertEquals("_postgresql._tcp.db.example.com", resolver.askedName);
    assertNull(resolver.askedServer);
    assertEquals(
        "jdbc:postgresql://primary.example.com:5432,standby.example.com:5433/orders",
        delegate.url);
    assertEquals("require", delegate.info.getProperty("sslmode"));
    assertEquals("primary", delegate.info.getProperty("targetServerType"));
    assertEquals("app", delegate.info.getProperty("user"));
  }

  @Test
  void wrapperParametersAreUsedAndNotPassedOn() throws SQLException {
    FixedResolver resolver = new FixedResolver(new SrvRecord(1, 1, 6432, "pool.example.com"));
    RecordingDriver delegate = new RecordingDriver();
    SrvDriver driver = new SrvDriver(resolver, delegate, new Random(1));

    Properties info = new Properties();
    info.setProperty("srvServiceName", "ignored-because-the-url-wins");
    info.setProperty("srvDnsServer", "10.0.0.53");
    driver.connect(
        "jdbc:postgresql+srv://db.example.com/orders?srvServiceName=pgbouncer&ssl=true", info);

    assertEquals("_pgbouncer._tcp.db.example.com", resolver.askedName);
    assertEquals("10.0.0.53", resolver.askedServer);
    assertEquals("jdbc:postgresql://pool.example.com:6432/orders", delegate.url);
    assertEquals("true", delegate.info.getProperty("ssl"));
    assertFalse(delegate.info.containsKey("srvServiceName"));
    assertFalse(delegate.info.containsKey("srvDnsServer"));
    assertTrue(info.containsKey("srvDnsServer"), "the caller's Properties must not change");
  }

  @Test
  void aNameThatStartsWithAnUnderscoreIsLookedUpAsItIs() throws SQLException {
    FixedResolver resolver = new FixedResolver(new SrvRecord(1, 1, 5432, "a.example.com"));
    SrvDriver driver = new SrvDriver(resolver, new RecordingDriver(), new Random(1));

    assertEquals(
        "jdbc:postgresql://a.example.com:5432/",
        driver.resolve("jdbc:postgresql+srv://_pg._tcp.example.com.", null));
    assertEquals("_pg._tcp.example.com", resolver.askedName);
  }

  private static String resolve(String url, String... targets) throws SQLException {
    SrvRecord[] records = new SrvRecord[targets.length];
    for (int i = 0; i < targets.length; i++) {
      records[i] = new SrvRecord(i, 0, 5432, targets[i]);
    }
    return new SrvDriver(new FixedResolver(records), new RecordingDriver(), new Random(1))
        .resolve(url, null);
  }

  @Test
  void targetsMustBeInTheParentDomainOfTheName() throws SQLException {
    String url = "jdbc:postgresql+srv://db.example.com/orders?sslmode=verify-full";

    assertEquals(
        "jdbc:postgresql://pg1.example.com:5432,a.b.EXAMPLE.com:5432,example.com:5432/orders",
        resolve(url, "pg1.example.com", "a.b.EXAMPLE.com", "example.com"));

    for (String outside :
        new String[] {"pg.attacker.net", "example.com.attacker.net", "notexample.com", "com"}) {
      SQLException e =
          assertThrows(SQLException.class, () -> resolve(url, "pg1.example.com", outside));
      assertEquals("08001", e.getSQLState());
      assertTrue(e.getMessage().contains("srvTargetDomain"), e.getMessage());
    }
  }

  @Test
  void aNameWithFewerThanThreeLabelsIsItsOwnTargetDomain() throws SQLException {
    assertEquals(
        "jdbc:postgresql://pg1.example.com:5432/",
        resolve("jdbc:postgresql+srv://example.com", "pg1.example.com"));
    assertThrows(
        SQLException.class, () -> resolve("jdbc:postgresql+srv://example.com", "pg1.other.com"));
    // The labels of a full SRV name are not part of the domain.
    assertThrows(
        SQLException.class,
        () -> resolve("jdbc:postgresql+srv://_pg._tcp.example.com", "pg1.tcp.attacker.com"));
    assertEquals(
        "jdbc:postgresql://pg1.example.com:5432/",
        resolve("jdbc:postgresql+srv://_pg._tcp.db.example.com", "pg1.example.com"));
  }

  @Test
  void theTargetDomainCanBeGiven() throws SQLException {
    String url = "jdbc:postgresql+srv://db.example.com/orders?srvTargetDomain=.Cloud.Example.NET.";

    assertEquals(
        "jdbc:postgresql://pg1.cloud.example.net:5432/orders",
        resolve(url, "pg1.cloud.example.net"));
    assertThrows(SQLException.class, () -> resolve(url, "pg1.example.com"));
    assertThrows(
        SQLException.class,
        () -> resolve("jdbc:postgresql+srv://db.example.com/?srvTargetDomain=a/b", "a.b"));
  }

  @Test
  void srvAllowedHostsSuffixIsAnotherNameForTheTargetDomain() throws SQLException {
    FixedResolver resolver = new FixedResolver(new SrvRecord(1, 0, 5432, "pg1.example.net"));
    RecordingDriver delegate = new RecordingDriver();
    SrvDriver driver = new SrvDriver(resolver, delegate, new Random(1));

    Properties info = new Properties();
    info.setProperty("srvAllowedHostsSuffix", ".example.net");
    driver.connect("jdbc:postgresql+srv://db.example.com/orders", info);
    assertEquals("jdbc:postgresql://pg1.example.net:5432/orders", delegate.url);
    assertFalse(delegate.info.containsKey("srvAllowedHostsSuffix"));

    // In the URL, and not passed on.
    assertEquals(
        "jdbc:postgresql://pg1.example.net:5432/orders",
        driver.resolve(
            "jdbc:postgresql+srv://db.example.com/orders?srvAllowedHostsSuffix=example.net&ssl=true",
            null));

    // The same value in the URL and in the properties is accepted, whichever name each uses.
    assertEquals(
        "jdbc:postgresql://pg1.example.net:5432/orders",
        driver.resolve(
            "jdbc:postgresql+srv://db.example.com/orders?srvTargetDomain=Example.NET.", info));

    // Both names with the same value are accepted. Different values are an error.
    assertEquals(
        "jdbc:postgresql://pg1.example.net:5432/orders",
        driver.resolve(
            "jdbc:postgresql+srv://db.example.com/orders"
                + "?srvTargetDomain=example.net&srvAllowedHostsSuffix=.EXAMPLE.net",
            null));
    SQLException e =
        assertThrows(
            SQLException.class,
            () ->
                driver.resolve(
                    "jdbc:postgresql+srv://db.example.com/orders"
                        + "?srvTargetDomain=example.net&srvAllowedHostsSuffix=example.org",
                    null));
    assertTrue(e.getMessage().contains("different values"), e.getMessage());
  }

  @Test
  void aServiceNameCannotBeGivenWithAFullSrvName() {
    SQLException e =
        assertThrows(
            SQLException.class,
            () ->
                resolve(
                    "jdbc:postgresql+srv://_pg._tcp.example.com/db?srvServiceName=pgbouncer",
                    "pg1.example.com"));
    assertTrue(e.getMessage().contains("srvServiceName"), e.getMessage());

    Properties info = new Properties();
    info.setProperty("srvServiceName", "pgbouncer");
    SrvDriver driver =
        new SrvDriver(
            new FixedResolver(new SrvRecord(1, 0, 5432, "pg1.example.com")),
            new RecordingDriver(),
            new Random(1));
    assertThrows(
        SQLException.class,
        () -> driver.resolve("jdbc:postgresql+srv://_pg._tcp.example.com/db", info));
  }

  @Test
  void theServiceNameMustBeOneDnsLabel() {
    for (String serviceName : new String[] {"a.b", "a/b", "a b", "-a", "a@b"}) {
      assertThrows(
          SQLException.class,
          () ->
              resolve(
                  "jdbc:postgresql+srv://db.example.com/db?srvServiceName=" + serviceName,
                  "pg1.example.com"),
          serviceName);
    }
  }

  @Test
  void thePostgresDriverIsLoadedThroughTheContextClassLoaderIfNecessary() throws Exception {
    // A class loader that cannot see the PostgreSQL driver, as in some containers.
    ClassLoader empty = new java.net.URLClassLoader(new java.net.URL[0], null);
    ClassLoader withPostgres = SrvDriverTest.class.getClassLoader();

    assertEquals(
        "org.postgresql.Driver",
        SrvDriver.loadPostgresDriver(withPostgres, empty).getClass().getName());
    assertEquals(
        "org.postgresql.Driver",
        SrvDriver.loadPostgresDriver(empty, withPostgres).getClass().getName());

    SQLException e =
        assertThrows(SQLException.class, () -> SrvDriver.loadPostgresDriver(empty, empty));
    assertEquals("08001", e.getSQLState());
    assertThrows(SQLException.class, () -> SrvDriver.loadPostgresDriver(empty, null));
  }

  @Test
  void urlParametersArePassedOnAsPropertiesAndNotInTheUrl() throws SQLException {
    RecordingDriver delegate = new RecordingDriver();
    SrvDriver driver =
        new SrvDriver(
            new FixedResolver(new SrvRecord(1, 0, 5432, "pg1.example.com")),
            delegate,
            new Random(1));
    Properties info = new Properties();
    info.setProperty("user", "from-properties");
    info.setProperty("ApplicationName", "app");

    driver.connect(
        "jdbc:postgresql+srv://db.example.com/orders"
            + "?user=from-url&password=hun%2Fter2&options=-c%20a%3Db&ssl",
        info);

    assertEquals("jdbc:postgresql://pg1.example.com:5432/orders", delegate.url);
    assertEquals("from-url", delegate.info.getProperty("user"));
    assertEquals("hun/ter2", delegate.info.getProperty("password"));
    assertEquals("-c a=b", delegate.info.getProperty("options"));
    assertEquals("", delegate.info.getProperty("ssl"));
    assertEquals("app", delegate.info.getProperty("ApplicationName"));
    assertEquals("from-properties", info.getProperty("user"), "the caller's Properties changed");

    SQLException e =
        assertThrows(
            SQLException.class,
            () -> driver.connect("jdbc:postgresql+srv://db.example.com/orders?password=x%ZZy", null));
    assertFalse(e.getMessage().contains("ZZ"), e.getMessage());
  }

  @Test
  void parameterNamesArePercentDecoded() throws SQLException {
    RecordingDriver delegate = new RecordingDriver();
    SrvDriver driver =
        new SrvDriver(
            new FixedResolver(new SrvRecord(1, 0, 5432, "pg1.example.net")),
            delegate,
            new Random(1));

    // "ssl%6Dode" is "sslmode", and "srv%54argetDomain" is "srvTargetDomain".
    driver.connect(
        "jdbc:postgresql+srv://db.example.com/orders"
            + "?ssl%6Dode=verify-full&srv%54argetDomain=example.net",
        null);

    assertEquals("verify-full", delegate.info.getProperty("sslmode"));
    assertFalse(delegate.info.containsKey("ssl%6Dode"));
    assertFalse(delegate.info.containsKey("srvTargetDomain"));
    assertEquals("jdbc:postgresql://pg1.example.net:5432/orders", delegate.url);
    assertThrows(
        SQLException.class,
        () -> driver.connect("jdbc:postgresql+srv://db.example.com/orders?ssl%ZZ=1", null));
  }

  @Test
  void theUrlCannotReplaceOrRemoveATargetDomainFromTheProperties() {
    SrvDriver driver =
        new SrvDriver(
            new FixedResolver(new SrvRecord(1, 0, 5432, "pg1.example.com")),
            new RecordingDriver(),
            new Random(1));
    for (String property : new String[] {"srvTargetDomain", "srvAllowedHostsSuffix"}) {
      Properties info = new Properties();
      info.setProperty(property, "locked.example.com");
      for (String parameter :
          new String[] {
            "srvTargetDomain=", "srvTargetDomain=example.com", "srvAllowedHostsSuffix=",
            "srvAllowedHostsSuffix=example.com", "srvTargetDomain", "srvTargetDomain=.",
          }) {
        // Without the parameter the target is outside locked.example.com, which is also an error,
        // so examine the message to see that the parameter itself was refused.
        SQLException e =
            assertThrows(
                SQLException.class,
                () -> driver.resolve("jdbc:postgresql+srv://db.example.com/db?" + parameter, info),
                parameter);
        assertTrue(e.getMessage().startsWith("Invalid "), e.getMessage());
      }
    }
    assertThrows(
        SQLException.class,
        () -> driver.resolve("jdbc:postgresql+srv://db.example.com/db?srvTargetDomain=", null));
  }

  @Test
  void aCustomResolverGetsOnlyAValidDnsServer() throws SQLException {
    FixedResolver resolver = new FixedResolver(new SrvRecord(1, 0, 5432, "pg1.example.com"));
    SrvDriver driver = new SrvDriver(resolver, new RecordingDriver(), new Random(1));

    SQLException e =
        assertThrows(
            SQLException.class,
            () ->
                driver.resolve(
                    "jdbc:postgresql+srv://db.example.com/db?srvDnsServer=evil.com/example.org",
                    null));
    assertTrue(e.getMessage().contains("srvDnsServer"), e.getMessage());
    assertNull(resolver.askedName, "the resolver was called");

    Properties info = new Properties();
    info.setProperty("srvDnsServer", "10.0.0.53 10.0.0.54");
    assertThrows(
        SQLException.class, () -> driver.resolve("jdbc:postgresql+srv://db.example.com/db", info));

    driver.resolve("jdbc:postgresql+srv://db.example.com/db?srvDnsServer=[fe80::1]:53", null);
    assertEquals("[fe80::1]:53", resolver.askedServer);
  }

  @Test
  void otherUrlsAreNotAccepted() throws SQLException {
    SrvDriver driver = new SrvDriver(new FixedResolver(), new RecordingDriver(), new Random(1));

    assertFalse(driver.acceptsURL("jdbc:postgresql://localhost/db"));
    assertNull(driver.connect("jdbc:postgresql://localhost/db", new Properties()));
    assertTrue(driver.acceptsURL("jdbc:postgresql+srv://db.example.com/db"));
  }

  @Test
  void invalidUrlsAreRejected() {
    SrvDriver driver = new SrvDriver(new FixedResolver(), new RecordingDriver(), new Random(1));

    assertThrows(SQLException.class, () -> driver.connect("jdbc:postgresql+srv:///db", null));
    assertThrows(
        SQLException.class, () -> driver.connect("jdbc:postgresql+srv://a.test:5432/db", null));
    assertThrows(
        SQLException.class, () -> driver.connect("jdbc:postgresql+srv://a.test,b.test/db", null));
  }

  @Test
  void errorMessagesDoNotContainCredentialsFromTheUrl() {
    SrvDriver driver = new SrvDriver(new FixedResolver(), new RecordingDriver(), new Random(1));
    for (String url :
        new String[] {
          "jdbc:postgresql+srv://a,b/db?user=u&password=hunter2",
          "jdbc:postgresql+srv://a.test:5432/db?password=hunter2",
          "jdbc:postgresql+srv:///db?password=hunter2",
          "jdbc:postgresql+srv://u:hunter2@a.test/db",
          "jdbc:postgresql+srv://hunter2@a.test/db",
          "jdbc:postgresql+srv://a.test/db?srvTargetDomain=a/b&password=hunter2",
          "jdbc:postgresql+srv://a.test/db?srvTargetDomain=x&srvAllowedHostsSuffix=y&password=hunter2",
          "jdbc:postgresql://u:hunter2@a.test/db?password=hunter2",
          "jdbc:postgresql+srv://user:hun/ter2@db.example.com/db",
          "jdbc:postgresql+srv://user:hun?ter2@db.example.com/db",
          "jdbc:postgresql+srv://a,b/db?password=hun@ter2",
          "jdbc:postgresql+srv://a,b/db?password=hun//ter2@x",
        }) {
      SQLException e = assertThrows(SQLException.class, () -> driver.resolve(url, null), url);
      assertFalse(e.getMessage().contains("hun"), e.getMessage());
      assertFalse(e.getMessage().contains("ter2"), e.getMessage());
      assertTrue(e.getMessage().contains("jdbc:postgresql"), e.getMessage());
    }
  }

  @Test
  void aDelegateThatDoesNotAcceptTheUrlGivesAnError() {
    RecordingDriver delegate = new RecordingDriver();
    delegate.connection = null;
    SrvDriver driver =
        new SrvDriver(
            new FixedResolver(new SrvRecord(1, 0, 5432, "pg1.example.com")),
            delegate,
            new Random(1));

    SQLException e =
        assertThrows(
            SQLException.class,
            () ->
                driver.connect(
                    "jdbc:postgresql+srv://db.example.com/orders?password=hunter2", null));
    assertEquals("08001", e.getSQLState());
    assertTrue(e.getMessage().contains("_postgresql._tcp.db.example.com"), e.getMessage());
    assertFalse(e.getMessage().contains("hunter2"), e.getMessage());
  }

  @Test
  void aServiceThatIsMarkedNotAvailableGivesAnError() {
    SrvDriver driver =
        new SrvDriver(
            new FixedResolver(new SrvRecord(0, 0, 0, ".")), new RecordingDriver(), new Random(1));

    SQLException e =
        assertThrows(
            SQLException.class, () -> driver.connect("jdbc:postgresql+srv://db.test/db", null));
    assertTrue(e.getMessage().contains("not available"), e.getMessage());
  }

  @Test
  void theDriverIsRegisteredWithDriverManager() throws Exception {
    Class.forName("io.github.heroicode.pgsrv.SrvDriver");
    assertTrue(
        DriverManager.getDriver("jdbc:postgresql+srv://db.example.com/db") instanceof SrvDriver);
  }

  @Test
  void equalPriorityRecordsAreOrderedByWeightedChance() {
    List<SrvRecord> records =
        Arrays.asList(
            new SrvRecord(10, 90, 5432, "heavy"),
            new SrvRecord(10, 10, 5432, "light"),
            new SrvRecord(5, 0, 5432, "first"),
            new SrvRecord(20, 0, 5432, "last"));
    Random random = new Random(42);
    Map<String, Integer> second = new HashMap<String, Integer>();
    for (int i = 0; i < 10000; i++) {
      List<SrvRecord> ordered = SrvRecord.order(new ArrayList<SrvRecord>(records), random);
      assertEquals(4, ordered.size());
      assertEquals("first", ordered.get(0).getTarget());
      assertEquals("last", ordered.get(3).getTarget());
      second.merge(ordered.get(1).getTarget(), 1, Integer::sum);
    }
    int heavy = second.get("heavy");
    assertTrue(heavy > 8700 && heavy < 9300, "heavy was tried first " + heavy + " times of 10000");
  }

  @Test
  void aSumOfWeightsLargerThanAnIntIsPermitted() {
    // 33,000 records of the largest weight have a sum above Integer.MAX_VALUE.
    List<SrvRecord> records = new ArrayList<SrvRecord>();
    for (int i = 0; i < 33000; i++) {
      records.add(new SrvRecord(1, 65535, 5432, "pg" + i + ".example.com"));
    }
    records.add(new SrvRecord(0, 65535, 5432, "first.example.com"));

    List<SrvRecord> ordered = SrvRecord.order(records, new Random(7));

    assertEquals(33001, ordered.size());
    assertEquals("first.example.com", ordered.get(0).getTarget());
    assertEquals(33001, new java.util.HashSet<SrvRecord>(ordered).size());
  }

  @Test
  void recordDataIsParsed() {
    SrvRecord record = SrvRecord.parse("10 60 5432 db1.example.com.");
    assertEquals(10, record.getPriority());
    assertEquals(60, record.getWeight());
    assertEquals(5432, record.getPort());
    assertEquals("db1.example.com", record.getTarget());
    assertThrows(IllegalArgumentException.class, () -> SrvRecord.parse("10 60 db1.example.com."));
  }

  @Test
  void aTargetMustBeAPlainHostName() {
    for (String target :
        new String[] {
          "evil.test/db?sslmode=disable&x=",
          "evil.test?ssl=false",
          "evil.test&user=mallory",
          "a.test,b.test",
          "a.test:1234",
          "user@a.test",
          "a.test#x",
          "a b.test",
          "a\\.test",
          "[::1]",
          "a..test",
          ".a.test",
          "-a.test",
          "a%2Ftest",
          "",
        }) {
      assertThrows(
          IllegalArgumentException.class, () -> new SrvRecord(1, 1, 5432, target), target);
    }
    assertThrows(IllegalArgumentException.class, () -> new SrvRecord(1, 1, 5432, null));

    new SrvRecord(1, 1, 5432, "pg-1.db_2.example.com");
    new SrvRecord(1, 1, 5432, "localhost");
    new SrvRecord(1, 1, 5432, "10.0.0.5");
    new SrvRecord(0, 0, 0, ".");
  }

  @Test
  void numbersMustBeInTheRangeOfTheRecordFormat() {
    assertThrows(IllegalArgumentException.class, () -> new SrvRecord(-1, 1, 5432, "a.test"));
    assertThrows(IllegalArgumentException.class, () -> new SrvRecord(1, 65536, 5432, "a.test"));
    assertThrows(IllegalArgumentException.class, () -> new SrvRecord(1, 1, 0, "a.test"));
    assertThrows(IllegalArgumentException.class, () -> new SrvRecord(1, 1, 65536, "a.test"));
    assertThrows(IllegalArgumentException.class, () -> SrvRecord.parse("1 1 99999 a.test."));
    new SrvRecord(65535, 65535, 65535, "a.test");
  }
}
