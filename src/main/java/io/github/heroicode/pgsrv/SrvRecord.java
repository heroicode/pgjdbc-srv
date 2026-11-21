package io.github.heroicode.pgsrv;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;

/** One DNS SRV record (RFC 2782). */
public final class SrvRecord {
  private final int priority;
  private final int weight;
  private final int port;
  private final String target;

  public SrvRecord(int priority, int weight, int port, String target) {
    this.priority = priority;
    this.weight = weight;
    this.port = port;
    this.target = target;
  }

  /**
   * Parses the record data in DNS presentation format: {@code "priority weight port target"}.
   * A trailing dot on the target is removed.
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
