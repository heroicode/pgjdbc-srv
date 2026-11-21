package io.github.heroicode.pgsrv;

import java.sql.SQLException;
import java.util.List;

/** Looks up the SRV records of a DNS name. */
public interface SrvResolver {
  /**
   * Returns the SRV records of a name.
   *
   * @param srvName the full SRV name, for example {@code _postgresql._tcp.db.example.com}
   * @param dnsServer {@code host} or {@code host:port} of the DNS server to query, or null to use
   *     the DNS servers of the operating system. {@link SrvDriver} gives only a value that has
   *     this form: a host name, an IPv4 address or an IPv6 address in brackets, and an optional
   *     port. A value from a different caller is not examined, so an implementation that puts
   *     the value into a URL or a command must examine it
   * @return the records in the order the DNS server returned them; never empty
   * @throws SQLException if the name has no SRV records or the lookup fails
   */
  List<SrvRecord> resolve(String srvName, String dnsServer) throws SQLException;
}
