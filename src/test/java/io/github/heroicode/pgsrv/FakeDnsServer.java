package io.github.heroicode.pgsrv;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Minimal UDP DNS server on the loopback interface that answers SRV queries. */
final class FakeDnsServer implements AutoCloseable {
  private static final int TYPE_SRV = 33;

  private final DatagramSocket socket;
  private final Map<String, List<SrvRecord>> zone = new ConcurrentHashMap<String, List<SrvRecord>>();
  private final Thread thread;

  FakeDnsServer() throws IOException {
    socket = new DatagramSocket(0, InetAddress.getLoopbackAddress());
    thread = new Thread(this::serve, "fake-dns");
    thread.setDaemon(true);
    thread.start();
  }

  /** Address in the form that the srvDnsServer parameter takes. */
  String address() {
    return "127.0.0.1:" + socket.getLocalPort();
  }

  void add(String name, SrvRecord record) {
    zone.computeIfAbsent(name.toLowerCase(), key -> new ArrayList<SrvRecord>()).add(record);
  }

  @Override
  public void close() {
    socket.close();
  }

  private void serve() {
    byte[] buffer = new byte[512];
    while (!socket.isClosed()) {
      try {
        DatagramPacket request = new DatagramPacket(buffer, buffer.length);
        socket.receive(request);
        byte[] response = answer(request.getData(), request.getLength());
        socket.send(
            new DatagramPacket(response, response.length, request.getSocketAddress()));
      } catch (IOException e) {
        // Socket closed, or a bad packet: keep going until closed.
      }
    }
  }

  private byte[] answer(byte[] query, int length) throws IOException {
    // Read the question name.
    StringBuilder name = new StringBuilder();
    int position = 12;
    while (query[position] != 0) {
      int labelLength = query[position] & 0xff;
      if (name.length() > 0) {
        name.append('.');
      }
      name.append(new String(query, position + 1, labelLength, StandardCharsets.US_ASCII));
      position += labelLength + 1;
    }
    position++;
    int type = ((query[position] & 0xff) << 8) | (query[position + 1] & 0xff);
    int questionEnd = position + 4;

    List<SrvRecord> records = zone.get(name.toString().toLowerCase());
    boolean nameExists = records != null;
    if (records == null || type != TYPE_SRV) {
      records = new ArrayList<SrvRecord>();
    }

    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    DataOutputStream out = new DataOutputStream(bytes);
    out.write(query, 0, 2); // id
    out.writeShort(0x8580 | (nameExists ? 0 : 3)); // response, authoritative; NXDOMAIN if unknown
    out.writeShort(1); // questions
    out.writeShort(records.size()); // answers
    out.writeShort(0);
    out.writeShort(0);
    out.write(query, 12, questionEnd - 12);
    for (SrvRecord record : records) {
      byte[] target = encodeName(record.getTarget());
      out.writeShort(0xC00C); // pointer to the question name
      out.writeShort(TYPE_SRV);
      out.writeShort(1); // class IN
      out.writeInt(60); // TTL
      out.writeShort(6 + target.length);
      out.writeShort(record.getPriority());
      out.writeShort(record.getWeight());
      out.writeShort(record.getPort());
      out.write(target);
    }
    return bytes.toByteArray();
  }

  private static byte[] encodeName(String name) {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    if (!".".equals(name)) {
      for (String label : name.split("\\.")) {
        byte[] labelBytes = label.getBytes(StandardCharsets.US_ASCII);
        out.write(labelBytes.length);
        out.write(labelBytes, 0, labelBytes.length);
      }
    }
    out.write(0);
    return out.toByteArray();
  }
}
