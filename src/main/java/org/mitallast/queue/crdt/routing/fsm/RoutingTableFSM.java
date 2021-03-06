package org.mitallast.queue.crdt.routing.fsm;

import com.google.common.base.Preconditions;
import com.typesafe.config.Config;
import javaslang.control.Option;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.mitallast.queue.common.events.EventBus;
import org.mitallast.queue.common.file.FileService;
import org.mitallast.queue.common.stream.StreamInput;
import org.mitallast.queue.common.stream.StreamOutput;
import org.mitallast.queue.common.stream.StreamService;
import org.mitallast.queue.common.stream.Streamable;
import org.mitallast.queue.crdt.routing.Resource;
import org.mitallast.queue.crdt.routing.RoutingBucket;
import org.mitallast.queue.crdt.routing.RoutingReplica;
import org.mitallast.queue.crdt.routing.RoutingTable;
import org.mitallast.queue.crdt.routing.event.RoutingTableChanged;
import org.mitallast.queue.raft.protocol.RaftSnapshotMetadata;
import org.mitallast.queue.raft.resource.ResourceFSM;
import org.mitallast.queue.raft.resource.ResourceRegistry;

import javax.inject.Inject;
import java.io.File;

public class RoutingTableFSM implements ResourceFSM {
    private final Logger logger = LogManager.getLogger();
    private final EventBus eventBus;
    private final StreamService streamService;
    private final File file;

    private volatile long lastApplied;
    private volatile RoutingTable routingTable;

    @Inject
    public RoutingTableFSM(
        Config config,
        EventBus eventBus,
        ResourceRegistry registry,
        FileService fileService,
        StreamService streamService
    ) {
        this.eventBus = eventBus;
        this.streamService = streamService;
        this.file = fileService.resource("crdt", "routing.bin");

        this.lastApplied = 0;
        this.routingTable = new RoutingTable(
            config.getInt("crdt.replicas"),
            config.getInt("crdt.buckets")
        );

        restore();

        registry.register(this);
        registry.register(AddResource.class, this::handle);
        registry.register(RemoveResource.class, this::handle);
        registry.register(UpdateMembers.class, this::handle);

        registry.register(AddReplica.class, this::handle);
        registry.register(CloseReplica.class, this::handle);
        registry.register(RemoveReplica.class, this::handle);

        registry.register(RoutingTable.class, this::handle);
    }

    public RoutingTable get() {
        return routingTable;
    }

    private void restore() {
        if (file.length() > 0) {
            try (StreamInput input = streamService.input(file)) {
                lastApplied = input.readLong();
                routingTable = input.readStreamable(RoutingTable::new);
            }
        }
    }

    private void persist(long index, RoutingTable routingTable) {
        Preconditions.checkArgument(index > lastApplied);
        this.lastApplied = index;
        logger.info("before: {}", this.routingTable);
        this.routingTable = routingTable;
        logger.info("after: {}", this.routingTable);
        try (StreamOutput output = streamService.output(file)) {
            output.writeLong(lastApplied);
            output.writeStreamable(routingTable);
        }
        eventBus.trigger(new RoutingTableChanged(index, routingTable));
    }

    private Streamable handle(long index, RoutingTable routingTable) {
        if (index <= lastApplied) {
            return null;
        }
        persist(index, routingTable);
        return null;
    }

    private AddResourceResponse handle(long index, AddResource request) {
        if (index <= lastApplied) {
            return null;
        }
        if (routingTable.hasResource(request.id())) {
            return new AddResourceResponse(request.type(), request.id(), false);
        }
        Resource resource = new Resource(
            request.id(),
            request.type()
        );
        persist(index, routingTable.withResource(resource));
        return new AddResourceResponse(request.type(), request.id(), true);
    }

    private RemoveResourceResponse handle(long index, RemoveResource request) {
        if (index <= lastApplied) {
            return null;
        }
        if (routingTable.hasResource(request.id())) {
            persist(index, routingTable.withoutResource(request.id()));
            return new RemoveResourceResponse(request.type(), request.id(), true);
        }
        return new RemoveResourceResponse(request.type(), request.id(), false);
    }

    private Streamable handle(long index, UpdateMembers updateMembers) {
        if (index <= lastApplied) {
            return null;
        }
        persist(index, routingTable.withMembers(updateMembers.members()));
        return null;
    }

    private Streamable handle(long index, AddReplica request) {
        if (index <= lastApplied) {
            return null;
        }
        RoutingBucket routingBucket = routingTable.bucket(request.bucket());
        if (!routingBucket.exists(request.member())) {
            persist(index, routingTable.withReplica(request.bucket(), request.member()));
        } else {
            logger.warn("node {} already allocated in bucket {}", request.member(), request.bucket());
        }
        return null;
    }

    private Streamable handle(long index, CloseReplica request) {
        if (index <= lastApplied) {
            return null;
        }
        RoutingBucket routingBucket = routingTable.bucket(request.bucket());
        Option<RoutingReplica> replica = routingBucket.replicas().get(request.replica());
        if (replica.exists(RoutingReplica::isOpened)) {
            persist(index, routingTable.withReplica(request.bucket(), replica.get().close()));
        }
        return null;
    }

    private Streamable handle(long index, RemoveReplica request) {
        if (index <= lastApplied) {
            return null;
        }
        RoutingBucket routingBucket = routingTable.bucket(request.bucket());
        Option<RoutingReplica> replica = routingBucket.replicas().get(request.replica());
        if (replica.exists(RoutingReplica::isClosed)) {
            persist(index, routingTable.withoutReplica(request.bucket(), request.replica()));
        }
        return null;
    }

    @Override
    public Option<Streamable> prepareSnapshot(RaftSnapshotMetadata snapshotMeta) {
        return Option.some(routingTable);
    }
}
