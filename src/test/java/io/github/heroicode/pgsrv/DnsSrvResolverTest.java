package io.github.heroicode.pgsrv;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.SQLException;
import java.util.List;
import java.util.Random;

import org.junit.jupiter.api.Test;

/** Runs the real JNDI DNS client against a DNS server on the loopback interface. */
class DnsSrvResolverTest {
  @Test
  void srvRecordsAreReadFromDns() throws Exception {
    try (FakeDnsServer dns = new FakeDnsServer()) {
      dns.add("_postgresql._tcp.db.test", new SrvRecord(20, 5, 5433, "standby.db.test"));
      dns.add("_postgresql._tcp.db.test", new SrvRecord(10, 7, 5432, "primary.db.test"));

      List<SrvRecord> records =
          new DnsSrvResolver().resolve("_postgresql._tcp.db.test", dns.address());

      assertEquals(2, records.size());
      List<SrvRecord> ordered = SrvRecord.order(records, new Random(1));
      assertEquals("10 7 5432 primary.db.test", ordered.get(0).toString());
      assertEquals("20 5 5433 standby.db.test", ordered.get(1).toString());
    }
  }

  @Test
  void theDriverResolvesAUrlThroughDns() throws Exception {
    try (FakeDnsServer dns = new FakeDnsServer()) {
      dns.add("_postgresql._tcp.db.test", new SrvRecord(1, 0, 6000, "a.db.test"));
      dns.add("_postgresql._tcp.db.test", new SrvRecord(2, 0, 6001, "b.db.test"));

      String url =
          new SrvDriver()
              .resolve(
                  "jdbc:postgresql+srv://db.test/app?srvDnsServer=" + dns.address() + "&ssl=true",
                  null);

      assertEquals("jdbc:postgresql://a.db.test:6000,b.db.test:6001/app?ssl=true", url);
    }
  }

  @Test
  void aTargetThatWouldChangeTheUrlIsRejected() throws Exception {
    try (FakeDnsServer dns = new FakeDnsServer()) {
      dns.addRaw(
          "_postgresql._tcp.db.test",
          1,
          0,
          5432,
          "evil.attacker.net/orders?sslmode=disable&user=mallory&x=");

      SQLException e =
          assertThrows(
              SQLException.class,
              () ->
                  new SrvDriver()
                      .resolve(
                          "jdbc:postgresql+srv://db.test/orders?sslmode=verify-full&srvDnsServer="
                              + dns.address(),
                          null));
      assertEquals("08001", e.getSQLState());
    }
  }

  @Test
  void theDnsServerMustBeAHostWithAnOptionalPort() {
    for (String server :
        new String[] {
          "10.0.0.53/attacker.test",
          "10.0.0.53/",
          "dns://10.0.0.53",
          "10.0.0.53 10.0.0.54",
          "10.0.0.53:0",
          "10.0.0.53:65536",
          "10.0.0.53:53:53",
          "10.0.0.53:",
          "user@10.0.0.53",
          "10.0.0.53?x=1",
          "10.0.0.53#x",
          "[::1",
          "::1",
          "[::1]x",
          "",
        }) {
      SQLException e =
          assertThrows(
              SQLException.class,
              () -> new DnsSrvResolver().resolve("_postgresql._tcp.db.test", server),
              server);
      assertEquals("08001", e.getSQLState());
      assertTrue(e.getMessage().contains("srvDnsServer"), e.getMessage());
    }
    for (String server :
        new String[] {"10.0.0.53", "10.0.0.53:5353", "ns1.example.com", "[::1]", "[fe80::1]:53"}) {
      assertTrue(DnsSrvResolver.isDnsServer(server), server);
    }
  }

  @Test
  void aNameWithoutSrvRecordsGivesAnError() throws Exception {
    try (FakeDnsServer dns = new FakeDnsServer()) {
      SQLException e =
          assertThrows(
              SQLException.class,
              () -> new DnsSrvResolver().resolve("_postgresql._tcp.missing.test", dns.address()));
      assertEquals("08001", e.getSQLState());
    }
  }
}
