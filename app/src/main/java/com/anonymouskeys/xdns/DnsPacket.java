package com.anonymouskeys.xdns;

import java.net.InetAddress;
import java.util.Arrays;
import java.util.Set;
import java.util.List;
import java.util.LinkedHashSet;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicInteger;

public final class DnsPacket {

    private static final AtomicInteger IP_ID = new AtomicInteger(1);

    private DnsPacket() {}

    public static final class Request {
        public final byte[] sourceIp;
        public final byte[] destinationIp;
        public final int sourcePort;
        public final int destinationPort;
        public final byte[] dns;

        Request(byte[] sourceIp, byte[] destinationIp,
                int sourcePort, int destinationPort, byte[] dns) {
            this.sourceIp = sourceIp;
            this.destinationIp = destinationIp;
            this.sourcePort = sourcePort;
            this.destinationPort = destinationPort;
            this.dns = dns;
        }
    }

    public static Request parseIpv4UdpDns(byte[] packet, int length) {
        if (length < 28) return null;

        int version = (packet[0] >>> 4) & 0x0f;
        if (version != 4) return null;

        int ihl = (packet[0] & 0x0f) * 4;
        if (ihl < 20 || length < ihl + 8) return null;

        int protocol = packet[9] & 0xff;
        if (protocol != 17) return null; // UDP

        int frag = read16(packet, 6);
        if ((frag & 0x1fff) != 0) return null;

        int totalLength = read16(packet, 2);
        if (totalLength <= 0 || totalLength > length) totalLength = length;

        int udp = ihl;
        int srcPort = read16(packet, udp);
        int dstPort = read16(packet, udp + 2);
        if (dstPort != 53) return null;

        int udpLength = read16(packet, udp + 4);
        if (udpLength < 8) return null;

        int dnsOffset = udp + 8;
        int dnsLength = Math.min(udpLength - 8, totalLength - dnsOffset);
        if (dnsLength < 12 || dnsOffset + dnsLength > length) return null;

        return new Request(
                Arrays.copyOfRange(packet, 12, 16),
                Arrays.copyOfRange(packet, 16, 20),
                srcPort,
                dstPort,
                Arrays.copyOfRange(packet, dnsOffset, dnsOffset + dnsLength)
        );
    }

    public static byte[] buildIpv4UdpResponse(Request request, byte[] dnsResponse) {
        int total = 20 + 8 + dnsResponse.length;
        if (total > 65535) throw new IllegalArgumentException("DNS response too large");

        byte[] packet = new byte[total];

        packet[0] = 0x45;
        packet[1] = 0;
        write16(packet, 2, total);
        write16(packet, 4, IP_ID.getAndIncrement() & 0xffff);
        write16(packet, 6, 0x4000); // DF
        packet[8] = 64;
        packet[9] = 17; // UDP

        System.arraycopy(request.destinationIp, 0, packet, 12, 4);
        System.arraycopy(request.sourceIp, 0, packet, 16, 4);

        write16(packet, 10, 0);
        write16(packet, 10, checksum(packet, 0, 20));

        int udp = 20;
        write16(packet, udp, request.destinationPort);
        write16(packet, udp + 2, request.sourcePort);
        write16(packet, udp + 4, 8 + dnsResponse.length);
        write16(packet, udp + 6, 0); // Valid for IPv4 UDP.

        System.arraycopy(dnsResponse, 0, packet, udp + 8, dnsResponse.length);
        return packet;
    }

    public static byte[] makeServFail(byte[] query) {
        int questionEnd = questionEnd(query);
        int len = questionEnd > 12 ? questionEnd : query.length;
        byte[] response = Arrays.copyOf(query, len);

        if (response.length < 12) return response;

        int flags = read16(response, 2);
        flags |= 0x8000; // QR
        flags |= 0x0080; // RA
        flags = (flags & 0xfff0) | 0x0002; // SERVFAIL
        write16(response, 2, flags);

        write16(response, 6, 0);
        write16(response, 8, 0);
        write16(response, 10, 0);
        return response;
    }

    public static String queryName(byte[] dns) {
        if (dns == null || dns.length < 13) return "?";
        int offset = 12;
        StringBuilder name = new StringBuilder();

        try {
            while (offset < dns.length) {
                int len = dns[offset++] & 0xff;
                if (len == 0) break;
                if ((len & 0xc0) != 0 || offset + len > dns.length) return "?";
                if (name.length() > 0) name.append('.');
                name.append(new String(
                        dns, offset, len,
                        java.nio.charset.StandardCharsets.US_ASCII
                ));
                offset += len;
            }
            return name.length() == 0 ? "?" : name.toString();
        } catch (Exception e) {
            return "?";
        }
    }

