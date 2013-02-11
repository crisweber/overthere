/**
 * Copyright (c) 2008, 2012, XebiaLabs B.V., All rights reserved.
 *
 *
 * Overthere is licensed under the terms of the GPLv2
 * <http://www.gnu.org/licenses/old-licenses/gpl-2.0.html>, like most XebiaLabs Libraries.
 * There are special exceptions to the terms and conditions of the GPLv2 as it is applied to
 * this software, see the FLOSS License Exception
 * <http://github.com/xebialabs/overthere/blob/master/LICENSE>.
 *
 * This program is free software; you can redistribute it and/or modify it under the terms
 * of the GNU General Public License as published by the Free Software Foundation; version 2
 * of the License.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY;
 * without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along with this
 * program; if not, write to the Free Software Foundation, Inc., 51 Franklin St, Fifth
 * Floor, Boston, MA 02110-1301  USA
 */
package com.xebialabs.overthere.ssh;

import com.google.common.io.Closeables;
import com.google.common.util.concurrent.Monitor;
import com.xebialabs.overthere.*;
import com.xebialabs.overthere.spi.AddressPortMapper;
import net.schmizz.sshj.SSHClient;
import net.schmizz.sshj.connection.ConnectionException;
import net.schmizz.sshj.connection.channel.direct.LocalPortForwarder;
import net.schmizz.sshj.connection.channel.direct.Session;
import net.schmizz.sshj.transport.TransportException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Closeable;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

import static com.google.common.base.Preconditions.checkState;
import static com.google.common.collect.Lists.newArrayList;
import static com.google.common.collect.Maps.newHashMap;
import static com.google.common.collect.Sets.newHashSet;
import static com.xebialabs.overthere.ssh.SshConnectionBuilder.PORT_ALLOCATION_RANGE_START;
import static com.xebialabs.overthere.ssh.SshConnectionBuilder.PORT_ALLOCATION_RANGE_START_DEFAULT;
import static java.lang.String.format;
import static java.net.InetSocketAddress.createUnresolved;

/**
 * A connection to a 'jump station' host using SSH w/ local port forwards.
 */
public class SshTunnelConnection extends SshConnection implements AddressPortMapper {

    private static final Monitor M = new Monitor();
    static final AtomicReference<TunnelPortManager> PORT_MANAGER = new AtomicReference<TunnelPortManager>(new TunnelPortManager());
    private static final int MAX_PORT = 65536;

    private Map<InetSocketAddress, InetSocketAddress> localPortForwards = newHashMap();

    private List<PortForwarder> portForwarders = newArrayList();

    private Integer startPortRange;

    public SshTunnelConnection(final String protocol, final ConnectionOptions options, AddressPortMapper mapper) {
        super(protocol, options, mapper);
        this.startPortRange = options.get(PORT_ALLOCATION_RANGE_START, PORT_ALLOCATION_RANGE_START_DEFAULT);
    }

    @Override
    protected void connect() {
        super.connect();
        checkState(sshClient != null, "Should have set an SSH client when connected");
    }

    @Override
    public void doClose() {
        logger.debug("Closing tunnel.");
        for (PortForwarder portForwarder : portForwarders) {
            Closeables.closeQuietly(portForwarder);
        }

        super.doClose();
    }

    @Override
    public InetSocketAddress map(InetSocketAddress address) {
        M.enter();
        try {
            if (localPortForwards.containsKey(address)) {
                return localPortForwards.get(address);
            }

            ServerSocket serverSocket = PORT_MANAGER.get().leaseNewPort(startPortRange);
            portForwarders.add(startForwarder(address, serverSocket));

            InetSocketAddress localAddress = createUnresolved("localhost", serverSocket.getLocalPort());
            localPortForwards.put(address, localAddress);
            return localAddress;
        } finally {
            M.leave();
        }
    }

