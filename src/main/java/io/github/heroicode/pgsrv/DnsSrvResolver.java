package io.github.heroicode.pgsrv;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Hashtable;
import java.util.List;

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

  @Override
  public List<SrvRecord> resolve(String srvName, String dnsServer) throws SQLException {
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
