package fr.themode.proxy;

import fr.themode.proxy.protocol.ClientHandler;
import fr.themode.proxy.protocol.ServerHandler;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class ProxyThread {

    private final Map<SocketChannel, Context> channelMap = new ConcurrentHashMap<>();
    private final Selector selector = Selector.open();

    private final ByteBuffer readBuffer = ByteBuffer.allocateDirect(Server.THREAD_READ_BUFFER);
    private final ByteBuffer writeBuffer = ByteBuffer.allocateDirect(Server.THREAD_WRITE_BUFFER);
    private final ByteBuffer contentBuffer = ByteBuffer.allocateDirect(Server.THREAD_CONTENT_BUFFER);

    public ProxyThread() throws IOException {
        Executors.newSingleThreadScheduledExecutor().scheduleAtFixedRate(() -> {
            try {
                threadTick();
            } catch (Exception e) {
                e.printStackTrace();
            }
        }, 0, Server.SELECTOR_TIMER, TimeUnit.MILLISECONDS);
    }

    private void threadTick() {
        try {
            selector.select();
        } catch (IOException e) {
            e.printStackTrace();
        }
        Set<SelectionKey> selectedKeys = selector.selectedKeys();
        Iterator<SelectionKey> iter = selectedKeys.iterator();
        while (iter.hasNext()) {
            SelectionKey key = iter.next();

            SocketChannel channel = (SocketChannel) key.channel();
            if (!channel.isOpen()) {
                iter.remove();
                continue;
            }

            if (key.isReadable()) {
                var context = channelMap.get(channel);
                var target = context.getTarget();
                try {

                    // Consume last incomplete packet
                    context.consumeCache(readBuffer);

                    // Read socket
                    while (readBuffer.position() < readBuffer.limit()) {
                        final int length = channel.read(readBuffer);
                        if (length == 0) {
                            // Nothing to read
                            break;
                        }
                        if (length == -1) {
                            // EOS
                            throw new IOException("Disconnected");
                        }
                    }

                    // Process data
                    readBuffer.flip();
                    context.processPackets(target, readBuffer, writeBuffer, contentBuffer);
                } catch (IOException e) {
                    e.printStackTrace();
                    try {
                        // Client close
                        channel.close();
                        target.close();
                        channelMap.remove(channel);
                        channelMap.remove(target);
                    } catch (IOException ioException) {
                        ioException.printStackTrace();
                    }
                } finally {
                    readBuffer.clear();
                    writeBuffer.clear();
                    contentBuffer.clear();
                }
            }
            iter.remove();
        }
    }

    public void receiveConnection(SocketChannel clientChannel, SocketChannel serverChannel) throws IOException {
        var clientContext = new Context(serverChannel, new ClientHandler());
        var serverContext = new Context(clientChannel, new ServerHandler());

        clientContext.targetContext = serverContext;
        serverContext.targetContext = clientContext;

        this.channelMap.put(clientChannel, clientContext);
        this.channelMap.put(serverChannel, serverContext);

        final int interest = SelectionKey.OP_READ;

        clientChannel.configureBlocking(false);
        clientChannel.register(selector, interest);

        serverChannel.configureBlocking(false);
        serverChannel.register(selector, interest);

        selector.wakeup();
    }

}
