package org.mitallast.queue.crdt.bucket;

import org.mitallast.queue.crdt.log.ReplicatedLog;
import org.mitallast.queue.crdt.registry.CrdtRegistry;
import org.mitallast.queue.crdt.replication.Replicator;
import org.mitallast.queue.crdt.replication.state.ReplicaState;

import java.io.Closeable;
import java.util.concurrent.locks.ReentrantLock;

public interface Bucket extends Closeable {

    int index();

    long replica();

    ReentrantLock lock();

    CrdtRegistry registry();

    ReplicatedLog log();

    Replicator replicator();

    ReplicaState state();

    void delete();

    @Override
    void close();
}
