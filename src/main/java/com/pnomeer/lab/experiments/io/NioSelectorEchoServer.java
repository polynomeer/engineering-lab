package com.pnomeer.lab.experiments.io;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.Channel;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.time.LocalTime;
import java.util.ArrayDeque;
import java.util.Iterator;
import java.util.Queue;

public final class NioSelectorEchoServer {
    private static final int DEFAULT_PORT = 7033;
    private static final int BUFFER_SIZE = 1024;

    private NioSelectorEchoServer() {
    }

    public static void main(String[] args) {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : DEFAULT_PORT;
        System.out.println("[NioSelectorEchoServer] starting on port " + port);

        try (Selector selector = Selector.open();
             ServerSocketChannel serverChannel = ServerSocketChannel.open()) {

            serverChannel.bind(new InetSocketAddress(port));
            serverChannel.configureBlocking(false);
            serverChannel.register(selector, SelectionKey.OP_ACCEPT);

            while (true) {
                selector.select();

                Iterator<SelectionKey> iterator = selector.selectedKeys().iterator();
                while (iterator.hasNext()) {
                    SelectionKey key = iterator.next();
                    iterator.remove();

                    try {
                        if (!key.isValid()) {
                            continue;
                        }
                        if (key.isAcceptable()) {
                            accept(selector, serverChannel);
                        } else if (key.isReadable()) {
                            read(key);
                        } else if (key.isWritable()) {
                            write(key);
                        }
                    } catch (Exception e) {
                        closeKey(key, "exception: " + e.getMessage());
                    }
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private static void accept(Selector selector, ServerSocketChannel serverChannel) throws IOException {
        SocketChannel client = serverChannel.accept();
        if (client == null) {
            return;
        }

        client.configureBlocking(false);
        client.register(selector, SelectionKey.OP_READ, new ClientState());
        System.out.printf("[%s] accepted: %s%n", LocalTime.now(), client.getRemoteAddress());
    }

    private static void read(SelectionKey key) throws IOException {
        SocketChannel channel = (SocketChannel) key.channel();
        ClientState state = (ClientState) key.attachment();

        ByteBuffer readBuffer = ByteBuffer.allocate(BUFFER_SIZE);
        int readBytes = channel.read(readBuffer);
        if (readBytes == -1) {
            closeKey(key, "client closed");
            return;
        }
        if (readBytes == 0) {
            return;
        }

        readBuffer.flip();
        state.incoming.append(StandardCharsets.UTF_8.decode(readBuffer));

        while (true) {
            int newlineIndex = state.incoming.indexOf("\n");
            if (newlineIndex < 0) {
                break;
            }

            String line = state.incoming.substring(0, newlineIndex).trim();
            state.incoming.delete(0, newlineIndex + 1);

            String response = "[nio] echo: " + line + "\n";
            state.outgoing.add(ByteBuffer.wrap(response.getBytes(StandardCharsets.UTF_8)));
        }

        if (!state.outgoing.isEmpty()) {
            key.interestOps(SelectionKey.OP_READ | SelectionKey.OP_WRITE);
        }
    }

    private static void write(SelectionKey key) throws IOException {
        SocketChannel channel = (SocketChannel) key.channel();
        ClientState state = (ClientState) key.attachment();

        while (!state.outgoing.isEmpty()) {
            ByteBuffer buffer = state.outgoing.peek();
            channel.write(buffer);
            if (buffer.hasRemaining()) {
                break;
            }
            state.outgoing.poll();
        }

        if (state.outgoing.isEmpty()) {
            key.interestOps(SelectionKey.OP_READ);
        }
    }

    private static void closeKey(SelectionKey key, String reason) {
        try {
            Channel channel = key.channel();
            System.out.printf("[%s] close: %s, reason=%s%n", LocalTime.now(), channel, reason);
            key.cancel();
            channel.close();
        } catch (IOException ignored) {
        }
    }

    private static final class ClientState {
        private final StringBuilder incoming = new StringBuilder();
        private final Queue<ByteBuffer> outgoing = new ArrayDeque<>();
    }
}
