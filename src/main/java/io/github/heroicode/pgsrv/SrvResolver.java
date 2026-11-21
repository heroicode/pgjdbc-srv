package io.github.heroicode.pgsrv;

import java.sql.SQLException;
import java.util.List;

/** Looks up the SRV records of a DNS name. */
public interface SrvResolver {
  /**
   * @param srvName the full SRV name, for example {@code _postgresql._tcp.db.example.com}
   * @param dnsServer {@code host} or {@code host:port} of the DNS server to query, or null to use
   *     the DNS servers of the operating system
   * @return the records in the order the DNS server returned them; never empty
   * @throws SQLException if the name has no SRV records or the lookup fails
   */
  List<SrvRecord> resolve(String srvName, String dnsServer) throws SQLException;
}