    private PortForwarder startForwarder(InetSocketAddress remoteAddress, ServerSocket serverSocket) {
        PortForwarder forwarderThread = new PortForwarder(sshClient, remoteAddress, serverSocket);
        logger.info("Starting {}", forwarderThread.getName());
        forwarderThread.start();
        try {
            forwarderThread.latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return forwarderThread;
    }

    @Override
    public OverthereFile getFile(String hostPath) throws RuntimeIOException {
        throw new UnsupportedOperationException("Cannot get a file from the tunnel.");
    }

    @Override
    public OverthereProcess startProcess(CmdLine commandLine) {
        throw new UnsupportedOperationException("Cannot start a process on the tunnel.");
    }

    @Override
    protected CmdLine processCommandLine(CmdLine commandLine) {
        throw new UnsupportedOperationException("Cannot process a command line for the tunnel.");
    }

    @Override
    protected SshProcess createProcess(Session session, CmdLine commandLine) throws TransportException, ConnectionException {
        throw new UnsupportedOperationException("Cannot create a process in the tunnel.");
    }

    @Override
    public void setWorkingDirectory(OverthereFile workingDirectory) {
        throw new UnsupportedOperationException("Cannot set a working directory on the tunnel.");
    }

    @Override
    public OverthereFile getWorkingDirectory() {
        throw new UnsupportedOperationException("Cannot get a working directory from the tunnel.");
    }

    @Override
    public int execute(final OverthereExecutionOutputHandler stdoutHandler, final OverthereExecutionOutputHandler stderrHandler, final CmdLine commandLine) {
        throw new UnsupportedOperationException("Cannot execute a command on the tunnel.");
    }

    private static final Logger logger = LoggerFactory.getLogger(SshTunnelConnection.class);

    private static class PortForwarder extends Thread implements Closeable {
        private final SSHClient sshClient;
        private final InetSocketAddress remoteAddress;
        private final ServerSocket localSocket;
        private CountDownLatch latch = new CountDownLatch(1);

        public PortForwarder(SSHClient sshClient, InetSocketAddress remoteAddress, ServerSocket localSocket) {
            super(buildName(remoteAddress, localSocket.getLocalPort()));
            this.sshClient = sshClient;
            this.remoteAddress = remoteAddress;
            this.localSocket = localSocket;
        }

        private static String buildName(InetSocketAddress remoteAddress, Integer localPort) {
            return format("SSH local port forward thread [%d:%s]", localPort, remoteAddress.toString());
        }

        @Override
        public void run() {
            LocalPortForwarder.Parameters params = new LocalPortForwarder.Parameters("localhost", localSocket.getLocalPort(), remoteAddress.getHostName(),
                remoteAddress.getPort());
            LocalPortForwarder forwarder = sshClient.newLocalPortForwarder(params, localSocket);
            try {
                latch.countDown();
                forwarder.listen();
            } catch (IOException ignore) {
                // OK.
            }
        }

        @Override
        public void close() throws IOException {
            localSocket.close();
            PORT_MANAGER.get().returnPort(localSocket);
            try {
                this.join();
            } catch (InterruptedException e) {
                // OK.
            }
        }
    }

    static class TunnelPortManager {
        private static final Set<Integer> portsHandedOut = newHashSet();
        private static Monitor M = new Monitor();

        ServerSocket leaseNewPort(Integer startFrom) {
            M.enter();
            try {
                for (int port = startFrom; port < MAX_PORT; port++) {
                    if (isLeased(port)) {
                        continue;
                    }

                    ServerSocket socket = tryBind(port);
                    if (socket != null) {
                        portsHandedOut.add(port);
                        return socket;
                    }
                }
                throw new IllegalStateException(format("Could not find a single free port in the range [%s-%s]...", startFrom, MAX_PORT));
            } finally {
                M.leave();
            }
        }

        synchronized void returnPort(ServerSocket socket) {
            M.enter();
            try {
                portsHandedOut.remove(socket.getLocalPort());
            } finally {
                M.leave();
            }
        }

        private boolean isLeased(int port) {
            return portsHandedOut.contains(port);
        }

        protected ServerSocket tryBind(int localPort) {
            try {
                ServerSocket ss = new ServerSocket();
                ss.setReuseAddress(true);
                ss.bind(new InetSocketAddress("localhost", localPort));
                return ss;
            } catch (IOException e) {
                return null;
            }
        }
    }
}