    public static String queryType(byte[] dns) {
        int offset = questionNameEnd(dns);
        if (offset < 0 || offset + 4 > dns.length) return "?";
        int type = read16(dns, offset);

        switch (type) {
            case 1: return "A";
            case 28: return "AAAA";
            case 5: return "CNAME";
            case 12: return "PTR";
            case 15: return "MX";
            case 16: return "TXT";
            case 33: return "SRV";
            case 65: return "HTTPS";
            default: return String.valueOf(type);
        }
    }

    public static List<String> allIpv4Addresses(byte[] dns) {
        Set<String> unique = new LinkedHashSet<>();

        try {
            if (dns == null || dns.length < 12) {
                return new ArrayList<>();
            }

            int answerCount = read16(dns, 6);
            if (answerCount <= 0) {
                return new ArrayList<>();
            }

            int offset = questionEnd(dns);
            if (offset < 0) {
                return new ArrayList<>();
            }

            for (int i = 0; i < answerCount && offset < dns.length; i++) {
                offset = skipName(dns, offset);

                if (offset < 0 || offset + 10 > dns.length) {
                    break;
                }

                int type = read16(dns, offset);
                int rdLength = read16(dns, offset + 8);
                int rdata = offset + 10;

                if (rdata + rdLength > dns.length) {
                    break;
                }

                if (type == 1 && rdLength == 4) {
                    InetAddress inet =
                            InetAddress.getByAddress(
                                    Arrays.copyOfRange(
                                            dns,
                                            rdata,
                                            rdata + 4
                                    )
                            );

                    if (!inet.isAnyLocalAddress()
                            && !inet.isLoopbackAddress()
                            && !inet.isLinkLocalAddress()
                            && !inet.isMulticastAddress()) {

                        String ip =
                                inet.getHostAddress();

                        unique.add(ip);
                    }
                }

                offset = rdata + rdLength;
            }

        } catch (Exception ignored) {
        }

        return new ArrayList<>(unique);
    }

    public static String firstAddress(byte[] dns) {
        try {
            if (dns == null || dns.length < 12) return "-";
            int answerCount = read16(dns, 6);
            if (answerCount <= 0) return "-";

            int offset = questionEnd(dns);
            if (offset < 0) return "-";

            for (int i = 0; i < answerCount && offset < dns.length; i++) {
                offset = skipName(dns, offset);
                if (offset < 0 || offset + 10 > dns.length) return "-";

                int type = read16(dns, offset);
                int rdLength = read16(dns, offset + 8);
                int rdata = offset + 10;

                if (rdata + rdLength > dns.length) return "-";

                if (type == 1 && rdLength == 4) {
                    return InetAddress.getByAddress(
                            Arrays.copyOfRange(dns, rdata, rdata + 4)
                    ).getHostAddress();
                }

                if (type == 28 && rdLength == 16) {
                    return InetAddress.getByAddress(
                            Arrays.copyOfRange(dns, rdata, rdata + 16)
                    ).getHostAddress();
                }

                offset = rdata + rdLength;
            }
        } catch (Exception ignored) {
        }

        return "-";
    }

    private static int questionEnd(byte[] dns) {
        int nameEnd = questionNameEnd(dns);
        if (nameEnd < 0 || nameEnd + 4 > dns.length) return -1;
        return nameEnd + 4;
    }

    private static int questionNameEnd(byte[] dns) {
        if (dns == null || dns.length < 13) return -1;
        int offset = 12;

        while (offset < dns.length) {
            int len = dns[offset++] & 0xff;
            if (len == 0) return offset;

            if ((len & 0xc0) == 0xc0) {
                if (offset >= dns.length) return -1;
                return offset + 1;
            }

            if (offset + len > dns.length) return -1;
            offset += len;
        }

        return -1;
    }

    private static int skipName(byte[] dns, int offset) {
        while (offset < dns.length) {
            int len = dns[offset] & 0xff;

            if (len == 0) return offset + 1;

            if ((len & 0xc0) == 0xc0) {
                return offset + 2 <= dns.length ? offset + 2 : -1;
            }

            offset++;
            if (offset + len > dns.length) return -1;
            offset += len;
        }
        return -1;
    }

    private static int checksum(byte[] data, int offset, int length) {
        long sum = 0;
        int end = offset + length;

        for (int i = offset; i < end; i += 2) {
            int high = data[i] & 0xff;
            int low = i + 1 < end ? data[i + 1] & 0xff : 0;
            sum += (high << 8) | low;
            while ((sum & 0xffff0000L) != 0) {
                sum = (sum & 0xffff) + (sum >>> 16);
            }
        }

        return (int) (~sum) & 0xffff;
    }

    private static int read16(byte[] data, int offset) {
        return ((data[offset] & 0xff) << 8) | (data[offset + 1] & 0xff);
    }

    private static void write16(byte[] data, int offset, int value) {
        data[offset] = (byte) ((value >>> 8) & 0xff);
        data[offset + 1] = (byte) (value & 0xff);
    }
}
