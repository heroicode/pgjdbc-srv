package io.github.heroicode.pgsrv;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * One DNS SRV record (RFC 2782).
 *
 * <p>The values come from DNS, which is not a trusted source, and the target and port become part
 * of a JDBC URL. For that reason a record can only be made from a plain host name and from
 * numbers that are in the range of the DNS record format.
 */
public final class SrvRecord {
  private static final int MAX_UNSIGNED_16_BIT = 65535;
  private static final int MAX_HOST_NAME_LENGTH = 253;
  private static final String LABEL = "[A-Za-z0-9_]([A-Za-z0-9_-]{0,61}[A-Za-z0-9_])?";
  private static final Pattern HOST_NAME = Pattern.compile(LABEL + "(\\." + LABEL + ")*");

  private final int priority;
  private final int weight;
  private final int port;
  private final String target;

  /**
   * @param target a host name of letters, digits, hyphens, underscores and dots, or "." alone
   * @throws IllegalArgumentException if the target is not a plain host name or a number is out of
   *     range
   */
  public SrvRecord(int priority, int weight, int port, String target) {
    checkRange("priority", priority, 0);
    checkRange("weight", weight, 0);
    if (".".equals(target)) {
      checkRange("port", port, 0);
    } else {
      if (target == null
          || target.length() > MAX_HOST_NAME_LENGTH
          || !HOST_NAME.matcher(target).matches()) {
        throw new IllegalArgumentException("SRV target is not a valid host name: \"" + target + "\"");
      }
      checkRange("port", port, 1);
    }
    this.priority = priority;
    this.weight = weight;
    this.port = port;
    this.target = target;
  }

  private static void checkRange(String field, int value, int min) {
    if (value < min || value > MAX_UNSIGNED_16_BIT) {
      throw new IllegalArgumentException("SRV " + field + " is out of range: " + value);
    }
  }

  /**
   * Parses the record data in DNS presentation format: {@code "priority weight port target"}.
   * A trailing dot on the target is removed.
   *
   * @throws IllegalArgumentException if the data is malformed or the record is not valid
   */
  public static SrvRecord parse(String rdata) {
    String[] parts = rdata.trim().split("\\s+");
    if (parts.length != 4) {
      throw new IllegalArgumentException("Malformed SRV record: \"" + rdata + "\"");
    }
    try {
      int priority = Integer.parseInt(parts[0]);
      int weight = Integer.parseInt(parts[1]);
      int port = Integer.parseInt(parts[2]);
      String target = parts[3];
      if (target.length() > 1 && target.endsWith(".")) {
        target = target.substring(0, target.length() - 1);
      }
      return new SrvRecord(priority, weight, port, target);
    } catch (NumberFormatException e) {
      throw new IllegalArgumentException("Malformed SRV record: \"" + rdata + "\"", e);
    }
  }

  /**
   * Puts records in the order in which a client must try them (RFC 2782): lowest priority value
   * first, and a weighted random order among records that have the same priority.
   */
  public static List<SrvRecord> order(Collection<SrvRecord> records, Random random) {
    Map<Integer, List<SrvRecord>> byPriority = new TreeMap<Integer, List<SrvRecord>>();
    for (SrvRecord record : records) {
      List<SrvRecord> group = byPriority.get(record.priority);
      if (group == null) {
        group = new ArrayList<SrvRecord>();
        byPriority.put(record.priority, group);
      }
      group.add(record);
    }

    List<SrvRecord> ordered = new ArrayList<SrvRecord>(records.size());
    for (List<SrvRecord> pool : byPriority.values()) {
      // Zero-weight records go first so that they keep a small chance of selection.
      pool.sort(Comparator.comparingInt(SrvRecord::getWeight));
      while (!pool.isEmpty()) {
        int total = 0;
        for (SrvRecord record : pool) {
          total += record.weight;
        }
        int pick = random.nextInt(total + 1);
        int running = 0;
        for (int i = 0; i < pool.size(); i++) {
          running += pool.get(i).weight;
          if (running >= pick) {
            ordered.add(pool.remove(i));
            break;
          }
        }
      }
    }
    return ordered;
  }

  public int getPriority() {
    return priority;
  }

  public int getWeight() {
    return weight;
  }

  public int getPort() {
    return port;
  }

  public String getTarget() {
    return target;
  }

  /** A target of "." means that the service is explicitly not available at this domain. */
  boolean isUnavailableMarker() {
    return ".".equals(target);
  }

  @Override
  public String toString() {
    return priority + " " + weight + " " + port + " " + target;
  }
}
