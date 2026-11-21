package io.github.heroicode.pgsrv;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Hashtable;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.naming.Context;
import javax.naming.NamingEnumeration;
import javax.naming.NamingException;
import javax.naming.directory.Attribute;
import javax.naming.directory.Attributes;
import javax.naming.directory.DirContext;
import javax.naming.directory.InitialDirContext;

/** Resolves SRV records with the DNS provider that is built into the JDK (JNDI). */
@SuppressWarnings("doclint:missing")
public final class DnsSrvResolver implements SrvResolver {
  /** SQLSTATE: the client is unable to establish the connection. */
  static final String CONNECTION_UNABLE_TO_CONNECT = "08001";

  /** A host name, an IPv4 address or an IPv6 address in brackets, and an optional port. */
  private static final Pattern DNS_SERVER =
      Pattern.compile("([A-Za-z0-9_.-]+|\\[[0-9A-Fa-f:.]+\\])(?::([0-9]{1,5}))?");

  /** True if the value is {@code host} or {@code host:port} and nothing more. */
  static boolean isDnsServer(String dnsServer) {
    Matcher matcher = DNS_SERVER.matcher(dnsServer);
    if (!matcher.matches()) {
      return false;
    }
    String host = matcher.group(1);
    if (!host.startsWith("[") && !SrvRecord.isHostName(host)) {
      return false;
    }
    String port = matcher.group(2);
    return port == null || (Integer.parseInt(port) >= 1 && Integer.parseInt(port) <= 65535);
  }

  @Override
  public List<SrvRecord> resolve(String srvName, String dnsServer) throws SQLException {
    // The value becomes part of the JNDI provider URL, where a "/" would start a base domain.
    if (dnsServer != null && !isDnsServer(dnsServer)) {
      throw new SQLException(
          "srvDnsServer must be host or host:port", CONNECTION_UNABLE_TO_CONNECT);
    }
    Hashtable<String, String> env = new Hashtable<String, String>();
    env.put(Context.INITIAL_CONTEXT_FACTORY, "com.sun.jndi.dns.DnsContextFactory");
    env.put(Context.PROVIDER_URL, dnsServer == null ? "dns:" : "dns://" + dnsServer);
    env.put("com.sun.jndi.dns.timeout.initial", "2000");
    env.put("com.sun.jndi.dns.timeout.retries", "2");

    List<SrvRecord> records = new ArrayList<SrvRecord>();
    try {
      DirContext context = new InitialDirContext(env);
      try {
        Attributes attributes = context.getAttributes(srvName, new String[] {"SRV"});
        Attribute srv = attributes.get("SRV");
        if (srv != null) {
          NamingEnumeration<?> values = srv.getAll();
          while (values.hasMore()) {
            records.add(SrvRecord.parse(String.valueOf(values.next())));
          }
        }
      } finally {
        context.close();
      }
    } catch (NamingException | IllegalArgumentException e) {
      throw new SQLException(
          "DNS SRV lookup of " + srvName + " failed: " + e.getMessage(),
          CONNECTION_UNABLE_TO_CONNECT,
          e);
    }
    if (records.isEmpty()) {
      throw new SQLException(
          "DNS name " + srvName + " has no SRV records", CONNECTION_UNABLE_TO_CONNECT);
    }
    return records;
  }
}
