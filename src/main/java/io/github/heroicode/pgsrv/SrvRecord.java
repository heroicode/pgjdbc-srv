package io.github.heroicode.pgsrv;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
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
@SuppressWarnings("doclint:missing")
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
      if (!isHostName(target)) {
        throw new IllegalArgumentException("SRV target is not a valid host name: \"" + target + "\"");
      }
      checkRange("port", port, 1);
    }
    this.priority = priority;
    this.weight = weight;
    this.port = port;
    this.target = target;
  }

  /** True for dot-separated labels of letters, digits, hyphens and underscores. */
  static boolean isHostName(String name) {
    return name != null
        && name.length() <= MAX_HOST_NAME_LENGTH
        && HOST_NAME.matcher(name).matches();
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
   * first, and a random order among records that have the same priority.
   *
   * <p>Among records of the same priority, the chance that a record is the next one is in
   * proportion to its weight. Records of weight 0 have an equal chance when all the weights are
   * 0. When some weights are larger, the records of weight 0 together have one chance in (sum of
   * the weights + 1) to be the next one.
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
      // RFC 2782 puts the records of weight 0 first, in any order. The order must be random:
      // the selection below takes the first of them, so a fixed order would always prefer the
      // record that the DNS server gave first. The sort is stable and keeps the random order.
      Collections.shuffle(pool, random);
      pool.sort(Comparator.comparingInt(SrvRecord::getWeight));
      // The sum of the weights can be larger than an int holds.
      long total = 0;
      for (SrvRecord record : pool) {
        total += record.weight;
      }
      while (!pool.isEmpty()) {
        // A number from 0 to the sum selects the first record whose running sum is not less
        // than the number. The number 0 can only select a record of weight 0. Thus, when there
        // is no such record, the number starts at 1. If it started at 0, the first record
        // would get one more chance than its weight gives.
        long lowest = pool.get(0).weight == 0 ? 0 : 1;
        long pick = lowest + (long) (random.nextDouble() * (total - lowest + 1));
        pick = Math.min(pick, total);
        long running = 0;
        for (int i = 0; i < pool.size(); i++) {
          running += pool.get(i).weight;
          if (running >= pick) {
            total -= pool.get(i).weight;
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

  /** Records are equal if all four fields are equal. The case of the target is ignored. */
  @Override
  public boolean equals(Object other) {
    if (this == other) {
      return true;
    }
    if (!(other instanceof SrvRecord)) {
      return false;
    }
    SrvRecord that = (SrvRecord) other;
    return priority == that.priority
        && weight == that.weight
        && port == that.port
        && target.equalsIgnoreCase(that.target);
  }

  @Override
  public int hashCode() {
    int result = priority;
    result = 31 * result + weight;
    result = 31 * result + port;
    result = 31 * result + target.toLowerCase(Locale.ROOT).hashCode();
    return result;
  }

  @Override
  public String toString() {
    return priority + " " + weight + " " + port + " " + target;
  }
}
