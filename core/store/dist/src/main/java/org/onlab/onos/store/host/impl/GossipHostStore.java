/*
 * Copyright 2014 Open Networking Laboratory
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.onlab.onos.store.host.impl;

import static java.util.concurrent.Executors.newSingleThreadScheduledExecutor;
import static org.onlab.onos.cluster.ControllerNodeToNodeId.toNodeId;
import static org.onlab.onos.net.DefaultAnnotations.merge;
import static org.onlab.onos.net.host.HostEvent.Type.HOST_ADDED;
import static org.onlab.onos.net.host.HostEvent.Type.HOST_MOVED;
import static org.onlab.onos.net.host.HostEvent.Type.HOST_REMOVED;
import static org.onlab.onos.net.host.HostEvent.Type.HOST_UPDATED;
import static org.onlab.util.Tools.namedThreads;
import static org.onlab.util.Tools.minPriority;
import static org.slf4j.LoggerFactory.getLogger;

import java.io.IOException;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.apache.commons.lang3.RandomUtils;
import org.apache.felix.scr.annotations.Activate;
import org.apache.felix.scr.annotations.Component;
import org.apache.felix.scr.annotations.Deactivate;
import org.apache.felix.scr.annotations.Reference;
import org.apache.felix.scr.annotations.ReferenceCardinality;
import org.apache.felix.scr.annotations.Service;
import org.onlab.onos.cluster.ClusterService;
import org.onlab.onos.cluster.ControllerNode;
import org.onlab.onos.cluster.NodeId;
import org.onlab.onos.net.Annotations;
import org.onlab.onos.net.ConnectPoint;
import org.onlab.onos.net.DefaultAnnotations;
import org.onlab.onos.net.DefaultHost;
import org.onlab.onos.net.DeviceId;
import org.onlab.onos.net.Host;
import org.onlab.onos.net.HostId;
import org.onlab.onos.net.HostLocation;
import org.onlab.onos.net.host.DefaultHostDescription;
import org.onlab.onos.net.host.HostClockService;
import org.onlab.onos.net.host.HostDescription;
import org.onlab.onos.net.host.HostEvent;
import org.onlab.onos.net.host.HostStore;
import org.onlab.onos.net.host.HostStoreDelegate;
import org.onlab.onos.net.host.PortAddresses;
import org.onlab.onos.net.provider.ProviderId;
import org.onlab.onos.store.AbstractStore;
import org.onlab.onos.store.Timestamp;
import org.onlab.onos.store.cluster.messaging.ClusterCommunicationService;
import org.onlab.onos.store.cluster.messaging.ClusterMessage;
import org.onlab.onos.store.cluster.messaging.ClusterMessageHandler;
import org.onlab.onos.store.cluster.messaging.MessageSubject;
import org.onlab.onos.store.impl.Timestamped;
import org.onlab.onos.store.serializers.KryoSerializer;
import org.onlab.onos.store.serializers.impl.DistributedStoreSerializers;
import org.onlab.packet.IpAddress;
import org.onlab.packet.MacAddress;
import org.onlab.packet.VlanId;
import org.onlab.util.KryoNamespace;
import org.slf4j.Logger;

import com.google.common.collect.FluentIterable;
import com.google.common.collect.HashMultimap;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Multimap;
import com.google.common.collect.Multimaps;
import com.google.common.collect.SetMultimap;

//TODO: multi-provider, annotation not supported.
/**
 * Manages inventory of end-station hosts in distributed data store
 * that uses optimistic replication and gossip based techniques.
 */
@Component(immediate = true)
@Service
public class GossipHostStore
        extends AbstractStore<HostEvent, HostStoreDelegate>
        implements HostStore {

    private final Logger log = getLogger(getClass());

    // TODO: make this configurable
    private int hostsExpected = 2000000;

    // Host inventory
    private final Map<HostId, StoredHost> hosts = new ConcurrentHashMap<>(hostsExpected, 0.75f, 16);

    private final Map<HostId, Timestamped<Host>> removedHosts = new ConcurrentHashMap<>(hostsExpected, 0.75f, 16);

    // Hosts tracked by their location
    private final Multimap<ConnectPoint, Host> locations = HashMultimap.create();

    private final SetMultimap<ConnectPoint, PortAddresses> portAddresses =
            Multimaps.synchronizedSetMultimap(
                    HashMultimap.<ConnectPoint, PortAddresses>create());

    @Reference(cardinality = ReferenceCardinality.MANDATORY_UNARY)
    protected HostClockService hostClockService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY_UNARY)
    protected ClusterCommunicationService clusterCommunicator;

    @Reference(cardinality = ReferenceCardinality.MANDATORY_UNARY)
    protected ClusterService clusterService;

    private static final KryoSerializer SERIALIZER = new KryoSerializer() {
        @Override
        protected void setupKryoPool() {
            serializerPool = KryoNamespace.newBuilder()
                    .register(DistributedStoreSerializers.STORE_COMMON)
                    .nextId(DistributedStoreSerializers.STORE_CUSTOM_BEGIN)
                    .register(InternalHostEvent.class)
                    .register(InternalHostRemovedEvent.class)
                    .register(HostFragmentId.class)
                    .register(HostAntiEntropyAdvertisement.class)
                    .build();
        }
    };

    private ExecutorService executor;

    private ScheduledExecutorService backgroundExecutor;

    @Activate
    public void activate() {
        clusterCommunicator.addSubscriber(
                GossipHostStoreMessageSubjects.HOST_UPDATED,
                new InternalHostEventListener());
        clusterCommunicator.addSubscriber(
                GossipHostStoreMessageSubjects.HOST_REMOVED,
                new InternalHostRemovedEventListener());
        clusterCommunicator.addSubscriber(
                GossipHostStoreMessageSubjects.HOST_ANTI_ENTROPY_ADVERTISEMENT,
                new InternalHostAntiEntropyAdvertisementListener());

        executor = Executors.newCachedThreadPool(namedThreads("host-fg-%d"));

        backgroundExecutor =
                newSingleThreadScheduledExecutor(minPriority(namedThreads("host-bg-%d")));

        // TODO: Make these configurable
        long initialDelaySec = 5;
        long periodSec = 5;
        // start anti-entropy thread
        backgroundExecutor.scheduleAtFixedRate(new SendAdvertisementTask(),
                    initialDelaySec, periodSec, TimeUnit.SECONDS);

        log.info("Started");
    }

    @Deactivate
    public void deactivate() {
        executor.shutdownNow();
        backgroundExecutor.shutdownNow();
        try {
            if (!backgroundExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                log.error("Timeout during executor shutdown");
            }
        } catch (InterruptedException e) {
            log.error("Error during executor shutdown", e);
        }

        hosts.clear();
        removedHosts.clear();
        locations.clear();
        portAddresses.clear();

        log.info("Stopped");
    }

    @Override
    public HostEvent createOrUpdateHost(ProviderId providerId, HostId hostId,
                                        HostDescription hostDescription) {
        Timestamp timestamp = hostClockService.getTimestamp(hostId);
        HostEvent event = createOrUpdateHostInternal(providerId, hostId, hostDescription, timestamp);
        if (event != null) {
            log.debug("Notifying peers of a host topology event for providerId: "
                    + "{}; hostId: {}; hostDescription: {}", providerId, hostId, hostDescription);
            try {
                notifyPeers(new InternalHostEvent(providerId, hostId, hostDescription, timestamp));
            } catch (IOException e) {
                log.error("Failed to notify peers of a host topology event for providerId: "
                        + "{}; hostId: {}; hostDescription: {}", providerId, hostId, hostDescription);
            }
        }
        return event;
    }

    private HostEvent createOrUpdateHostInternal(ProviderId providerId, HostId hostId,
                                        HostDescription hostDescription, Timestamp timestamp) {
        StoredHost host = hosts.get(hostId);
        if (host == null) {
            return createHost(providerId, hostId, hostDescription, timestamp);
        }
        return updateHost(providerId, host, hostDescription, timestamp);
    }

    // creates a new host and sends HOST_ADDED
    private HostEvent createHost(ProviderId providerId, HostId hostId,
                                 HostDescription descr, Timestamp timestamp) {
        synchronized (this) {
            // If this host was previously removed, first ensure
            // this new request is "newer"
            if (removedHosts.containsKey(hostId)) {
                if (removedHosts.get(hostId).isNewer(timestamp)) {
                    return null;
                } else {
                    removedHosts.remove(hostId);
                }
            }
            StoredHost newhost = new StoredHost(providerId, hostId,
                    descr.hwAddress(),
                    descr.vlan(),
                    new Timestamped<>(descr.location(), timestamp),
                    ImmutableSet.copyOf(descr.ipAddress()));
            hosts.put(hostId, newhost);
            locations.put(descr.location(), newhost);
            return new HostEvent(HOST_ADDED, newhost);
        }
    }

    // checks for type of update to host, sends appropriate event
    private HostEvent updateHost(ProviderId providerId, StoredHost host,
                                 HostDescription descr, Timestamp timestamp) {
        HostEvent event;
        if (!host.location.isNewer(timestamp) && !host.location().equals(descr.location())) {
            host.setLocation(new Timestamped<>(descr.location(), timestamp));
            return new HostEvent(HOST_MOVED, host);
        }

        if (host.ipAddresses().containsAll(descr.ipAddress()) &&
                descr.annotations().keys().isEmpty()) {
            return null;
        }

        Set<IpAddress> addresses = new HashSet<>(host.ipAddresses());
        addresses.addAll(descr.ipAddress());
        Annotations annotations = merge((DefaultAnnotations) host.annotations(),
                                        descr.annotations());
        StoredHost updated = new StoredHost(providerId, host.id(),
                                            host.mac(), host.vlan(),
                                            host.location, addresses,
                                            annotations);
        event = new HostEvent(HOST_UPDATED, updated);
        synchronized (this) {
            hosts.put(host.id(), updated);
            locations.remove(host.location(), host);
            locations.put(updated.location(), updated);
        }
        return event;
    }

    @Override
    public HostEvent removeHost(HostId hostId) {
        Timestamp timestamp = hostClockService.getTimestamp(hostId);
        HostEvent event = removeHostInternal(hostId, timestamp);
        if (event != null) {
            log.debug("Notifying peers of a host removed topology event for hostId: {}", hostId);
            try {
                notifyPeers(new InternalHostRemovedEvent(hostId, timestamp));
            } catch (IOException e) {
                log.info("Failed to notify peers of a host removed topology event for hostId: {}", hostId);
            }
        }
        return event;
    }

    private HostEvent removeHostInternal(HostId hostId, Timestamp timestamp) {
        synchronized (this) {
            Host host = hosts.remove(hostId);
            if (host != null) {
                locations.remove((host.location()), host);
                removedHosts.put(hostId, new Timestamped<>(host, timestamp));
                return new HostEvent(HOST_REMOVED, host);
            }
            return null;
        }
    }

    @Override
    public int getHostCount() {
        return hosts.size();
    }

    @Override
    public Iterable<Host> getHosts() {
        return ImmutableSet.<Host>copyOf(hosts.values());
    }

    @Override
    public Host getHost(HostId hostId) {
        return hosts.get(hostId);
    }

    @Override
    public Set<Host> getHosts(VlanId vlanId) {
        Set<Host> vlanset = new HashSet<>();
        for (Host h : hosts.values()) {
            if (h.vlan().equals(vlanId)) {
                vlanset.add(h);
            }
        }
        return vlanset;
    }

    @Override
    public Set<Host> getHosts(MacAddress mac) {
        Set<Host> macset = new HashSet<>();
        for (Host h : hosts.values()) {
            if (h.mac().equals(mac)) {
                macset.add(h);
            }
        }
        return macset;
    }

    @Override
    public Set<Host> getHosts(IpAddress ip) {
        Set<Host> ipset = new HashSet<>();
        for (Host h : hosts.values()) {
            if (h.ipAddresses().contains(ip)) {
                ipset.add(h);
            }
        }
        return ipset;
    }

    @Override
    public Set<Host> getConnectedHosts(ConnectPoint connectPoint) {
        return ImmutableSet.copyOf(locations.get(connectPoint));
    }

    @Override
    public Set<Host> getConnectedHosts(DeviceId deviceId) {
        Set<Host> hostset = new HashSet<>();
        for (ConnectPoint p : locations.keySet()) {
            if (p.deviceId().equals(deviceId)) {
                hostset.addAll(locations.get(p));
            }
        }
        return hostset;
    }

    @Override
    public void updateAddressBindings(PortAddresses addresses) {
        portAddresses.put(addresses.connectPoint(), addresses);
    }

    @Override
    public void removeAddressBindings(PortAddresses addresses) {
        portAddresses.remove(addresses.connectPoint(), addresses);
    }

    @Override
    public void clearAddressBindings(ConnectPoint connectPoint) {
        portAddresses.removeAll(connectPoint);
    }

    @Override
    public Set<PortAddresses> getAddressBindings() {
        synchronized (portAddresses) {
            return ImmutableSet.copyOf(portAddresses.values());
        }
    }

    @Override
    public Set<PortAddresses> getAddressBindingsForPort(ConnectPoint connectPoint) {
        synchronized (portAddresses) {
            Set<PortAddresses> addresses = portAddresses.get(connectPoint);

            if (addresses == null) {
                return Collections.emptySet();
            } else {
                return ImmutableSet.copyOf(addresses);
            }
        }
    }

    // Auxiliary extension to allow location to mutate.
    private static final class StoredHost extends DefaultHost {
        private Timestamped<HostLocation> location;

        /**
         * Creates an end-station host using the supplied information.
         *
         * @param providerId  provider identity
         * @param id          host identifier
         * @param mac         host MAC address
         * @param vlan        host VLAN identifier
         * @param location    host location
         * @param ips         host IP addresses
         * @param annotations optional key/value annotations
         */
        public StoredHost(ProviderId providerId, HostId id,
                          MacAddress mac, VlanId vlan, Timestamped<HostLocation> location,
                          Set<IpAddress> ips, Annotations... annotations) {
            super(providerId, id, mac, vlan, location.value(), ips, annotations);
            this.location = location;
        }

        void setLocation(Timestamped<HostLocation> location) {
            this.location = location;
        }

        @Override
        public HostLocation location() {
            return location.value();
        }

        public Timestamp timestamp() {
            return location.timestamp();
        }
    }

    private void notifyPeers(InternalHostRemovedEvent event) throws IOException {
        broadcastMessage(GossipHostStoreMessageSubjects.HOST_REMOVED, event);
    }

    private void notifyPeers(InternalHostEvent event) throws IOException {
        broadcastMessage(GossipHostStoreMessageSubjects.HOST_UPDATED, event);
    }

    private void broadcastMessage(MessageSubject subject, Object event) throws IOException {
        ClusterMessage message = new ClusterMessage(
                clusterService.getLocalNode().id(),
                subject,
                SERIALIZER.encode(event));
        clusterCommunicator.broadcast(message);
    }

    private void unicastMessage(NodeId peer,
                                MessageSubject subject,
                                Object event) throws IOException {
        ClusterMessage message = new ClusterMessage(
                clusterService.getLocalNode().id(),
                subject,
                SERIALIZER.encode(event));
        clusterCommunicator.unicast(message, peer);
    }

    private void notifyDelegateIfNotNull(HostEvent event) {
        if (event != null) {
            notifyDelegate(event);
        }
    }

    private final class InternalHostEventListener
            implements ClusterMessageHandler {
        @Override
        public void handle(ClusterMessage message) {

            log.debug("Received host update event from peer: {}", message.sender());
            InternalHostEvent event = SERIALIZER.decode(message.payload());

            ProviderId providerId = event.providerId();
            HostId hostId = event.hostId();
            HostDescription hostDescription = event.hostDescription();
            Timestamp timestamp = event.timestamp();

            executor.submit(new Runnable() {

                @Override
                public void run() {
                    try {
                        notifyDelegateIfNotNull(createOrUpdateHostInternal(providerId,
                                                                           hostId,
                                                                           hostDescription,
                                                                           timestamp));
                    } catch (Exception e) {
                        log.warn("Exception thrown handling host removed", e);
                    }
                }
            });
        }
    }

    private final class InternalHostRemovedEventListener
            implements ClusterMessageHandler {
        @Override
        public void handle(ClusterMessage message) {

            log.debug("Received host removed event from peer: {}", message.sender());
            InternalHostRemovedEvent event = SERIALIZER.decode(message.payload());

            HostId hostId = event.hostId();
            Timestamp timestamp = event.timestamp();

            executor.submit(new Runnable() {

                @Override
                public void run() {
                    try {
                        notifyDelegateIfNotNull(removeHostInternal(hostId, timestamp));
                    } catch (Exception e) {
                        log.warn("Exception thrown handling host removed", e);
                    }
                }
            });
        }
    }

    private final class SendAdvertisementTask implements Runnable {

        @Override
        public void run() {
            if (Thread.currentThread().isInterrupted()) {
                log.info("Interrupted, quitting");
                return;
            }

            try {
                final NodeId self = clusterService.getLocalNode().id();
                Set<ControllerNode> nodes = clusterService.getNodes();

                ImmutableList<NodeId> nodeIds = FluentIterable.from(nodes)
                        .transform(toNodeId())
                        .toList();

                if (nodeIds.size() == 1 && nodeIds.get(0).equals(self)) {
                    log.trace("No other peers in the cluster.");
                    return;
                }

                NodeId peer;
                do {
                    int idx = RandomUtils.nextInt(0, nodeIds.size());
                    peer = nodeIds.get(idx);
                } while (peer.equals(self));

                HostAntiEntropyAdvertisement ad = createAdvertisement();

                if (Thread.currentThread().isInterrupted()) {
                    log.info("Interrupted, quitting");
                    return;
                }

                try {
                    unicastMessage(peer, GossipHostStoreMessageSubjects.HOST_ANTI_ENTROPY_ADVERTISEMENT, ad);
                } catch (IOException e) {
                    log.debug("Failed to send anti-entropy advertisement to {}", peer);
                    return;
                }
            } catch (Exception e) {
                // catch all Exception to avoid Scheduled task being suppressed.
                log.error("Exception thrown while sending advertisement", e);
            }
        }
    }

    private HostAntiEntropyAdvertisement createAdvertisement() {
        final NodeId self = clusterService.getLocalNode().id();

        Map<HostFragmentId, Timestamp> timestamps = new HashMap<>(hosts.size());
        Map<HostId, Timestamp> tombstones = new HashMap<>(removedHosts.size());

        hosts.forEach((hostId, hostInfo) -> {
            final ProviderId providerId = hostInfo.providerId();
            timestamps.put(new HostFragmentId(hostId, providerId), hostInfo.timestamp());
        });

        removedHosts.forEach((hostId, timestamped) -> {
            tombstones.put(hostId, timestamped.timestamp());
        });

        return new HostAntiEntropyAdvertisement(self, timestamps, tombstones);
    }

    private synchronized void handleAntiEntropyAdvertisement(HostAntiEntropyAdvertisement ad) {

        final NodeId sender = ad.sender();

        for (Entry<HostId, StoredHost> host : hosts.entrySet()) {
            // for each locally live Hosts...
            final HostId hostId = host.getKey();
            final StoredHost localHost = host.getValue();
            final ProviderId providerId = localHost.providerId();
            final HostFragmentId hostFragId = new HostFragmentId(hostId, providerId);
            final Timestamp localLiveTimestamp = localHost.timestamp();

            Timestamp remoteTimestamp = ad.timestamps().get(hostFragId);
            if (remoteTimestamp == null) {
                remoteTimestamp = ad.tombstones().get(hostId);
            }
            if (remoteTimestamp == null ||
                localLiveTimestamp.compareTo(remoteTimestamp) > 0) {

                // local is more recent, push
                // TODO: annotation is lost
                final HostDescription desc = new DefaultHostDescription(
                            localHost.mac(),
                            localHost.vlan(),
                            localHost.location(),
                            localHost.ipAddresses());
                try {
                    unicastMessage(sender, GossipHostStoreMessageSubjects.HOST_UPDATED,
                            new InternalHostEvent(providerId, hostId, desc, localHost.timestamp()));
                } catch (IOException e1) {
                    log.debug("Failed to send advertisement response", e1);
                }
            }

            final Timestamp remoteDeadTimestamp = ad.tombstones().get(hostId);
            if (remoteDeadTimestamp != null &&
                remoteDeadTimestamp.compareTo(localLiveTimestamp) > 0) {
                // sender has recent remove
                notifyDelegateIfNotNull(removeHostInternal(hostId, remoteDeadTimestamp));
            }
        }

        for (Entry<HostId, Timestamped<Host>> dead : removedHosts.entrySet()) {
            // for each locally dead Hosts
            final HostId hostId = dead.getKey();
            final Timestamp localDeadTimestamp = dead.getValue().timestamp();

            // TODO: pick proper ProviderId, when supporting multi-provider
            final ProviderId providerId = dead.getValue().value().providerId();
            final HostFragmentId hostFragId = new HostFragmentId(hostId, providerId);

            final Timestamp remoteLiveTimestamp = ad.timestamps().get(hostFragId);
            if (remoteLiveTimestamp != null &&
                localDeadTimestamp.compareTo(remoteLiveTimestamp) > 0) {
                // sender has zombie, push
                try {
                    unicastMessage(sender, GossipHostStoreMessageSubjects.HOST_REMOVED,
                            new InternalHostRemovedEvent(hostId, localDeadTimestamp));
                } catch (IOException e1) {
                    log.debug("Failed to send advertisement response", e1);
                }
            }
        }


        for (Entry<HostId, Timestamp> e : ad.tombstones().entrySet()) {
            // for each remote tombstone advertisement...
            final HostId hostId = e.getKey();
            final Timestamp adRemoveTimestamp = e.getValue();

            final StoredHost storedHost = hosts.get(hostId);
            if (storedHost == null) {
                continue;
            }
            if (adRemoveTimestamp.compareTo(storedHost.timestamp()) > 0) {
                // sender has recent remove info, locally remove
                notifyDelegateIfNotNull(removeHostInternal(hostId, adRemoveTimestamp));
            }
        }
    }

    private final class InternalHostAntiEntropyAdvertisementListener
            implements ClusterMessageHandler {

        @Override
        public void handle(ClusterMessage message) {
            log.trace("Received Host Anti-Entropy advertisement from peer: {}", message.sender());
            HostAntiEntropyAdvertisement advertisement = SERIALIZER.decode(message.payload());
            backgroundExecutor.submit(new Runnable() {

                @Override
                public void run() {
                    try {
                        handleAntiEntropyAdvertisement(advertisement);
                    } catch (Exception e) {
                        log.warn("Exception thrown handling Host advertisements", e);
                    }
                }
            });
        }
    }
}
